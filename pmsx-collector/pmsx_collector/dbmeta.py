"""DB 메타 직접 수집 (Python 드라이버, 읽기 전용) — docs/04 §2 'DB 메타 수집'.

지원: oracle(oracledb thin, Oracle Client 불필요) · tibero(pyodbc + Tibero ODBC, Oracle 호환 딕셔너리 뷰)
      postgresql(psycopg) · mssql(pyodbc + ODBC Driver 17/18) · mysql/mariadb(pymysql)

원칙
  - 카탈로그·딕셔너리 SELECT 만 실행한다. 세션을 읽기 전용으로 설정할 수 있는 DB 는 설정하고,
    끝나면 commit 없이 rollback 후 닫는다.
  - 스키마 이름은 식별자 규칙([A-Za-z0-9_$#])만 허용해 질의에 리터럴로 넣는다 (드라이버마다 다른 paramstyle 회피).

설정 (collector.yaml)
  db:
    type: mssql                     # oracle | tibero | postgresql | mssql | mysql | mariadb
    schema: dbo                     # SQL 분석 시 스키마 미지정 테이블의 기본 스키마 (키 표기)
    schemas: [dbo]                  # 수집할 스키마(오라클은 OWNER, MySQL 은 DB 이름). 기본 [schema]
    includeRoutines: true           # 프로시저·함수 본문 수집
    includeSystemObjects: false     # MSSQL is_ms_shipped 객체 포함 여부
    connection:
      host: localhost\\SQLEXPRESS
      port: 1433                    # 생략 가능
      database: HRDB                # PG/MSSQL/MySQL DB 이름
      service: HRDEV                # Oracle 서비스명 (또는 dsn: host:port/service)
      user: PMSX_RO
      passwordEnv: PMSX_DB_PW       # 비밀번호는 환경변수로만
      trusted: true                 # MSSQL Windows 인증
      odbcDriver: ODBC Driver 17 for SQL Server   # mssql/tibero
      odbcExtra: "Encrypt=no"       # 연결 문자열 추가 항목
"""
from __future__ import annotations

import os
import re
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Any, Callable

IDENT = re.compile(r"^[A-Za-z0-9_$#]+$")


@dataclass
class DbMeta:
    tables: list[tuple] = field(default_factory=list)      # (schema, table, kind, comment)
    columns: list[tuple] = field(default_factory=list)     # (schema, table, column, type, nullable, default, comment, ord)
    pks: list[tuple] = field(default_factory=list)         # (schema, table, column, pos)
    fks: list[tuple] = field(default_factory=list)         # (schema, table, name, column, rschema, rtable, rcolumn, pos)
    indexes: list[tuple] = field(default_factory=list)     # (schema, table, index, unique, column, pos)
    routines: list[tuple] = field(default_factory=list)    # (schema, name, PROCEDURE|FUNCTION, body)
    source: str = ""                                       # oracle | mssql ... (출처 표기)
    sql_dialect: str = "oracle"                            # SP 본문 분석용 sqlglot 방언


def _in(schemas: list[str], upper: bool) -> str:
    bad = [s for s in schemas if not IDENT.match(s)]
    if bad:
        raise ValueError(f"스키마 이름에 허용되지 않는 문자: {bad}")
    return ", ".join(f"'{s.upper() if upper else s}'" for s in schemas)


# ---------------------------------------------------------------- 방언별 질의
class Dialect:
    name = ""
    upper = False                  # 스키마 리터럴을 대문자로 (Oracle 계열)
    sqlglot = "oracle"             # SP 본문 분석 방언

    def connect(self, c: dict, pw: str | None):  # pragma: no cover - 드라이버별
        raise NotImplementedError

    def read_only(self, conn) -> None:
        pass

    def queries(self, s: str, opts: dict) -> dict[str, str]:  # s = IN 목록
        raise NotImplementedError

    def routines(self, cur, s: str, opts: dict) -> list[tuple]:
        cur.execute(self.queries(s, opts)["routines"])
        return [tuple(r) for r in cur.fetchall()]


class Oracle(Dialect):
    name, upper = "oracle", True

    def connect(self, c, pw):
        import oracledb
        dsn = c.get("dsn") or f"{c.get('host', 'localhost')}:{c.get('port', 1521)}/{c.get('service', c.get('database', ''))}"
        return oracledb.connect(user=c.get("user"), password=pw, dsn=dsn)

    def read_only(self, conn):
        conn.cursor().execute("SET TRANSACTION READ ONLY")

    def queries(self, s, opts):
        return {
            "tables": f"""SELECT t.OWNER, t.TABLE_NAME, 'table', c.COMMENTS FROM ALL_TABLES t
                LEFT JOIN ALL_TAB_COMMENTS c ON c.OWNER = t.OWNER AND c.TABLE_NAME = t.TABLE_NAME
                WHERE t.OWNER IN ({s})
                UNION ALL SELECT v.OWNER, v.VIEW_NAME, 'view', c.COMMENTS FROM ALL_VIEWS v
                LEFT JOIN ALL_TAB_COMMENTS c ON c.OWNER = v.OWNER AND c.TABLE_NAME = v.VIEW_NAME
                WHERE v.OWNER IN ({s})""",
            "columns": f"""SELECT c.OWNER, c.TABLE_NAME, c.COLUMN_NAME,
                c.DATA_TYPE || CASE WHEN c.DATA_TYPE IN ('VARCHAR2','NVARCHAR2','CHAR','NCHAR','RAW') THEN '(' || c.CHAR_LENGTH || ')'
                                    WHEN c.DATA_TYPE = 'NUMBER' AND c.DATA_PRECISION IS NOT NULL
                                    THEN '(' || c.DATA_PRECISION || CASE WHEN c.DATA_SCALE > 0 THEN ',' || c.DATA_SCALE END || ')' END,
                c.NULLABLE, c.DATA_DEFAULT, m.COMMENTS, c.COLUMN_ID
                FROM ALL_TAB_COLUMNS c LEFT JOIN ALL_COL_COMMENTS m
                  ON m.OWNER = c.OWNER AND m.TABLE_NAME = c.TABLE_NAME AND m.COLUMN_NAME = c.COLUMN_NAME
                WHERE c.OWNER IN ({s})""",
            "pks": f"""SELECT cc.OWNER, cc.TABLE_NAME, cc.COLUMN_NAME, cc.POSITION FROM ALL_CONSTRAINTS c
                JOIN ALL_CONS_COLUMNS cc ON cc.OWNER = c.OWNER AND cc.CONSTRAINT_NAME = c.CONSTRAINT_NAME
                WHERE c.CONSTRAINT_TYPE = 'P' AND c.OWNER IN ({s})""",
            "fks": f"""SELECT c.OWNER, c.TABLE_NAME, c.CONSTRAINT_NAME, cc.COLUMN_NAME, rc.OWNER, rc.TABLE_NAME, rcc.COLUMN_NAME, cc.POSITION
                FROM ALL_CONSTRAINTS c
                JOIN ALL_CONS_COLUMNS cc ON cc.OWNER = c.OWNER AND cc.CONSTRAINT_NAME = c.CONSTRAINT_NAME
                JOIN ALL_CONSTRAINTS rc ON rc.OWNER = c.R_OWNER AND rc.CONSTRAINT_NAME = c.R_CONSTRAINT_NAME
                JOIN ALL_CONS_COLUMNS rcc ON rcc.OWNER = rc.OWNER AND rcc.CONSTRAINT_NAME = rc.CONSTRAINT_NAME AND rcc.POSITION = cc.POSITION
                WHERE c.CONSTRAINT_TYPE = 'R' AND c.OWNER IN ({s})""",
            "indexes": f"""SELECT i.TABLE_OWNER, i.TABLE_NAME, i.INDEX_NAME, CASE i.UNIQUENESS WHEN 'UNIQUE' THEN 1 ELSE 0 END,
                ic.COLUMN_NAME, ic.COLUMN_POSITION FROM ALL_INDEXES i
                JOIN ALL_IND_COLUMNS ic ON ic.INDEX_OWNER = i.OWNER AND ic.INDEX_NAME = i.INDEX_NAME
                WHERE i.TABLE_OWNER IN ({s})""",
            "routines": f"""SELECT OWNER, NAME, TYPE, LINE, TEXT FROM ALL_SOURCE
                WHERE OWNER IN ({s}) AND TYPE IN ('PROCEDURE', 'FUNCTION', 'PACKAGE BODY')
                ORDER BY OWNER, NAME, TYPE, LINE""",
        }

    def routines(self, cur, s, opts):
        cur.execute(self.queries(s, opts)["routines"])
        src: dict[tuple, list[str]] = defaultdict(list)
        for owner, name, typ, _line, txt in cur.fetchall():
            src[(owner, name, "FUNCTION" if typ == "FUNCTION" else "PROCEDURE")].append(txt or "")
        # ALL_SOURCE 는 'PROCEDURE X ...' 로 시작 → DDL 파서와 같은 형태로 CREATE 를 붙인다
        return [(o, n, t, "CREATE OR REPLACE " + "".join(lines)) for (o, n, t), lines in src.items()]


class Tibero(Oracle):
    """Tibero: Oracle 호환 딕셔너리 뷰(ALL_*)를 그대로 쓰고, 연결은 Tibero ODBC 드라이버(pyodbc)."""
    name = "tibero"

    def connect(self, c, pw):
        import pyodbc
        driver = c.get("odbcDriver", "Tibero 7 ODBC Driver")
        parts = [f"DRIVER={{{driver}}}", f"SERVER={c.get('host', 'localhost')}", f"PORT={c.get('port', 8629)}",
                 f"DB={c.get('database', c.get('service', 'tibero'))}", f"UID={c.get('user', '')}", f"PWD={pw or ''}"]
        if c.get("odbcExtra"):
            parts.append(c["odbcExtra"])
        return pyodbc.connect(";".join(parts), autocommit=False, readonly=True)

    def read_only(self, conn):
        pass   # pyodbc readonly=True 로 연결


class Postgres(Dialect):
    name, sqlglot = "postgresql", "postgres"

    def connect(self, c, pw):
        import psycopg
        conn = psycopg.connect(host=c.get("host", "localhost"), port=c.get("port", 5432), dbname=c.get("database"),
                               user=c.get("user"), password=pw)
        return conn

    def read_only(self, conn):
        conn.read_only = True

    def queries(self, s, opts):
        return {
            "tables": f"""SELECT n.nspname, c.relname, CASE c.relkind WHEN 'v' THEN 'view' ELSE 'table' END,
                obj_description(c.oid, 'pg_class') FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relkind IN ('r', 'p', 'v') AND n.nspname IN ({s})""",
            "columns": f"""SELECT n.nspname, c.relname, a.attname, format_type(a.atttypid, a.atttypmod), NOT a.attnotnull,
                pg_get_expr(d.adbin, d.adrelid), col_description(c.oid, a.attnum), a.attnum
                FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace
                LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                WHERE a.attnum > 0 AND NOT a.attisdropped AND c.relkind IN ('r', 'p', 'v') AND n.nspname IN ({s})""",
            "pks": f"""SELECT n.nspname, c.relname, a.attname, k.ord FROM pg_index i
                JOIN pg_class c ON c.oid = i.indrelid JOIN pg_namespace n ON n.oid = c.relnamespace
                CROSS JOIN LATERAL unnest(i.indkey::int2[]) WITH ORDINALITY AS k(col, ord)
                JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = k.col
                WHERE i.indisprimary AND n.nspname IN ({s})""",
            "fks": f"""SELECT n.nspname, cl.relname, con.conname, a.attname, rn.nspname, rcl.relname, ra.attname, k.ord
                FROM pg_constraint con
                JOIN pg_class cl ON cl.oid = con.conrelid JOIN pg_namespace n ON n.oid = cl.relnamespace
                JOIN pg_class rcl ON rcl.oid = con.confrelid JOIN pg_namespace rn ON rn.oid = rcl.relnamespace
                CROSS JOIN LATERAL unnest(con.conkey, con.confkey) WITH ORDINALITY AS k(col, rcol, ord)
                JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.col
                JOIN pg_attribute ra ON ra.attrelid = con.confrelid AND ra.attnum = k.rcol
                WHERE con.contype = 'f' AND n.nspname IN ({s})""",
            "indexes": f"""SELECT n.nspname, t.relname, ic.relname, i.indisunique, a.attname, k.ord FROM pg_index i
                JOIN pg_class t ON t.oid = i.indrelid JOIN pg_class ic ON ic.oid = i.indexrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                CROSS JOIN LATERAL unnest(i.indkey::int2[]) WITH ORDINALITY AS k(col, ord)
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.col
                WHERE n.nspname IN ({s})""",
            "routines": f"""SELECT n.nspname, p.proname, CASE p.prokind WHEN 'p' THEN 'PROCEDURE' ELSE 'FUNCTION' END,
                pg_get_functiondef(p.oid) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname IN ({s}) AND p.prokind IN ('f', 'p')""",
        }


class MsSql(Dialect):
    name, sqlglot = "mssql", "tsql"

    def connect(self, c, pw):
        import pyodbc
        driver = c.get("odbcDriver", "ODBC Driver 17 for SQL Server")
        server = c.get("host", "localhost") + (f",{c['port']}" if c.get("port") else "")
        parts = [f"DRIVER={{{driver}}}", f"SERVER={server}", f"DATABASE={c.get('database', 'master')}",
                 "ApplicationIntent=ReadOnly"]
        if c.get("trusted"):
            parts.append("Trusted_Connection=yes")
        else:
            parts += [f"UID={c.get('user', '')}", f"PWD={pw or ''}"]
        if c.get("odbcExtra"):
            parts.append(c["odbcExtra"])
        return pyodbc.connect(";".join(parts), autocommit=False, readonly=True, timeout=10)

    def queries(self, s, opts):
        ms = "" if opts.get("includeSystemObjects") else "AND o.is_ms_shipped = 0"
        return {
            "tables": f"""SELECT s.name, o.name, CASE o.type WHEN 'V' THEN 'view' ELSE 'table' END,
                CAST(ep.value AS NVARCHAR(4000)) FROM sys.objects o JOIN sys.schemas s ON s.schema_id = o.schema_id
                LEFT JOIN sys.extended_properties ep ON ep.major_id = o.object_id AND ep.minor_id = 0 AND ep.name = 'MS_Description'
                WHERE o.type IN ('U', 'V') AND s.name IN ({s}) {ms}""",
            "columns": f"""SELECT s.name, o.name, c.name,
                TYPE_NAME(c.user_type_id) + CASE
                  WHEN TYPE_NAME(c.user_type_id) IN ('varchar', 'char', 'varbinary', 'binary')
                    THEN '(' + CASE c.max_length WHEN -1 THEN 'max' ELSE CAST(c.max_length AS VARCHAR(10)) END + ')'
                  WHEN TYPE_NAME(c.user_type_id) IN ('nvarchar', 'nchar')
                    THEN '(' + CASE c.max_length WHEN -1 THEN 'max' ELSE CAST(c.max_length / 2 AS VARCHAR(10)) END + ')'
                  WHEN TYPE_NAME(c.user_type_id) IN ('decimal', 'numeric')
                    THEN '(' + CAST(c.precision AS VARCHAR(5)) + ',' + CAST(c.scale AS VARCHAR(5)) + ')'
                  ELSE '' END,
                c.is_nullable, OBJECT_DEFINITION(c.default_object_id), CAST(ep.value AS NVARCHAR(4000)), c.column_id
                FROM sys.columns c JOIN sys.objects o ON o.object_id = c.object_id AND o.type IN ('U', 'V')
                JOIN sys.schemas s ON s.schema_id = o.schema_id
                LEFT JOIN sys.extended_properties ep ON ep.major_id = c.object_id AND ep.minor_id = c.column_id AND ep.name = 'MS_Description'
                WHERE s.name IN ({s}) {ms}""",
            "pks": f"""SELECT s.name, o.name, c.name, ic.key_ordinal FROM sys.indexes i
                JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
                JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                JOIN sys.objects o ON o.object_id = i.object_id JOIN sys.schemas s ON s.schema_id = o.schema_id
                WHERE i.is_primary_key = 1 AND s.name IN ({s}) {ms}""",
            "fks": f"""SELECT s.name, o.name, fk.name, c.name, rs.name, ro.name, rc.name, fkc.constraint_column_id
                FROM sys.foreign_keys fk JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id
                JOIN sys.objects o ON o.object_id = fk.parent_object_id JOIN sys.schemas s ON s.schema_id = o.schema_id
                JOIN sys.columns c ON c.object_id = fkc.parent_object_id AND c.column_id = fkc.parent_column_id
                JOIN sys.objects ro ON ro.object_id = fk.referenced_object_id JOIN sys.schemas rs ON rs.schema_id = ro.schema_id
                JOIN sys.columns rc ON rc.object_id = fkc.referenced_object_id AND rc.column_id = fkc.referenced_column_id
                WHERE s.name IN ({s}) {ms}""",
            "indexes": f"""SELECT s.name, o.name, i.name, i.is_unique, c.name, ic.key_ordinal FROM sys.indexes i
                JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
                JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                JOIN sys.objects o ON o.object_id = i.object_id AND o.type IN ('U', 'V') JOIN sys.schemas s ON s.schema_id = o.schema_id
                WHERE i.name IS NOT NULL AND ic.key_ordinal > 0 AND s.name IN ({s}) {ms}""",
            "routines": f"""SELECT s.name, o.name, CASE o.type WHEN 'P' THEN 'PROCEDURE' ELSE 'FUNCTION' END, m.definition
                FROM sys.sql_modules m JOIN sys.objects o ON o.object_id = m.object_id JOIN sys.schemas s ON s.schema_id = o.schema_id
                WHERE o.type IN ('P', 'FN', 'IF', 'TF') AND s.name IN ({s}) {ms}""",
        }


class MySql(Dialect):
    name, sqlglot = "mysql", "mysql"

    def connect(self, c, pw):
        import pymysql
        return pymysql.connect(host=c.get("host", "localhost"), port=int(c.get("port", 3306)), user=c.get("user"),
                               password=pw or "", database=c.get("database"), charset="utf8mb4")

    def read_only(self, conn):
        with conn.cursor() as cur:
            cur.execute("SET SESSION TRANSACTION READ ONLY")

    def queries(self, s, opts):
        return {
            "tables": f"""SELECT TABLE_SCHEMA, TABLE_NAME, CASE TABLE_TYPE WHEN 'VIEW' THEN 'view' ELSE 'table' END, TABLE_COMMENT
                FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ({s})""",
            "columns": f"""SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE = 'YES', COLUMN_DEFAULT,
                COLUMN_COMMENT, ORDINAL_POSITION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA IN ({s})""",
            "pks": f"""SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION FROM information_schema.KEY_COLUMN_USAGE
                WHERE CONSTRAINT_NAME = 'PRIMARY' AND TABLE_SCHEMA IN ({s})""",
            "fks": f"""SELECT TABLE_SCHEMA, TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME, REFERENCED_TABLE_SCHEMA, REFERENCED_TABLE_NAME,
                REFERENCED_COLUMN_NAME, ORDINAL_POSITION FROM information_schema.KEY_COLUMN_USAGE
                WHERE REFERENCED_TABLE_NAME IS NOT NULL AND TABLE_SCHEMA IN ({s})""",
            "indexes": f"""SELECT TABLE_SCHEMA, TABLE_NAME, INDEX_NAME, NON_UNIQUE = 0, COLUMN_NAME, SEQ_IN_INDEX
                FROM information_schema.STATISTICS WHERE TABLE_SCHEMA IN ({s})""",
            "routines": f"""SELECT ROUTINE_SCHEMA, ROUTINE_NAME, ROUTINE_TYPE, ROUTINE_DEFINITION FROM information_schema.ROUTINES
                WHERE ROUTINE_SCHEMA IN ({s})""",
        }


DIALECTS: dict[str, type[Dialect]] = {"oracle": Oracle, "tibero": Tibero, "postgresql": Postgres, "postgres": Postgres,
                                      "mssql": MsSql, "sqlserver": MsSql, "mysql": MySql, "mariadb": MySql}


def dialect_for(db_type: str) -> Dialect:
    try:
        return DIALECTS[(db_type or "").lower()]()
    except KeyError:
        raise ValueError(f"지원하지 않는 DB 종류: {db_type} (지원: {', '.join(sorted(DIALECTS))})") from None


# ---------------------------------------------------------------- 실행
def _password(c: dict) -> str | None:
    if c.get("passwordEnv"):
        return os.environ.get(c["passwordEnv"])
    return None                       # 설정 파일에 평문 비밀번호는 받지 않는다


def open_readonly(cfg) -> tuple[Dialect, Any]:
    d = dialect_for(cfg.get("db.type") or cfg.get("system.dbType"))
    conn = d.connect(cfg.get("db.connection") or {}, _password(cfg.get("db.connection") or {}))
    d.read_only(conn)
    return d, conn


def _close(conn) -> None:
    try:
        conn.rollback()               # 어떤 경우에도 commit 하지 않는다
    except Exception:  # noqa: BLE001
        pass
    conn.close()


def collect_meta(cfg, connect: Callable[[], tuple[Dialect, Any]] | None = None) -> DbMeta:
    """설정된 스키마들의 메타를 읽는다. connect 는 테스트에서 가짜 연결을 주입할 때 사용."""
    d, conn = (connect or (lambda: open_readonly(cfg)))()
    schemas = list(cfg.get("db.schemas") or [cfg.schema])
    opts = {"includeSystemObjects": bool(cfg.get("db.includeSystemObjects", False))}
    s = _in(schemas, d.upper)
    q = d.queries(s, opts)
    meta = DbMeta(source=d.name, sql_dialect=d.sqlglot)
    try:
        cur = conn.cursor()
        for part in ("tables", "columns", "pks", "fks", "indexes"):
            cur.execute(q[part])
            setattr(meta, part, [tuple(r) for r in cur.fetchall()])
        if cfg.get("db.includeRoutines", True):
            meta.routines = d.routines(cur, s, opts)
    finally:
        _close(conn)
    return meta


def run_select(cfg, sql: str) -> list[dict]:
    """메뉴 테이블 등 단순 조회 (SELECT/WITH 만 허용)."""
    if not re.match(r"^\s*(SELECT|WITH)\b", sql, re.I) or ";" in sql.strip().rstrip(";"):
        raise ValueError("menu.query 는 단일 SELECT 문만 허용합니다.")
    _, conn = open_readonly(cfg)
    try:
        cur = conn.cursor()
        cur.execute(sql.strip().rstrip(";"))
        names = [c[0].upper() for c in cur.description]
        return [dict(zip(names, ["" if v is None else str(v) for v in row])) for row in cur.fetchall()]
    finally:
        _close(conn)
