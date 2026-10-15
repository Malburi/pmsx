"""DB 메타 직접 수집 — 커서 목킹 단위 테스트 + 로컬 SQLEXPRESS 읽기 전용 통합 테스트(없으면 생략)."""
import re

import pytest

from pmsx_collector.config import Config
from pmsx_collector.dbmeta import DIALECTS, MsSql, Oracle, _in, collect_meta, open_readonly, run_select
from pmsx_collector.model import ModelBuilder
from pmsx_collector.parsers import Context
from pmsx_collector.parsers.db_meta import DbMetaParser

from conftest import SAMPLE


class Recorder:
    """실행된 SQL 기록 + commit 호출 감지용 연결/커서 프록시."""

    def __init__(self, conn=None, rows_for=None):
        self.conn, self.rows_for, self.sql, self.committed, self.rolled_back = conn, rows_for, [], False, False
        self._rows = []
        self.description = None

    # connection
    def cursor(self):
        if self.conn is None:
            return self
        real = self.conn.cursor()
        rec = self

        class Cur:
            def execute(self, sql, *a):
                rec.sql.append(sql)
                real.execute(sql, *a)
                self.description = real.description

            def fetchall(self):
                return real.fetchall()
        return Cur()

    def commit(self):
        self.committed = True

    def rollback(self):
        self.rolled_back = True
        if self.conn is not None:
            self.conn.rollback()

    def close(self):
        if self.conn is not None:
            self.conn.close()

    # fake cursor
    def execute(self, sql, *a):
        self.sql.append(sql)
        self._rows = self.rows_for(sql)

    def fetchall(self):
        return self._rows


ORACLE_ROWS = [
    ("ALL_TAB_COLUMNS", [
        ("HR", "TB_EMP", "EMP_NO", "VARCHAR2(8)", "N", None, "사원번호", 1),
        ("HR", "TB_EMP", "DEPT_CD", "VARCHAR2(10)", "N", None, "부서코드", 2),
        ("HR", "TB_EMP", "USE_YN", "CHAR(1)", "N", "'Y' ", None, 3),
        ("HR", "TB_DEPT", "DEPT_CD", "VARCHAR2(10)", "N", None, None, 1),
        ("HR", "TB_DEPT", "DEPT_NM", "VARCHAR2(100)", "Y", None, None, 2)]),
    ("CONSTRAINT_TYPE = 'P'", [("HR", "TB_EMP", "EMP_NO", 1), ("HR", "TB_DEPT", "DEPT_CD", 1)]),
    ("CONSTRAINT_TYPE = 'R'", [("HR", "TB_EMP", "FK_EMP_DEPT", "DEPT_CD", "HR", "TB_DEPT", "DEPT_CD", 1)]),
    ("ALL_INDEXES", [("HR", "TB_EMP", "IX_EMP_01", 0, "DEPT_CD", 1), ("HR", "TB_EMP", "IX_EMP_01", 0, "USE_YN", 2)]),
    ("ALL_SOURCE", [("HR", "SP_EMP_RETIRE", "PROCEDURE", 1, "PROCEDURE SP_EMP_RETIRE(p_emp IN VARCHAR2) IS\n"),
                    ("HR", "SP_EMP_RETIRE", "PROCEDURE", 2, "BEGIN\n  UPDATE TB_EMP SET USE_YN = 'N' WHERE EMP_NO = p_emp;\n"),
                    ("HR", "SP_EMP_RETIRE", "PROCEDURE", 3, "END;\n")]),
    ("ALL_TABLES", [("HR", "TB_EMP", "table", "사원"), ("HR", "TB_DEPT", "table", "부서")]),
]


def _oracle_rows(sql):
    for marker, rows in ORACLE_ROWS:
        if marker in sql:
            return rows
    return []


def _cfg(raw) -> Config:
    return Config(SAMPLE / "collector.yaml", raw)


def test_fake_oracle_meta_to_model():
    cfg = _cfg({"system": {"code": "HR"}, "db": {"type": "oracle", "schema": "HR", "connection": {"host": "x"}},
                "source": {"includeSnippets": True}})
    rec = Recorder(rows_for=_oracle_rows)
    m = ModelBuilder("HR", "인사", [], [], "oracle")
    ctx = Context(cfg, m)
    DbMetaParser(connect=lambda: (Oracle(), rec)).parse(ctx)

    emp = m.tables["tbl:HR.TB_EMP"]
    assert emp["comment"] == "사원"
    assert [c["name"] for c in emp["columns"]] == ["EMP_NO", "DEPT_CD", "USE_YN"]
    assert emp["columns"][0]["pk"] is True and emp["columns"][2]["default"] == "'Y'"
    assert emp["indexes"] == [{"name": "IX_EMP_01", "columns": ["DEPT_CD", "USE_YN"], "unique": False}]
    assert {"from": "tbl:HR.TB_EMP", "to": "tbl:HR.TB_DEPT", "columns": [["DEPT_CD", "DEPT_CD"]], "kind": "fk",
            "confidence": 1.0} in m.relations
    sp = m.sql["sp:HR.SP_EMP_RETIRE"]
    assert sp["kind"] == "procedure" and sp["file"] == "db:oracle/HR"
    acc = [e for e in m.edges.values() if e["from"] == "sp:HR.SP_EMP_RETIRE"]
    assert acc[0]["to"] == "tbl:HR.TB_EMP" and acc[0]["crud"] == "U"
    # ★ 읽기 전용: SELECT 만 실행, commit 없음, rollback 으로 종료
    assert all(re.match(r"\s*SELECT\b", q) for q in rec.sql), rec.sql
    assert not rec.committed and rec.rolled_back


def test_all_dialect_queries_are_select_only():
    for name, cls in DIALECTS.items():
        d = cls()
        for part, q in d.queries(_in(["HR"], d.upper), {}).items():
            assert re.match(r"\s*SELECT\b", q), f"{name}.{part}"
            assert not re.search(r"\b(INSERT|UPDATE|DELETE|MERGE|DROP|ALTER|CREATE|TRUNCATE|GRANT|EXEC)\b", q), f"{name}.{part}"


def test_schema_identifier_is_validated():
    with pytest.raises(ValueError):
        _in(["HR'; DROP TABLE X; --"], True)


def test_menu_query_must_be_single_select():
    cfg = _cfg({"system": {"code": "HR"}, "db": {"type": "oracle"}})
    for bad in ("DELETE FROM TB_MENU", "SELECT 1 FROM DUAL; DROP TABLE TB_MENU"):
        with pytest.raises(ValueError):
            run_select(cfg, bad)


def test_connection_failure_is_recorded_not_raised():
    cfg = _cfg({"system": {"code": "HR"}, "db": {"type": "nosuchdb", "connection": {"host": "x"}}})
    m = ModelBuilder("HR", "인사", [], [], None)
    DbMetaParser().parse(Context(cfg, m))
    assert m.failures and "DB 메타 수집 실패" in m.failures[0]["message"]


# ---------------------------------------------------------------- 로컬 SQLEXPRESS (읽기 전용)
SQLEXPRESS = {"system": {"code": "MS"},
              "db": {"type": "mssql", "schema": "dbo", "schemas": ["dbo"], "includeSystemObjects": True,
                     "connection": {"host": r"localhost\SQLEXPRESS", "database": "master", "trusted": True,
                                    "odbcExtra": "Encrypt=no"}}}


@pytest.fixture(scope="module")
def sqlexpress():
    try:
        import pyodbc  # noqa: F401
        d, conn = open_readonly(_cfg(SQLEXPRESS))
        conn.close()
    except Exception as e:  # noqa: BLE001
        pytest.skip(f"로컬 SQLEXPRESS 접속 불가: {str(e)[:80]}")


def test_sqlexpress_master_catalog_readonly(sqlexpress):
    cfg = _cfg(SQLEXPRESS)
    holder = {}

    def connect():
        d, real = open_readonly(cfg)
        holder["rec"] = Recorder(conn=real)
        return d, holder["rec"]

    meta = collect_meta(cfg, connect)
    rec = holder["rec"]
    assert isinstance(meta.tables, list) and isinstance(meta.columns, list)
    assert meta.source == "mssql" and meta.sql_dialect == "tsql"
    if meta.tables:                                  # master.dbo 의 MS 기본 테이블(spt_*) 등
        assert meta.columns
    assert all(re.match(r"\s*SELECT\b", q) for q in rec.sql), rec.sql
    assert not rec.committed and rec.rolled_back
    assert isinstance(MsSql().queries(_in(["dbo"], False), {})["tables"], str)
