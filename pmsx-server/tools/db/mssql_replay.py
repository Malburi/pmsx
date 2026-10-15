"""SQL Server 실DB 재생 검증.

H2 MSSQLServer 모드 통합 테스트가 실행한 '번역된 SQL'(./gradlew sqlCapture → build/sql-capture/mssql/*.sql)을
실제 SQL Server 에 그대로 실행해 문법·타입·변환 오류를 찾는다. 시나리오마다 테스트 DB 를 새로 만든다.

사용 (pmsx-server 에서):
  python tools/db/mssql_replay.py [--server localhost\\SQLEXPRESS] [--db PMSX_TEST] [--keep]
  - sqlcmd(Windows 통합 인증)로 접속한다. TCP/IP 가 꺼져 있어도 공유 메모리로 동작한다.
  - 대상 DB 는 Korean_100_CS_AS 로 새로 만들고, 끝나면 삭제한다(--keep 이면 남김).

오류 분류
  - SQL 오류: 문법·컬럼·타입 변환 등 → 번역/DDL 문제 (0건이어야 함)
  - 상태 오류: 중복 키(2627·2601)·FK(547)·NULL(515) → H2 실행에서 롤백된 문장도 재생되므로 생길 수 있음(참고용)
"""
import argparse
import collections
import io
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "tools", "ddl"))
import convert_ddl  # noqa: E402

STATE_ERRORS = {2627, 2601, 547, 515}
ERR_RE = re.compile(r"^(?:메시지|Msg) (\d+), (?:수준|Level) (\d+)")


def sqlcmd(server, args, query=None, infile=None, outfile=None):
    cmd = ["sqlcmd", "-S", server, "-E", "-C", "-f", "65001"] + args
    if query:
        cmd += ["-Q", query]
    if infile:
        cmd += ["-i", infile]
    if outfile:
        cmd += ["-o", outfile]
    r = subprocess.run(cmd, capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace"))


def ddl_script(db, path):
    out = [f"USE {db};", "GO"]
    d = os.path.join(ROOT, "src", "main", "resources", "db", "migration", "sqlserver")
    for f in sorted(x for x in os.listdir(d) if x.endswith(".sql")):
        t = io.open(os.path.join(d, f), encoding="utf-8").read()
        t = t.replace("${tstz}", "DATETIMEOFFSET").replace("${bit}", "BIT")
        stmts, _ = convert_ddl.split_statements(t)
        for s in stmts:
            code = "\n".join(convert_ddl.split_code_comment(l)[0] for l in s.split("\n")).strip()
            if code:
                out += [code, "GO"]
    io.open(path, "w", encoding="utf-8-sig").write("\n".join(out) + "\n")


def parse(outfile, capture):
    stmts = {}
    cur = None
    for line in io.open(capture, encoding="utf-8-sig"):
        m = re.match(r"PRINT '#(\d+)'", line)
        if m:
            cur = int(m.group(1))
            stmts[cur] = []
        elif cur is not None and line.strip() != "GO":
            stmts[cur].append(line.rstrip("\n"))
    errors = []
    lines = io.open(outfile, encoding="utf-8-sig", errors="replace").read().splitlines()
    n = None
    for i, line in enumerate(lines):
        m = re.match(r"^#(\d+)$", line.strip())
        if m:
            n = int(m.group(1))
            continue
        m = ERR_RE.match(line.strip())
        if m:
            msg = lines[i + 1].strip() if i + 1 < len(lines) else ""
            errors.append((n, int(m.group(1)), msg))
    return stmts, errors


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", default=r"localhost\SQLEXPRESS")
    ap.add_argument("--db", default="PMSX_TEST")
    ap.add_argument("--dir", default=os.path.join(ROOT, "build", "sql-capture", "mssql"))
    ap.add_argument("--keep", action="store_true")
    a = ap.parse_args()
    work = os.path.join(ROOT, "build", "mssql-replay")
    os.makedirs(work, exist_ok=True)
    ddl = os.path.join(work, "ddl.sql")
    ddl_script(a.db, ddl)
    total_sql_errors = 0
    files = sorted(f for f in os.listdir(a.dir) if f.endswith(".sql"))
    try:
        for f in files:
            sqlcmd(a.server, [], query=f"IF DB_ID('{a.db}') IS NOT NULL BEGIN ALTER DATABASE {a.db} SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE {a.db}; END; CREATE DATABASE {a.db} COLLATE Korean_100_CS_AS;")
            ddl_out = os.path.join(work, f + ".ddl.out")
            sqlcmd(a.server, [], infile=ddl, outfile=ddl_out)
            ddl_err = [l for l in io.open(ddl_out, encoding="utf-8-sig", errors="replace") if ERR_RE.match(l.strip())]
            cap = os.path.join(a.dir, f)
            run = os.path.join(work, f + ".run.sql")
            io.open(run, "w", encoding="utf-8-sig").write(f"USE {a.db};\nGO\nSET NOCOUNT ON;\nGO\n" + io.open(cap, encoding="utf-8-sig").read())
            out = os.path.join(work, f + ".out")
            sqlcmd(a.server, [], infile=run, outfile=out)
            stmts, errors = parse(out, cap)
            sql_err = [e for e in errors if e[1] not in STATE_ERRORS]
            state_err = [e for e in errors if e[1] in STATE_ERRORS]
            total_sql_errors += len(sql_err) + len(ddl_err)
            print(f"== {f}: 문장 {len(stmts)}, SQL 오류 {len(sql_err)}, 상태 오류 {len(state_err)}, DDL 오류 {len(ddl_err)}")
            seen = collections.OrderedDict()
            for n, code, msg in sql_err:
                key = (code, msg[:120])
                if key not in seen:
                    seen[key] = n
            for (code, msg), n in list(seen.items())[:25]:
                head = " ".join(stmts.get(n, ["?"]))[:300]
                print(f"   [{code}] {msg}\n      #{n}: {head}")
            if state_err:
                c = collections.Counter(e[1] for e in state_err)
                print("   상태 오류 분포:", dict(c))
    finally:
        if not a.keep:
            sqlcmd(a.server, [], query=f"IF DB_ID('{a.db}') IS NOT NULL BEGIN ALTER DATABASE {a.db} SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE {a.db}; END")
            print(f"테스트 DB {a.db} 삭제")
    print("SQL 오류 합계:", total_sql_errors)
    return 1 if total_sql_errors else 0


if __name__ == "__main__":
    sys.exit(main())
