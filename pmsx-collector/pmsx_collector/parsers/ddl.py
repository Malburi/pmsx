"""DB 메타 수집 — DDL 파일 파서 (DB 접속 없이 테이블·컬럼·PK·FK·코멘트·프로시저).

DB 직접 수집은 dbmeta.py + parsers/db_meta.py (Python 드라이버, 읽기 전용).
"""
from __future__ import annotations

import re
from pathlib import Path

import sqlglot
from sqlglot import exp

from ..keys import sp_key, tbl_key
from . import Context
from .sql_analyzer import analyze

# 프로시저/함수 블록은 SQL*Plus 관례대로 단독 '/' 줄(또는 파일 끝)에서 끝난다.
PROC_RE = re.compile(
    r"CREATE\s+(?:OR\s+REPLACE\s+)?(PROCEDURE|FUNCTION)\s+([\w$#.]+)(.*?)(?:^[ \t]*/[ \t]*$|\Z)",
    re.S | re.I | re.M)
TSQL_PROC_RE = re.compile(r"\s*(?:--[^\n]*\n\s*)*CREATE\s+(?:OR\s+ALTER\s+)?(PROC(?:EDURE)?|FUNCTION)\s+([\w$#.\[\]]+)", re.I)
MS_DESC_RE = re.compile(
    r"sp_addextendedproperty\s+(?:@name\s*=\s*)?N?'MS_Description'\s*,\s*(?:@value\s*=\s*)?N?'((?:[^']|'')*)'\s*,"
    r"\s*(?:@level0type\s*=\s*)?N?'SCHEMA'\s*,\s*(?:@level0name\s*=\s*)?N?'\w+'\s*,\s*(?:@level1type\s*=\s*)?N?'TABLE'\s*,"
    r"\s*(?:@level1name\s*=\s*)?N?'(\w+)'(?:\s*,\s*(?:@level2type\s*=\s*)?N?'COLUMN'\s*,\s*(?:@level2name\s*=\s*)?N?'(\w+)')?", re.I)
COMMENT_RE = re.compile(r"COMMENT\s+ON\s+(TABLE|COLUMN)\s+([\w$#.]+)\s+IS\s+'((?:[^']|'')*)'", re.I)


def dialect_of(ctx: Context) -> str:
    """sqlglot 방언: db.dialect 또는 system.dbType (mssql → tsql)."""
    d = ctx.config.get("db.dialect")
    if d:
        return d
    return {"mssql": "tsql", "sqlserver": "tsql", "postgresql": "postgres", "mysql": "mysql", "mariadb": "mysql",
            "tibero": "oracle"}.get((ctx.config.get("system.dbType") or "oracle").lower(), "oracle")


def _line_of(text: str, pos: int) -> int:
    return text.count("\n", 0, pos) + 1


class DdlParser:
    name = "ddl"
    layer = "db"

    def detect(self, root: Path) -> float:
        return 1.0 if any(root.rglob("*.sql")) else 0.0

    def parse(self, ctx: Context) -> None:
        cfg, m = ctx.config, ctx.model
        files = [cfg.resolve(f) for f in (cfg.get("db.ddlFiles") or [])]
        if not files:
            m.fail("db", "db.ddlFiles", "DDL 파일이 지정되지 않아 테이블 메타를 수집하지 못했습니다.")
            return
        dialect = dialect_of(ctx)
        procs: list[tuple[Path, str, str, int, str]] = []
        for f in files:
            if not f or not f.is_file():
                m.fail("db", str(f), "DDL 파일 없음")
                continue
            text = f.read_text(encoding="utf-8-sig")
            if dialect == "tsql":
                # T-SQL: 배치(GO) 단위. CREATE PROC/FUNCTION 배치는 프로시저, 나머지는 테이블·주석
                body_parts = []
                pos = 0
                for batch in re.split(r"^[ \t]*GO[ \t]*;?[ \t]*$", text, flags=re.M | re.I):
                    start = text.find(batch, pos)
                    pos = max(pos, start)
                    pm = TSQL_PROC_RE.match(batch)
                    if pm:
                        procs.append((f, "FUNCTION" if pm.group(1).upper().startswith("FUNC") else "PROCEDURE",
                                      re.sub(r"[\[\]]", "", pm.group(2)).split(".")[-1].upper(),
                                      _line_of(text, start + pm.start(1)), batch.strip()))
                    else:
                        body_parts.append(batch)
                self._tables(ctx, f, text, ";\n".join(body_parts), dialect)
            else:
                # 프로시저 블록은 따로 떼어 낸다
                for pm in PROC_RE.finditer(text):
                    procs.append((f, pm.group(1).upper(), pm.group(2).split(".")[-1].upper(), _line_of(text, pm.start()), pm.group(0)))
                body = PROC_RE.sub("", text)
                self._tables(ctx, f, text, body, dialect)
        for f, kind, name, line, src in procs:
            register_routine(ctx, ctx.config.rel(f), kind, name, line, src, dialect)
        link_routine_calls(ctx)

    # ---------- 테이블 ----------
    def _tables(self, ctx: Context, f: Path, full: str, body: str, dialect: str = "oracle"):
        cfg, m = ctx.config, ctx.model
        schema = cfg.schema
        comments_t: dict[str, str] = {}
        comments_c: dict[tuple[str, str], str] = {}
        for cm in COMMENT_RE.finditer(body):
            target, txt = cm.group(2).upper(), cm.group(3).replace("''", "'")
            if cm.group(1).upper() == "TABLE":
                comments_t[target.split(".")[-1]] = txt
            else:
                parts = target.split(".")
                comments_c[(parts[-2], parts[-1])] = txt
        for cm in MS_DESC_RE.finditer(body):         # MSSQL: EXEC sp_addextendedproperty 'MS_Description', N'…', …
            txt, tname, cname = cm.group(1).replace("''", "'"), cm.group(2).upper(), cm.group(3)
            if cname:
                comments_c[(tname, cname.upper())] = txt
            else:
                comments_t[tname] = txt
        fks: list[tuple[str, list[str], str, list[str]]] = []

        stmts = re.split(r";|(?=\bCREATE\s+TABLE\b)", body, flags=re.I)     # T-SQL 은 ';' 생략이 흔함
        for stmt in [s for s in stmts if re.search(r"\bCREATE\s+TABLE\b", s, re.I)]:
            try:
                tree = sqlglot.parse_one(stmt, read=dialect)
            except Exception as e:  # noqa: BLE001
                m.fail("db", cfg.rel(f), f"CREATE TABLE 파싱 실패: {str(e)[:120]}")
                continue
            if not isinstance(tree, exp.Create) or not isinstance(tree.this, exp.Schema):
                continue
            tname = tree.this.this.name.upper()
            cols, pk = [], []
            for item in tree.this.expressions:
                if isinstance(item, exp.ColumnDef):
                    cname = item.name.upper()
                    kinds = [c.args.get("kind") for c in item.args.get("constraints") or []]
                    col = {"name": cname,
                           "dataType": item.args["kind"].sql(dialect) if item.args.get("kind") else "UNKNOWN",
                           "nullable": not any(isinstance(k, exp.NotNullColumnConstraint) for k in kinds),
                           "pk": False}
                    for k in kinds:
                        if isinstance(k, exp.DefaultColumnConstraint):
                            col["default"] = k.this.sql(dialect)
                        if isinstance(k, exp.PrimaryKeyColumnConstraint):
                            pk.append(cname)
                    if (tname, cname) in comments_c:
                        col["comment"] = comments_c[(tname, cname)]
                    cols.append(col)
                for c in ([item] if isinstance(item, (exp.PrimaryKey, exp.ForeignKey)) else
                          item.expressions if isinstance(item, exp.Constraint) else []):
                    if isinstance(c, exp.PrimaryKey):
                        pk.extend(i.name.upper() for i in c.expressions)
                    elif isinstance(c, exp.ForeignKey):
                        ref = c.args.get("reference")
                        if ref is not None and isinstance(ref.this, exp.Schema):
                            fks.append((tname, [i.name.upper() for i in c.expressions],
                                        ref.this.this.name.upper(), [i.name.upper() for i in ref.this.expressions]))
            for col in cols:
                col["pk"] = col["name"] in pk
            m.add_table(tbl_key(schema, tname), schema, tname, comment=comments_t.get(tname), columns=cols)
            ctx.table_pk[tname] = pk
            ctx.table_cols[tname] = {c["name"] for c in cols}

        for child, ccols, parent, pcols in fks:
            m.relations.append({"from": tbl_key(schema, child), "to": tbl_key(schema, parent),
                                "columns": [[a, b] for a, b in zip(ccols, pcols)], "kind": "fk", "confidence": 1.0})



_TSQL_STMT_START = re.compile(r"^\s*(SELECT|INSERT|UPDATE|DELETE|MERGE|END|IF|ELSE|WHILE|RETURN|DECLARE|SET|BEGIN|EXEC|EXECUTE|COMMIT|ROLLBACK)\b",
                              re.I | re.M)


def _dml_statements(body: str, dialect: str) -> list[str]:
    """프로시저 본문에서 DML 문장만 잘라 낸다. T-SQL 은 ';' 생략이 흔해 줄머리 키워드로 끊는다."""
    if dialect != "tsql":
        return [sm.group(0) for sm in re.finditer(r"\b(SELECT|INSERT|UPDATE|DELETE|MERGE)\b.*?;", body, re.S | re.I)]
    starts = [mm.start() for mm in _TSQL_STMT_START.finditer(body)] + [len(body)]
    out = []
    for a, b in zip(starts, starts[1:]):
        chunk = body[a:b].strip().rstrip(";")
        if re.match(r"(SELECT|INSERT|UPDATE|DELETE|MERGE)\b", chunk, re.I):
            out.append(chunk)
    return out


def register_routine(ctx: Context, file_label: str, kind: str, name: str, line: int | None, src: str,
                     dialect: str = "oracle") -> str:
    """프로시저/함수 1건 → sqlStatements(sp:) + 본문 DML 의 accesses 엣지. DDL 파일·DB 직접 수집 공용."""
    cfg, m = ctx.config, ctx.model
    key = sp_key(cfg.schema, name)
    m.sql[key] = {"key": key, "kind": "procedure" if kind.upper() == "PROCEDURE" else "function", "file": file_label}
    if line:
        m.sql[key]["line"] = line
    if cfg.include_snippets:
        m.sql[key]["text"] = src.strip()
    body = re.split(r"\bBEGIN\b", src, maxsplit=1, flags=re.I) if dialect != "tsql" else re.split(r"\bAS\b", src, maxsplit=1, flags=re.I)
    body = body[1] if len(body) > 1 else src
    if dialect != "tsql":
        body = re.split(r"\bEXCEPTION\b", body, maxsplit=1, flags=re.I)[0]
    ev = f"{file_label}:{line}" if line else file_label
    ctx.routine_bodies[key] = (body, dialect, ev)
    for stmt in _dml_statements(body, dialect):
        info = analyze(stmt, ctx.table_cols, dialect)
        for t, v in info.tables.items():
            crud = "".join(c for c in "CRUD" if c in v["crud"])
            m.add_edge(key, tbl_key(cfg.schema, t), "accesses", confidence=0.9 if info.parsed else 0.7,
                       crud=crud or "R", columns=sorted(v["columns"]) or None, evidence=ev)
            if tbl_key(cfg.schema, t) not in m.tables:
                m.add_table(tbl_key(cfg.schema, t), cfg.schema, t, comment="DDL 미확인")
        for a, b in info.joins:
            ctx.join_evidence.append((key, a, b))
    return key


_PLSQL_KEYWORDS = {"IF", "ELSIF", "WHILE", "FOR", "LOOP", "RETURN", "VALUES", "IN", "EXISTS", "NVL", "COUNT", "SUM", "MAX",
                   "MIN", "AVG", "DECODE", "SUBSTR", "ROUND", "TO_CHAR", "TO_DATE", "TO_NUMBER", "COALESCE", "CAST", "TRIM",
                   "UPPER", "LOWER", "LENGTH", "INSTR", "REPLACE", "AND", "OR", "NOT", "RAISE", "SQLERRM", "SYSDATE", "NOW",
                   "DATEADD", "GETDATE", "ISNULL", "CONVERT", "OBJECT_ID", "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE",
                   "USING", "ON", "WHERE", "SET", "INTO", "FROM", "JOIN", "BETWEEN", "OVER", "PARTITION", "ROW_NUMBER"}


def link_routine_calls(ctx: Context) -> None:
    """프로시저 본문의 다른 프로시저 호출 → sp→sp calls 엣지 (모든 루틴 등록 후 실행).

    PL/SQL: `SP_X(…);` · `CALL SP_X(…)` · `PKG.PROC(…)`, T-SQL: `EXEC[UTE] [dbo.]SP_X …`.
    정의가 없는 패키지/외부 프로시저 호출은 경고로만 남긴다.
    """
    cfg, m = ctx.config, ctx.model
    known = {k.split(".", 1)[1].upper(): k for k in m.sql if k.startswith("sp:")}
    for key, (body, dialect, ev) in ctx.routine_bodies.items():
        me = key.split(".", 1)[1].upper()
        names: list[str] = []
        if dialect == "tsql":
            names += [n for n in re.findall(r"\bEXEC(?:UTE)?\s+(?:@\w+\s*=\s*)?([\[\]\w.]+)", body, re.I)]
        names += re.findall(r"\bCALL\s+([\w$#.]+)", body, re.I)
        # 문장 머리의 프로시저 호출: 줄 시작 또는 ; BEGIN THEN ELSE LOOP 뒤
        names += re.findall(r"(?:^|;|\bBEGIN\b|\bTHEN\b|\bELSE\b|\bLOOP\b)\s*([A-Za-z_][\w$#]*(?:\.[A-Za-z_][\w$#]*)?)\s*\(", body, re.M | re.I)
        seen: set[str] = set()
        for raw in names:
            nm = raw.replace("[", "").replace("]", "")
            short = nm.split(".")[-1].upper()
            full = nm.upper()
            if short in _PLSQL_KEYWORDS or short == me or full in seen:
                continue
            seen.add(full)
            target = known.get(full) or known.get(short)
            if target and target != key:
                m.add_edge(key, target, "calls", confidence=0.9, evidence=ev)
            elif "." in nm or dialect == "tsql":
                m.warn("sql", key, f"정의를 찾지 못한 프로시저 호출: {nm} ({ev})")
