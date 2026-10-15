"""Oracle·Tibero 번역 SQL 정적 검사.

실 Oracle·Tibero DB 가 없는 환경에서, H2 Oracle 호환 모드 통합 테스트가 실행한 SQL 을 Oracle 문법으로 번역한 결과
(./gradlew sqlCapture → build/sql-capture/{oracle,tibero}/*.sql)를 읽어 H2 가 너그럽게 넘기는 Oracle 비호환 구문을 찾는다.

검사 항목 (오류)
  - 남은 PostgreSQL/SQL Server 구문: LIMIT, ILIKE, '::', TRUE/FALSE, LEN(, TOP n, SUBSTRING(
  - FROM 없는 SELECT (FROM DUAL 필요, 하위 질의 포함)
  - 테이블 별칭 앞 AS (FROM T AS X)
  - 조건 자리의 숫자: CASE WHEN 0/1 THEN, WHERE 1 AND …
  - 불리언 컬럼 단독 조건 (WHERE … AND EDITED …)
  - Tibero(ROWNUM 감싸기): 안쪽 SELECT 목록의 중복 열 이름(ORA-00918)
  - Oracle 11g·12.1 식별자 30자 초과 별칭
검사 항목 (경고)
  - '' 와의 비교 (Oracle 은 '' = NULL 이라 항상 거짓)
사용 (pmsx-server 에서): python tools/db/oracle_sql_check.py
"""
import collections
import io
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
CAP = os.path.join(ROOT, "build", "sql-capture")
COMMON = os.path.join(ROOT, "src", "main", "resources", "db", "migration", "common")

TOKEN = re.compile(r"""(?P<ws>\s+)|(?P<str>N?'(?:[^']|'')*')|(?P<qid>"[^"]*")|(?P<num>\d+(?:\.\d+)?)|(?P<word>[A-Za-z_][A-Za-z0-9_$#]*)|(?P<sym>\|\||<>|<=|>=|!=|::|.)""", re.S)


def tokens(sql):
    out = []
    for m in TOKEN.finditer(sql):
        kind = m.lastgroup
        if kind == "ws":
            continue
        out.append((kind, m.group()))
    return out


def bool_columns():
    cols = set()
    for f in os.listdir(COMMON):
        for l in io.open(os.path.join(COMMON, f), encoding="utf-8"):
            m = re.match(r"\s+(\w+)\s+BOOLEAN", l) or re.search(r"ADD COLUMN (\w+) BOOLEAN", l)
            if m:
                cols.add(m.group(1).upper())
    return cols


def statements(path):
    text = io.open(path, encoding="utf-8-sig").read()
    for m in re.finditer(r"PRINT '#(\d+)'\nGO\n(.*?)\nGO\n", text, re.S):
        yield int(m.group(1)), m.group(2)


def select_without_from(toks):
    """SELECT 마다 같은 괄호 깊이에서 FROM 이 나오는지 확인"""
    bad = 0
    for i, (k, t) in enumerate(toks):
        if k == "word" and t.upper() == "SELECT":
            depth = 0
            found = False
            for k2, t2 in toks[i + 1:]:
                if t2 == "(":
                    depth += 1
                elif t2 == ")":
                    if depth == 0:
                        break
                    depth -= 1
                elif depth == 0 and k2 == "word" and t2.upper() == "FROM":
                    found = True
                    break
                elif depth == 0 and k2 == "word" and t2.upper() in ("UNION", "INTERSECT", "MINUS", "EXCEPT"):
                    break
            if not found:
                bad += 1
    return bad


def select_list_names(toks, start):
    """toks[start] 가 SELECT 일 때 최상위 SELECT 목록의 출력 이름들"""
    names, cur, depth = [], [], 0
    for k, t in toks[start + 1:]:
        if t == "(":
            depth += 1
        elif t == ")":
            depth -= 1
        if depth == 0 and k == "word" and t.upper() == "FROM":
            break
        if depth == 0 and t == ",":
            names.append(cur)
            cur = []
            continue
        cur.append((k, t))
    names.append(cur)
    out = []
    for item in names:
        if not item:
            continue
        last = item[-1]
        if last[0] in ("word", "qid") and not (len(item) >= 2 and item[-2][1] == "."):
            out.append(last[1].upper().strip('"'))
        elif len(item) >= 3 and item[-2][1] == "." and last[0] == "word":
            out.append(last[1].upper())
        elif last[1] == "*":
            out.append("*")
    return out


def check(vendor, path, boolcols):
    errors, warns = [], []
    for n, sql in statements(path):
        toks = tokens(sql)
        words = [t.upper() for k, t in toks if k == "word"]
        head = words[0] if words else ""

        def err(msg):
            errors.append((msg, n, sql))

        for w in ("LIMIT", "ILIKE", "TOP", "LEN", "TRUE", "FALSE", "SUBSTRING"):
            if w in words:
                err(f"남은 비 Oracle 구문: {w}")
        if any(t == "::" for k, t in toks):
            err("남은 PostgreSQL 캐스트 ::")
        if vendor == "tibero" and ("FETCH" in words or "OFFSET" in words):
            err("Tibero(ROWNUM 모드)에 OFFSET/FETCH")
        if head in ("SELECT", "WITH", "INSERT", "UPDATE", "DELETE") and select_without_from(toks):
            err("FROM 없는 SELECT (FROM DUAL 필요)")
        for i in range(len(toks) - 3):
            a, b, c = toks[i][1].upper(), toks[i + 1], toks[i + 2][1].upper()
            if a in ("FROM", "JOIN") and b[0] == "word" and c == "AS":
                err(f"테이블 별칭 앞 AS: {toks[i + 1][1]} AS")
            if a in ("WHEN", "WHERE", "AND", "OR", "NOT") and b[0] == "num" and c in ("THEN", "AND", "OR", ")"):
                err(f"조건 자리의 숫자: {a} {b[1]} {c}")
            if a in ("WHERE", "AND", "OR", "NOT", "WHEN", "(") and b[0] == "word":
                name = b[1].upper()
                nxt = c
                if name in boolcols and nxt not in ("=", "<>", "!=", "IS", "IN", ",", "."):
                    err(f"불리언 컬럼 단독 조건: {a} {name} {nxt}")
                if c == "." and i + 3 < len(toks):
                    name2 = toks[i + 3][1].upper()
                    nxt2 = toks[i + 4][1].upper() if i + 4 < len(toks) else ""
                    if name2 in boolcols and nxt2 not in ("=", "<>", "!=", "IS", "IN", ","):
                        err(f"불리언 컬럼 단독 조건: {a} {b[1]}.{name2} {nxt2}")
        for i in range(len(toks) - 1):
            if toks[i][1].upper() == "AS" and toks[i + 1][0] in ("word", "qid") and len(toks[i + 1][1].strip('"')) > 30:
                err(f"30자 초과 별칭: {toks[i + 1][1]}")
            if toks[i][1] in ("=", "<>", "!=") and toks[i + 1][1] in ("''", "N''"):
                warns.append(("'' 와 비교", n, sql))
        if vendor == "tibero" and "Q__" in words:
            # SELECT * FROM (SELECT q__.*, ROWNUM rn__ FROM (<inner>) q__ ...)
            idx = [i for i, (k, t) in enumerate(toks) if k == "word" and t.upper() == "SELECT"]
            if len(idx) >= 3:
                names = [x for x in select_list_names(toks, idx[2]) if x != "*"]
                dup = [x for x, c in collections.Counter(names).items() if c > 1]
                if dup:
                    err(f"ROWNUM 감싸기 안쪽 SELECT 중복 열 이름(ORA-00918): {dup}")
    return errors, warns


def main():
    boolcols = bool_columns()
    total = 0
    for vendor in ("oracle", "tibero"):
        d = os.path.join(CAP, vendor)
        if not os.path.isdir(d):
            print(f"{d} 없음 — 먼저 ./gradlew sqlCapture")
            continue
        for f in sorted(os.listdir(d)):
            errors, warns = check(vendor, os.path.join(d, f), boolcols)
            n = sum(1 for _ in statements(os.path.join(d, f)))
            print(f"== {vendor}/{f}: 문장 {n}, 오류 {len(errors)}, 경고 {len(warns)}")
            seen = collections.OrderedDict()
            for msg, num, sql in errors + [("[경고] " + m, num, s) for m, num, s in warns]:
                seen.setdefault(msg, (num, sql))
            for msg, (num, sql) in list(seen.items())[:20]:
                print(f"   {msg}\n      #{num}: {' '.join(sql.split())[:260]}")
            total += len(errors)
    print("오류 합계:", total)
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
