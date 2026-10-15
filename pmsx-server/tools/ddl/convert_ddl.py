"""common(PostgreSQL·H2) Flyway 스크립트 → oracle / sqlserver 벤더 스크립트 생성기.

사용:  python tools/ddl/convert_ddl.py            (pmsx-server 에서)
결과:  src/main/resources/db/migration/{oracle,sqlserver}/V*__*.sql  (덮어씀)

규칙 요약
- 공통 DDL 작성 규칙: 한 줄에 컬럼 하나, 문장 끝은 줄 끝 ';', 타입은 VARCHAR(n)/CHAR(n)/TEXT/BOOLEAN/INTEGER/BIGINT/
  DOUBLE PRECISION/NUMERIC(p,s)/DATE/TIMESTAMP WITH TIME ZONE 만 사용.
- Oracle : VARCHAR(n≤1000)→VARCHAR2(n CHAR), VARCHAR(n>1000)→VARCHAR2(4000), TEXT→CLOB, BOOLEAN→NUMBER(1),
           INTEGER→NUMBER(10), BIGINT→NUMBER(19), DOUBLE PRECISION→FLOAT(53), NUMERIC→NUMBER,
           ADD COLUMN→ADD (...), ALTER COLUMN DROP NOT NULL→MODIFY (c NULL), 다건 VALUES→행별 INSERT.
           빈 문자열은 NULL 이므로 DEFAULT '' 컬럼은 기본값·NOT NULL 을 뺀다.
- SQL Server : VARCHAR→NVARCHAR, TEXT→NVARCHAR(MAX), BOOLEAN→${bit}(기본 BIT), INTEGER→INT, DOUBLE PRECISION→FLOAT,
           NUMERIC→DECIMAL, TIMESTAMP WITH TIME ZONE→${tstz}(기본 DATETIMEOFFSET), ADD COLUMN→ADD,
           한글 리터럴→N'...'.
"""
import io
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
MIG = os.path.join(ROOT, "src", "main", "resources", "db", "migration")
COMMON = os.path.join(MIG, "common")

# 벤더별 개별 조정: (table, column) → 대체 타입 (Oracle 인덱스 키 길이 6398 바이트 제한 대응 등)
ORACLE_TYPE_OVERRIDE = {
    # UQ_AS_RELATION 키 합계가 CHAR 의미(×4 바이트)로 6398 바이트를 넘으므로 JSON 컬럼은 BYTE 의미로 둔다
    ("AS_RELATION", "COLUMN_PAIRS"): "VARCHAR2(1000)",
}

# Oracle 은 빈 문자열을 NULL 로 저장하므로, 사용자가 비워 둘 수 있는 본문·라벨 컬럼은 NOT NULL 을 뺀다
ORACLE_NULLABLE = {
    ("AS_NOTE", "CONTENT"), ("QA_COMMENT", "CONTENT"), ("SM_SR_COMMENT", "CONTENT"), ("SM_KB", "CONTENT"),
    ("CM_TERM", "DEFINITION"), ("RS_TIMESHEET_ENTRY", "TARGET_LABEL"), ("SM_SR", "REQ_NAME"), ("AS_NODE", "NAME"),
    ("QA_ACTION_ITEM", "CONTENT"), ("SM_INCIDENT_TIMELINE", "CONTENT"),
}

TYPE_RE = re.compile(
    r"\b(TIMESTAMP WITH TIME ZONE|DOUBLE PRECISION|VARCHAR\(\d+\)|CHAR\(\d+\)|NUMERIC\(\d+\s*,\s*\d+\)|TEXT|BOOLEAN|INTEGER|BIGINT|DATE)(?!\w)")
ADD_COL_RE = re.compile(r"^ALTER TABLE (\w+) ADD COLUMN (\w+) (.*?);(.*)$", re.S)
DROP_NN_RE = re.compile(r"^ALTER TABLE (\w+) ALTER COLUMN (\w+) DROP NOT NULL;(.*)$", re.S)
CREATE_RE = re.compile(r"^CREATE TABLE (\w+) \(", re.S)
INSERT_RE = re.compile(r"^INSERT INTO (\w+) \(([^)]*)\) VALUES\s*(.*);\s*$", re.S)


def map_type_oracle(t, table=None, col=None):
    if (table, col) in ORACLE_TYPE_OVERRIDE:
        return ORACLE_TYPE_OVERRIDE[(table, col)]
    m = re.match(r"VARCHAR\((\d+)\)", t)
    if m:
        n = int(m.group(1))
        return f"VARCHAR2({n} CHAR)" if n <= 1000 else "VARCHAR2(4000)"
    m = re.match(r"NUMERIC\((\d+)\s*,\s*(\d+)\)", t)
    if m:
        return f"NUMBER({m.group(1)}, {m.group(2)})"
    return {
        "TEXT": "CLOB", "BOOLEAN": "NUMBER(1)", "INTEGER": "NUMBER(10)", "BIGINT": "NUMBER(19)",
        "DOUBLE PRECISION": "FLOAT(53)", "DATE": "DATE", "TIMESTAMP WITH TIME ZONE": "TIMESTAMP WITH TIME ZONE",
    }.get(t, t)


def map_type_mssql(t, table=None, col=None):
    m = re.match(r"VARCHAR\((\d+)\)", t)
    if m:
        n = int(m.group(1))
        return f"NVARCHAR({n})" if n <= 4000 else "NVARCHAR(MAX)"
    m = re.match(r"CHAR\((\d+)\)", t)
    if m:
        return f"NCHAR({m.group(1)})"
    m = re.match(r"NUMERIC\((\d+)\s*,\s*(\d+)\)", t)
    if m:
        return f"DECIMAL({m.group(1)}, {m.group(2)})"
    return {
        "TEXT": "NVARCHAR(MAX)", "BOOLEAN": "${bit}", "INTEGER": "INT", "BIGINT": "BIGINT",
        "DOUBLE PRECISION": "FLOAT", "DATE": "DATE", "TIMESTAMP WITH TIME ZONE": "${tstz}",
    }.get(t, t)


def split_code_comment(line):
    """'--' 주석을 분리 (문자열 안 '--' 는 무시)."""
    q = False
    for i, ch in enumerate(line):
        if ch == "'":
            q = not q
        elif not q and line.startswith("--", i):
            return line[:i], line[i:]
    return line, ""


def convert_literals(code, vendor):
    """문자열 밖의 TRUE/FALSE/CURRENT_TIMESTAMP 변환, MSSQL 한글 리터럴 N 접두."""
    out, i = [], 0
    while i < len(code):
        if code[i] == "'":
            j = i + 1
            while j < len(code):
                if code[j] == "'" and j + 1 < len(code) and code[j + 1] == "'":
                    j += 2
                    continue
                if code[j] == "'":
                    break
                j += 1
            lit = code[i:j + 1]
            if vendor == "mssql" and any(ord(c) > 127 for c in lit):
                lit = "N" + lit
            out.append(lit)
            i = j + 1
            continue
        m = re.match(r"\b(TRUE|FALSE|CURRENT_TIMESTAMP)\b", code[i:])
        if m and (i == 0 or not (code[i - 1].isalnum() or code[i - 1] == "_")):
            w = m.group(1)
            if w == "TRUE":
                out.append("1")
            elif w == "FALSE":
                out.append("0")
            else:
                out.append("CURRENT_TIMESTAMP" if vendor == "mssql" else "SYSTIMESTAMP")
            i += len(w)
            continue
        out.append(code[i])
        i += 1
    return "".join(out)


def column_line(line, table, vendor, schema):
    code, comment = split_code_comment(line)
    stripped = code.strip()
    if not stripped or stripped.upper().startswith(("CONSTRAINT", "PRIMARY KEY", "UNIQUE", "FOREIGN KEY")) or stripped == ")":
        new = convert_literals(code, vendor)
        return new + comment
    m = re.match(r"(\s*)(\w+)(\s+)(.*)$", code)
    if not m:
        return convert_literals(code, vendor) + comment
    indent, col, sp, rest = m.groups()
    tm = TYPE_RE.search(rest)
    base = tm.group(1) if tm else None
    schema.setdefault(table, {})[col] = base
    if tm:
        mapped = map_type_oracle(base, table, col) if vendor == "oracle" else map_type_mssql(base, table, col)
        rest = rest[:tm.start()] + mapped + rest[tm.end():]
    if vendor == "oracle" and (table, col) in ORACLE_NULLABLE and "NOT NULL" in rest:
        rest = re.sub(r"\s*NOT NULL", "", rest)
        comment = (comment + " " if comment else "-- ") + "[oracle] 빈 값 허용(빈 문자열=NULL)"
    if vendor == "oracle" and re.search(r"DEFAULT ''", rest):
        rest = re.sub(r"\s*DEFAULT ''", "", rest)
        rest = re.sub(r"\s*NOT NULL", "", rest)
        comment = (comment + " " if comment else "-- ") + "[oracle] 빈 문자열=NULL 이라 NOT NULL·기본값 제외"
    return indent + col + sp + convert_literals(rest, vendor) + comment


def convert_statement(stmt, vendor, schema):
    s = stmt.rstrip("\n")
    head = s.lstrip()
    if not head or head.startswith("--") and ";" not in head:
        return s
    # 앞쪽 주석 줄 보존
    lines = s.split("\n")
    lead = []
    while lines and (lines[0].strip().startswith("--") or not lines[0].strip()):
        lead.append(lines.pop(0))
    body = "\n".join(lines)
    prefix = "\n".join(lead) + ("\n" if lead else "")

    m = CREATE_RE.match(body)
    if m:
        table = m.group(1)
        out = []
        for i, ln in enumerate(body.split("\n")):
            out.append(ln if i == 0 else column_line(ln, table, vendor, schema))
        return prefix + "\n".join(out)

    m = ADD_COL_RE.match(body)
    if m:
        table, col, rest, tail = m.groups()
        tm = TYPE_RE.search(rest)
        base = tm.group(1) if tm else None
        schema.setdefault(table, {})[col] = base
        if tm:
            mapped = map_type_oracle(base, table, col) if vendor == "oracle" else map_type_mssql(base, table, col)
            rest = rest[:tm.start()] + mapped + rest[tm.end():]
        rest = convert_literals(rest, vendor)
        if vendor == "oracle":
            return prefix + f"ALTER TABLE {table} ADD ({col} {rest});{tail}"
        return prefix + f"ALTER TABLE {table} ADD {col} {rest};{tail}"

    m = DROP_NN_RE.match(body)
    if m:
        table, col, tail = m.groups()
        base = schema.get(table, {}).get(col)
        if vendor == "oracle":
            return prefix + f"ALTER TABLE {table} MODIFY ({col} NULL);{tail}"
        mapped = map_type_mssql(base, table, col)
        return prefix + f"ALTER TABLE {table} ALTER COLUMN {col} {mapped} NULL;{tail}"

    m = INSERT_RE.match(body)
    if m and vendor == "oracle":
        table, cols, values = m.groups()
        rows = split_rows(values)
        return prefix + "\n".join(
            f"INSERT INTO {table} ({cols}) VALUES {convert_literals(r, vendor)};" for r in rows)

    return prefix + convert_literals(body, vendor)


def split_rows(values):
    rows, depth, q, cur = [], 0, False, []
    for ch in values:
        if ch == "'":
            q = not q
        if not q:
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
        cur.append(ch)
        if not q and depth == 0 and ch == ")":
            rows.append("".join(cur).strip().lstrip(",").strip())
            cur = []
    return rows


def split_statements(text):
    """줄 끝 ';' 기준으로 문장 분리 (문장 사이 주석·빈 줄은 다음 문장 앞에 붙는다)."""
    stmts, cur = [], []
    for line in text.split("\n"):
        cur.append(line)
        code, _ = split_code_comment(line)
        if code.rstrip().endswith(";"):
            stmts.append("\n".join(cur))
            cur = []
    tail = "\n".join(cur)
    return stmts, tail


def main():
    files = sorted(f for f in os.listdir(COMMON) if f.endswith(".sql"))
    for vendor, folder in (("oracle", "oracle"), ("mssql", "sqlserver")):
        schema = {}
        outdir = os.path.join(MIG, folder)
        os.makedirs(outdir, exist_ok=True)
        for f in files:
            text = io.open(os.path.join(COMMON, f), encoding="utf-8").read()
            stmts, tail = split_statements(text)
            out = [convert_statement(s, vendor, schema) for s in stmts]
            header = (f"-- 자동 생성: tools/ddl/convert_ddl.py ← common/{f} ({vendor}). 직접 고치지 말고 생성기를 고칠 것.\n")
            body = "\n".join(out) + ("\n" + tail if tail.strip() else "\n")
            body = re.sub(r"\s*\(H2 MODE=PostgreSQL / PostgreSQL 공용\)", "", body)
            body = re.sub(r"^-- H2\(MODE=PostgreSQL\) / PostgreSQL 공용 문법.*\n", "", body, flags=re.M)
            io.open(os.path.join(outdir, f), "w", encoding="utf-8", newline="\n").write(header + body)
        print(vendor, "->", outdir, len(files), "files")


if __name__ == "__main__":
    sys.exit(main())
