"""DB 직접 수집 파서 — dbmeta.collect_meta 결과(테이블·컬럼·PK·FK·인덱스·코멘트·SP 본문)를 중간모델에 넣는다.

db.connection 이 설정되면 DDL 파일(ddl 파서) 대신 또는 함께 사용한다. 같은 테이블이 둘 다 있으면 DB 쪽이 우선.
"""
from __future__ import annotations

from collections import defaultdict
from pathlib import Path

from ..dbmeta import DbMeta, collect_meta
from ..keys import tbl_key
from . import Context
from .ddl import link_routine_calls, register_routine


class DbMetaParser:
    name = "db-meta"
    layer = "db"

    def __init__(self, connect=None):
        self._connect = connect            # 테스트용 가짜 연결 주입

    def detect(self, root: Path) -> float:
        return 0.0

    def parse(self, ctx: Context) -> None:
        cfg, m = ctx.config, ctx.model
        try:
            meta = collect_meta(cfg, self._connect)
        except Exception as e:  # noqa: BLE001 - 드라이버 미설치·접속 실패 모두 기록 후 계속
            m.fail("db", f"db:{cfg.get('db.type')}", f"DB 메타 수집 실패: {str(e).splitlines()[0][:200]}")
            return
        apply_meta(ctx, meta)


def apply_meta(ctx: Context, meta: DbMeta) -> None:
    cfg, m = ctx.config, ctx.model
    default = cfg.schema
    configured = {s.upper(): s for s in (cfg.get("db.schemas") or [default])}

    def sch(s: str) -> str:
        # 기본 스키마는 SQL 분석과 같은 키 표기(cfg.schema)를 쓴다 → 스키마 미지정 SQL 참조와 같은 테이블 키
        return default if s.upper() == default.upper() else configured.get(s.upper(), s)

    cols: dict[tuple, list] = defaultdict(list)
    for schema, table, col, dtype, nullable, dflt, comment, ordinal in meta.columns:
        cols[(schema, table)].append((int(ordinal or 0), col, dtype, nullable, dflt, comment))
    pks: dict[tuple, list] = defaultdict(list)
    for schema, table, col, pos in meta.pks:
        pks[(schema, table)].append((int(pos or 0), col.upper()))
    idx: dict[tuple, dict] = defaultdict(dict)
    for schema, table, iname, unique, col, pos in meta.indexes:
        i = idx[(schema, table)].setdefault(iname, {"name": iname, "unique": bool(unique), "_cols": []})
        i["_cols"].append((int(pos or 0), col.upper()))

    for schema, table, kind, comment in meta.tables:
        pk = [c for _, c in sorted(pks.get((schema, table), []))]
        columns = []
        for _, col, dtype, nullable, dflt, ccomment in sorted(cols.get((schema, table), []), key=lambda r: r[0]):
            c = {"name": col.upper(), "dataType": str(dtype or "UNKNOWN").strip(), "nullable": _bool(nullable),
                 "pk": col.upper() in pk}
            if dflt not in (None, ""):
                c["default"] = str(dflt).strip()
            if ccomment:
                c["comment"] = str(ccomment)
            columns.append(c)
        indexes = [{"name": i["name"], "columns": [c for _, c in sorted(i["_cols"])], "unique": i["unique"]}
                   for i in idx.get((schema, table), {}).values()]
        key = tbl_key(sch(schema), table)
        t = m.add_table(key, sch(schema), table, comment=comment or None, columns=columns, indexes=indexes or None)
        t["kind"] = "view" if kind == "view" else "table"
        ctx.table_pk[table.upper()] = pk
        ctx.table_cols[table.upper()] = {c["name"] for c in columns}

    fks: dict[tuple, list] = defaultdict(list)
    for schema, table, name, col, rschema, rtable, rcol, pos in meta.fks:
        fks[(schema, table, name, rschema, rtable)].append((int(pos or 0), col.upper(), rcol.upper()))
    have = {(r["from"], r["to"], tuple(map(tuple, r["columns"]))) for r in m.relations}
    for (schema, table, _name, rschema, rtable), pairs in sorted(fks.items()):
        rel = {"from": tbl_key(sch(schema), table), "to": tbl_key(sch(rschema), rtable),
               "columns": [[a, b] for _, a, b in sorted(pairs)], "kind": "fk", "confidence": 1.0}
        if (rel["from"], rel["to"], tuple(map(tuple, rel["columns"]))) not in have:
            m.relations.append(rel)

    for schema, name, typ, body in meta.routines:
        if body:
            register_routine(ctx, f"db:{meta.source}/{schema}", typ, name, None, str(body), meta.sql_dialect)
    link_routine_calls(ctx)


def _bool(v) -> bool:
    if isinstance(v, str):
        return v.upper() in ("Y", "YES", "TRUE", "1")
    return bool(v)
