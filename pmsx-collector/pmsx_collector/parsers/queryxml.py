"""자체 프레임워크 쿼리 XML 파서 — `<query><id>X_S01</id><value>SELECT …</value></query>` 형식.

국내 레거시 자체 프레임워크(coperframe 등)는 MyBatis 대신 ID → SQL 매핑 XML 을 쓴다.
태그 이름과 동적 블록 문법은 설정(queryXml)으로 바꿀 수 있다.

    queryXml:
      files: ["WEB-INF/config/query/*.xml"]      # 소스 루트 기준 glob (여러 개)
      item: query          # 문장 하나를 감싸는 태그
      id: id               # 문장 ID 태그 (또는 속성명 — idAttr: id)
      sql: value           # SQL 본문 태그
      dynamicBlock: '\\$\\d+\\{'   # `$1{AND X = ?}` 같은 조건 블록 시작 (닫는 } 와 함께 벗겨 '모든 조건 참'으로 펼침)

키: ID 가 시스템 안에서 유일하면 `sql:<ID>`, 여러 파일에 같은 ID 가 있으면 `sql:<파일명>.<ID>`.
코드의 문자열 호출(dataAccesser.query("X_S01"))은 ctx.sql_by_ref[ID] 로 찾는다.
DDL 이 없으면 SQL 에 나온 테이블·컬럼을 '추정'으로 등록한다(실패로 기록하지 않음).
"""
from __future__ import annotations

import glob
import re
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

from ..keys import sp_key, tbl_key
from ..textio import read_xml_text
from . import Context
from .sql_analyzer import analyze

KIND_BY_VERB = {"SELECT": "select", "WITH": "select", "INSERT": "insert", "UPDATE": "update", "DELETE": "delete",
                "MERGE": "merge", "CALL": "procedure", "BEGIN": "procedure", "{CALL": "procedure", "DECLARE": "procedure"}


def strip_dynamic_blocks(sql: str, opener: str = r"\$\d+\{") -> str:
    """`$1{AND CD_ID = ?}` → `AND CD_ID = ?` (중첩 블록 포함, 닫는 괄호 짝 맞춤)."""
    rx = re.compile(opener)
    out: list[str] = []
    i = 0
    depth_stack: list[int] = []      # 열린 동적 블록의 '{' 깊이
    depth = 0
    while i < len(sql):
        m = rx.match(sql, i)
        if m:
            depth += 1
            depth_stack.append(depth)
            i = m.end()
            continue
        ch = sql[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            if depth_stack and depth_stack[-1] == depth:
                depth_stack.pop()
                depth -= 1
                i += 1
                continue
            depth -= 1
        out.append(ch)
        i += 1
    return "".join(out)


def plain_sql(text: str, opener: str) -> str:
    text = strip_dynamic_blocks(text, opener)
    text = re.sub(r"<!--.*?-->", " ", text, flags=re.S)
    text = re.sub(r"\?", ":p", text)                      # JDBC 바인드 ? → :p (sqlglot 호환)
    return re.sub(r"[ \t]+", " ", re.sub(r"\n\s*\n+", "\n", text)).strip()


def _kind(sql: str) -> str:
    head = sql.lstrip("( \n\t").split(None, 1)
    return KIND_BY_VERB.get(head[0].upper() if head else "", "other")


class QueryXmlParser:
    name = "queryxml"
    layer = "sql"

    def detect(self, root: Path) -> float:
        return 0.0

    def parse(self, ctx: Context) -> None:
        cfg, m = ctx.config, ctx.model
        spec = cfg.get("queryXml") or {}
        item_tag, id_tag, sql_tag = spec.get("item", "query"), spec.get("id", "id"), spec.get("sql", "value")
        id_attr = spec.get("idAttr")
        opener = spec.get("dynamicBlock", r"\$\d+\{")
        files: list[Path] = []
        for pat in spec.get("files", []) or []:
            base = cfg.source_root
            files += [Path(p) for p in sorted(glob.glob(str(base / pat), recursive=True))]
        if not files:
            m.fail("parse", "queryXml.files", "쿼리 XML 파일을 찾지 못함")
            return
        items: list[tuple[Path, str, str, int | None]] = []     # (파일, ID, SQL, 줄)
        for f in files:
            raw = read_xml_text(f)
            try:
                doc = ET.fromstring(re.sub(r"<!DOCTYPE[^>]*>", "", raw, count=1))
            except ET.ParseError as e:
                m.fail("parse", cfg.rel(f), f"쿼리 XML 파싱 실패: {e}")
                continue
            lines = raw.splitlines()
            for node in doc.iter(item_tag):
                sid = (node.get(id_attr) if id_attr else (node.findtext(id_tag) or "")).strip()
                body = node.find(sql_tag)
                if not sid or body is None:
                    continue
                text = "".join(body.itertext())
                line = next((i + 1 for i, ln in enumerate(lines) if sid in ln and (id_tag in ln or (id_attr and id_attr in ln))), None)
                items.append((f, sid, text, line))
        dup = Counter(sid for _, sid, _, _ in items)
        known_cols = ctx.table_cols
        has_ddl = bool(m.tables)
        for f, sid, text, line in items:
            stem = f.stem
            key = f"sql:{sid}" if dup[sid] == 1 else f"sql:{stem}.{sid}"
            ctx.sql_by_ref.setdefault(sid, key)
            ctx.sql_by_ref[f"{stem}.{sid}"] = key
            sql = plain_sql(text, opener)
            entry = {"key": key, "kind": _kind(sql), "namespace": stem, "file": cfg.rel(f)}
            if line:
                entry["line"] = line
            if cfg.include_snippets:
                entry["text"] = sql
            ev = f"{cfg.rel(f)}:{line}" if line else cfg.rel(f)
            info = analyze(sql, known_cols)
            if info.procedures:
                entry["kind"] = "procedure"
            m.sql[key] = entry
            for proc in info.procedures:
                m.add_edge(key, sp_key(cfg.schema, proc), "calls", confidence=0.99, evidence=ev)
            for t, v in info.tables.items():
                if not re.fullmatch(r"[A-Z_][\w$#]*", t):
                    continue
                tkey = tbl_key(cfg.schema, t)
                if tkey not in m.tables:
                    m.add_table(tkey, cfg.schema, t, comment="DDL 미확인 — SQL에서 추정" if not has_ddl else "DDL 미확인")
                    if has_ddl:
                        m.fail("parse", key, f"DDL에 없는 테이블 참조: {t}")
                    m.tables[tkey]["_inferred_cols"] = set()
                if "_inferred_cols" in m.tables[tkey]:
                    m.tables[tkey]["_inferred_cols"].update(v["columns"])
                crud = "".join(c for c in "CRUD" if c in v["crud"])
                m.add_edge(key, tkey, "accesses", confidence=0.95 if info.parsed else 0.7,
                           crud=crud or "R", columns=sorted(v["columns"]) or None, evidence=ev)
            if not info.parsed:
                m.warn("sql", key, "SQL 구문 분석 실패 → 정규식으로 테이블만 추정")
            for a, b in info.joins:
                ctx.join_evidence.append((key, a, b))
        dups = [s for s, n in dup.items() if n > 1]
        if dups:
            m.warn("sql", "queryXml", f"여러 파일에 같은 쿼리 ID {len(dups)}건 — 파일명.ID 로 구분 (예: {', '.join(dups[:3])})")
        # DDL 이 없을 때: SQL 에 나온 컬럼으로 테이블 정의 추정
        for t in m.tables.values():
            cols = t.pop("_inferred_cols", None)
            if cols is not None and not t.get("columns"):
                t["columns"] = [{"name": c, "dataType": "UNKNOWN", "comment": "SQL에서 추정"} for c in sorted(cols)]
