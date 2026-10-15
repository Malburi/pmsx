"""SQL 문 분석 — 테이블·CRUD·컬럼·조인 조건 추출 (sqlglot, 실패 시 정규식).

결과: SqlInfo(tables={TABLE: {"crud": set, "columns": set}}, joins=[((T,C),(T,C))], parsed=bool)
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

import sqlglot
from sqlglot import exp

IGNORE_TABLES = {"DUAL"}


@dataclass
class SqlInfo:
    tables: dict[str, dict] = field(default_factory=dict)
    joins: list[tuple[tuple[str, str], tuple[str, str]]] = field(default_factory=list)
    parsed: bool = True
    procedures: list[str] = field(default_factory=list)   # CALL 대상 프로시저명

    def touch(self, table: str, crud: str, columns=()):
        t = table.upper()
        if t in IGNORE_TABLES:
            return
        ent = self.tables.setdefault(t, {"crud": set(), "columns": set()})
        ent["crud"].update(crud)
        ent["columns"].update(c.upper() for c in columns)

    def merge(self, other: "SqlInfo"):
        for t, v in other.tables.items():
            self.touch(t, "".join(v["crud"]), v["columns"])
        self.joins.extend(other.joins)
        self.procedures.extend(other.procedures)
        self.parsed = self.parsed and other.parsed


CALL_RE = re.compile(r"\{\s*call\s+([\w$#.]+)\s*\(", re.I)


def _split_statements(sql: str) -> list[str]:
    """익명 블록(BEGIN ... END;) 은 풀어서 개별 문장으로."""
    s = sql.strip().rstrip(";").strip()
    m = re.match(r"^BEGIN\b(.*?)(?:;\s*)?END\s*;?\s*$", s, re.S | re.I)
    if m:
        s = m.group(1)
    return [p.strip() for p in s.split(";") if p.strip()]


def analyze(sql: str, known_cols: dict[str, set[str]] | None = None, dialect: str = "oracle") -> SqlInfo:
    info = SqlInfo()
    call = CALL_RE.search(sql)
    if call:
        info.procedures.append(call.group(1).split(".")[-1].upper())
        return info
    for stmt in _split_statements(sql):
        info.merge(_analyze_one(stmt, known_cols or {}, dialect))
    return info


def _strip_select_into(stmt: str) -> str:
    # PL/SQL: SELECT a, b INTO v1, v2 FROM ... -> SELECT a, b FROM ...
    return re.sub(r"\bINTO\b.*?\bFROM\b", "FROM", stmt, count=1, flags=re.S | re.I) if re.match(r"\s*SELECT\b", stmt, re.I) else stmt


def _analyze_one(stmt: str, known_cols: dict[str, set[str]], dialect: str) -> SqlInfo:
    stmt = _strip_select_into(stmt)
    try:
        tree = sqlglot.parse_one(stmt, read=dialect)
    except Exception:  # noqa: BLE001 - 방언 미지원 구문은 정규식으로
        return _regex_fallback(stmt)
    if tree is None:
        return _regex_fallback(stmt)

    info = SqlInfo()
    alias: dict[str, str] = {}
    tables_in_stmt: list[str] = []
    for t in tree.find_all(exp.Table):
        name = t.name.upper()
        if not name or name in IGNORE_TABLES:
            continue
        tables_in_stmt.append(name)
        alias[name] = name
        if t.alias:
            alias[t.alias.upper()] = name

    target = None
    target_crud = ""
    if isinstance(tree, exp.Insert):
        target, target_crud = _table_name(tree.this), "C"
    elif isinstance(tree, exp.Update):
        target, target_crud = _table_name(tree.this), "U"
    elif isinstance(tree, exp.Delete):
        target, target_crud = _table_name(tree.this), "D"
    elif isinstance(tree, exp.Merge):
        target = _table_name(tree.this)
        target_crud = "".join(c for c, cls in (("U", exp.Update), ("C", exp.Insert), ("D", exp.Delete))
                              if any(True for _ in tree.find_all(cls))) or "CU"

    for name in tables_in_stmt:
        if name != target:
            info.touch(name, "R")
    if target:
        info.touch(target, target_crud)

    def owner(col: exp.Column) -> str | None:
        q = col.table.upper() if col.table else ""
        if q:
            return alias.get(q)
        uniq = set(tables_in_stmt)
        if len(uniq) == 1:
            return next(iter(uniq))
        if target and col.name.upper() in known_cols.get(target, set()):
            return target
        for t in uniq:
            if col.name.upper() in known_cols.get(t, set()):
                return t
        return None

    # 컬럼 귀속
    for col in tree.find_all(exp.Column):
        t = owner(col)
        name = col.name.upper()
        if not t or not name or name in ("NEXTVAL", "CURRVAL"):
            continue
        if known_cols.get(t) and name not in known_cols[t]:
            continue  # 변수·별칭 등 실제 컬럼이 아닌 것
        info.touch(t, "", [name])
    # INSERT 컬럼 목록
    if isinstance(tree, exp.Insert) and isinstance(tree.this, exp.Schema) and target:
        info.touch(target, "", [i.name for i in tree.this.expressions if isinstance(i, exp.Identifier)])

    # 조인 조건 (A.x = B.y, 서로 다른 별칭)
    for eq in tree.find_all(exp.EQ):
        l, r = eq.left, eq.right
        if isinstance(l, exp.Column) and isinstance(r, exp.Column) and l.table and r.table:
            la, ra = l.table.upper(), r.table.upper()
            if la == ra:
                continue
            lt, rt = alias.get(la), alias.get(ra)
            if lt and rt:
                info.joins.append(((lt, l.name.upper()), (rt, r.name.upper())))
    return info


def _table_name(node) -> str | None:
    if isinstance(node, exp.Schema):
        node = node.this
    if isinstance(node, exp.Table):
        return node.name.upper()
    return None


_FALLBACK = [
    (re.compile(r"\bINSERT\s+INTO\s+([\w$#.]+)", re.I), "C"),
    (re.compile(r"\bUPDATE\s+([\w$#.]+)\s", re.I), "U"),
    (re.compile(r"\bDELETE\s+(?:FROM\s+)?([\w$#.]+)", re.I), "D"),
    (re.compile(r"\bMERGE\s+INTO\s+([\w$#.]+)", re.I), "CU"),
    (re.compile(r"\b(?:FROM|JOIN)\s+([\w$#.]+)", re.I), "R"),
]


def _regex_fallback(stmt: str) -> SqlInfo:
    info = SqlInfo(parsed=False)
    for rx, crud in _FALLBACK:
        for m in rx.finditer(stmt):
            name = m.group(1).split(".")[-1]
            if name.upper() not in ("SET", "SELECT"):
                info.touch(name, crud)
    return info
