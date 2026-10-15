"""
인사시스템(HR) 샘플 레거시 - 캡처용 mock 런타임

실제 Spring/JSP 서버 대신 표준 라이브러리만으로 화면과 *.do 엔드포인트를 흉내 낸다.
  - /login.do          로그인 화면 (아이디/비밀번호 아무 값이나 허용)
  - /main.do           메뉴 화면
  - /<...>/HRxxxx.do   JSP 를 지시자·태그 제거 후 HTML 로 서빙
  - *.do (JSON)        샘플 데이터 응답
  - 쓰기 엔드포인트     호출되면 writes.log 에 한 줄씩 기록 (수집기 차단 검증용)

실행: python mock_server.py [--port 8091] [--write-log writes.log]
"""
import argparse
import csv
import html
import json
import re
import secrets
import sys
import threading
from datetime import datetime
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parent
WEBAPP = ROOT / "src" / "main" / "webapp"
JSP_ROOT = WEBAPP / "WEB-INF" / "jsp"

STATIC_PREFIXES = ("/css/", "/js/", "/vendor/")
CONTENT_TYPES = {".css": "text/css; charset=utf-8", ".js": "application/javascript; charset=utf-8"}

WRITE_PATHS = {
    "/hr/emp/save.do", "/hr/emp/delete.do",
    "/hr/appoint/save.do", "/hr/appoint/confirm.do",
    "/hr/dept/save.do", "/hr/dept/sort.do",
    "/hr/edu/save.do",
}

DEPTS = [
    {"deptCd": "D100", "deptNm": "기획조정실", "upDeptCd": None, "upDeptNm": "", "sortOrd": 1, "useYn": "Y"},
    {"deptCd": "D110", "deptNm": "인사팀", "upDeptCd": "D100", "upDeptNm": "기획조정실", "sortOrd": 2, "useYn": "Y"},
    {"deptCd": "D120", "deptNm": "재무팀", "upDeptCd": "D100", "upDeptNm": "기획조정실", "sortOrd": 3, "useYn": "Y"},
    {"deptCd": "D200", "deptNm": "정보화담당관", "upDeptCd": None, "upDeptNm": "", "sortOrd": 4, "useYn": "Y"},
    {"deptCd": "D210", "deptNm": "정보화팀", "upDeptCd": "D200", "upDeptNm": "정보화담당관", "sortOrd": 5, "useYn": "Y"},
    {"deptCd": "D300", "deptNm": "총무팀", "upDeptCd": None, "upDeptNm": "", "sortOrd": 6, "useYn": "N"},
]
DEPT_NM = {d["deptCd"]: d["deptNm"] for d in DEPTS}
CODES = {
    "POS": [{"cd": "P1", "cdNm": "과장"}, {"cd": "P2", "cdNm": "대리"}, {"cd": "P3", "cdNm": "주임"}, {"cd": "P4", "cdNm": "사원"}],
    "APPT": [{"cd": "A1", "cdNm": "전보"}, {"cd": "A2", "cdNm": "승진"}, {"cd": "A3", "cdNm": "파견"}],
    "EDU": [{"cd": "E1", "cdNm": "직무"}, {"cd": "E2", "cdNm": "법정의무"}, {"cd": "E3", "cdNm": "어학"}],
}
EDUS = [
    {"eduSeq": "1", "empNo": "20190012", "empNm": "홍길동", "eduTypeCd": "E1", "eduTypeNm": "직무", "eduNm": "공공데이터 분석", "eduYear": "2026", "eduHours": 16, "instNm": "인재개발원"},
    {"eduSeq": "2", "empNo": "20210033", "empNm": "이철수", "eduTypeCd": "E2", "eduTypeNm": "법정의무", "eduNm": "개인정보보호", "eduYear": "2026", "eduHours": 4, "instNm": "사이버교육"},
    {"eduSeq": "3", "empNo": "20240121", "empNm": "정하늘", "eduTypeCd": "E3", "eduTypeNm": "어학", "eduNm": "실무 영어", "eduYear": "2025", "eduHours": 30, "instNm": "외부위탁"},
]


def _edu_filter(body):
    t, y, nm = (body.get("eduTypeCd") or ""), (body.get("eduYear") or ""), (body.get("eduNm") or "")
    return [e for e in EDUS if (not t or e["eduTypeCd"] == t) and (not y or e["eduYear"] == y) and (not nm or nm in e["eduNm"])]
POS_NM = {c["cd"]: c["cdNm"] for c in CODES["POS"]}
EMPS = [
    {"empNo": "20190012", "empNm": "홍길동", "deptCd": "D110", "posCd": "P1", "hireDt": "2019-03-02", "email": "hong@example.go.kr"},
    {"empNo": "20200105", "empNm": "김영희", "deptCd": "D120", "posCd": "P2", "hireDt": "2020-07-01", "email": "kim@example.go.kr"},
    {"empNo": "20210033", "empNm": "이철수", "deptCd": "D210", "posCd": "P3", "hireDt": "2021-01-04", "email": "lee@example.go.kr"},
    {"empNo": "20220218", "empNm": "박민수", "deptCd": "D110", "posCd": "P4", "hireDt": "2022-09-15", "email": "park@example.go.kr"},
    {"empNo": "20230007", "empNm": "최지은", "deptCd": "D300", "posCd": "P4", "hireDt": "2023-02-01", "email": "choi@example.go.kr"},
    {"empNo": "20240121", "empNm": "정하늘", "deptCd": "D210", "posCd": "P4", "hireDt": "2024-07-08", "email": "jung@example.go.kr"},
]
for _e in EMPS:
    _e["deptNm"] = DEPT_NM[_e["deptCd"]]
    _e["posNm"] = POS_NM[_e["posCd"]]
APPTS = [
    {"apptNo": "AP00000012", "empNo": "20220218", "empNm": "박민수", "apptTypeNm": "전보", "fromDeptNm": "총무팀", "toDeptNm": "인사팀", "apptDt": "2025-01-01", "status": "C"},
    {"apptNo": "AP00000015", "empNo": "20210033", "empNm": "이철수", "apptTypeNm": "승진", "fromDeptNm": "정보화팀", "toDeptNm": "정보화팀", "apptDt": "2026-01-01", "status": "R"},
]

SESSIONS: set[str] = set()
_write_lock = threading.Lock()


def load_menus():
    with open(ROOT / "db" / "menu.csv", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def find_jsp(stem: str) -> Path | None:
    for p in JSP_ROOT.rglob(f"{stem}.jsp"):
        return p
    return None


def render_jsp(path: Path, query: dict) -> str:
    src = path.read_text(encoding="utf-8")
    src = re.sub(r"<%--.*?--%>", "", src, flags=re.S)
    src = re.sub(r"<%@.*?%>\s*", "", src, flags=re.S)
    src = re.sub(r"<c:url\s+value=['\"]([^'\"]+)['\"]\s*/>", r"\1", src)
    src = re.sub(r"</?c:[^>]*>", "", src)
    src = re.sub(r"\$\{(\w+)\}", lambda m: html.escape((query.get(m.group(1)) or [""])[0]), src)
    return src.lstrip()


LOGIN_HTML = """<!DOCTYPE html>
<html lang="ko"><head><meta charset="UTF-8"><title>인사시스템 로그인</title>
<link rel="stylesheet" href="/css/cmm/legacy.css"></head>
<body class="popup"><div class="title">■ 인사시스템 로그인</div>
<form id="frmLogin" method="post" action="/loginProc.do" class="search-box" style="width:320px">
  <p><label for="userId">아이디</label><input type="text" id="userId" name="userId"></p>
  <p><label for="userPw">비밀번호</label><input type="password" id="userPw" name="userPw"></p>
  <p><button type="submit" id="btnLogin">로그인</button></p>
</form></body></html>"""


def main_html() -> str:
    items = []
    for m in load_menus():
        if m["MENU_URL"]:
            items.append(f'<li><a id="menu_{m["MENU_ID"]}" href="{m["MENU_URL"]}">{html.escape(m["MENU_NM"])}</a></li>')
        else:
            items.append(f'<li class="grp">{html.escape(m["MENU_NM"])}</li>')
    return ("<!DOCTYPE html><html lang=\"ko\"><head><meta charset=\"UTF-8\"><title>인사시스템</title>"
            "<link rel=\"stylesheet\" href=\"/css/cmm/legacy.css\"></head><body>"
            "<div class=\"title\">■ 인사시스템</div><ul id=\"menuList\">" + "".join(items) + "</ul></body></html>")


class Handler(BaseHTTPRequestHandler):
    server_version = "HRLegacyMock/1.0"
    write_log: Path = ROOT / "writes.log"

    def log_message(self, fmt, *args):  # 조용히
        if getattr(self.server, "verbose", False):
            sys.stderr.write("[mock] " + fmt % args + "\n")

    # ---------- helpers ----------
    def _send(self, code: int, body: bytes, ctype: str, headers: dict | None = None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, obj, code=200):
        self._send(code, json.dumps(obj, ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8")

    def _html(self, text: str, code=200, headers=None):
        self._send(code, text.encode("utf-8"), "text/html; charset=utf-8", headers)

    def _redirect(self, location: str, headers=None):
        h = {"Location": location}
        h.update(headers or {})
        self._send(302, b"", "text/plain", h)

    def _authed(self) -> bool:
        c = SimpleCookie(self.headers.get("Cookie", ""))
        return "SID" in c and c["SID"].value in SESSIONS

    def _body(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        ctype = self.headers.get("Content-Type", "")
        if "json" in ctype:
            try:
                data = json.loads(raw or b"{}")
                return data if isinstance(data, dict) else {"rows": data}
            except ValueError:
                return {}
        return {k: v[0] for k, v in parse_qs(raw.decode("utf-8")).items()}

    def _record_write(self, path: str, body: dict):
        line = json.dumps({"at": datetime.now().isoformat(timespec="seconds"), "method": self.command,
                           "path": path, "keys": sorted(body.keys())}, ensure_ascii=False)
        with _write_lock, open(self.write_log, "a", encoding="utf-8") as f:
            f.write(line + "\n")
        sys.stderr.write(f"[mock] WRITE {self.command} {path}\n")

    # ---------- routing ----------
    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        self._route("GET")

    def do_POST(self):
        self._route("POST")

    def _route(self, method: str):
        url = urlparse(self.path)
        path, query = url.path, parse_qs(url.query)

        if path.startswith(STATIC_PREFIXES):
            f = (WEBAPP / path.lstrip("/")).resolve()
            if WEBAPP.resolve() in f.parents and f.is_file():
                return self._send(200, f.read_bytes(), CONTENT_TYPES.get(f.suffix, "application/octet-stream"))
            return self._send(404, b"not found", "text/plain")

        if path in ("/", ""):
            return self._redirect("/main.do" if self._authed() else "/login.do")
        if path == "/login.do":
            return self._html(LOGIN_HTML)
        if path == "/loginProc.do" and method == "POST":
            body = self._body()
            if not body.get("userId") or not body.get("userPw"):
                return self._redirect("/login.do?error=1")
            sid = secrets.token_hex(16)
            SESSIONS.add(sid)
            return self._redirect("/main.do", {"Set-Cookie": f"SID={sid}; Path=/; HttpOnly"})

        if not self._authed():
            if path.endswith(".do") and method == "POST":
                return self._json({"code": "AUTH", "message": "로그인이 필요합니다."}, 401)
            return self._redirect("/login.do")

        body = self._body() if method == "POST" else {k: v[0] for k, v in query.items()}

        if path in WRITE_PATHS:
            if method != "POST":
                return self._json({"code": "METHOD"}, 405)
            self._record_write(path, body)
            return self._json({"result": "OK"})

        if path == "/main.do":
            return self._html(main_html())

        m = re.match(r"^/.+/(HR\d{4}(?:P\d{2})?)\.do$", path)
        if m and method == "GET":
            jsp = find_jsp(m.group(1))
            if not jsp:
                return self._html("<h1>404 - 화면을 찾을 수 없습니다.</h1>", 404)
            return self._html(render_jsp(jsp, query))

        api = API.get((method, path))
        if api:
            return api(self, body)
        return self._json({"code": "NOT_FOUND", "message": path}, 404)


def _emp_filter(body):
    nm = (body.get("empNm") or "").strip()
    dept = (body.get("deptCd") or "").strip()
    rows = [e for e in EMPS if (not nm or nm in e["empNm"] or nm == e["empNo"]) and (not dept or dept == e["deptCd"])]
    return rows


def _excel(h: Handler, body):
    lines = ["사원번호,사원명,부서,직위,입사일"] + [f'{e["empNo"]},{e["empNm"]},{e["deptNm"]},{e["posNm"]},{e["hireDt"]}' for e in _emp_filter(body)]
    data = ("﻿" + "\n".join(lines)).encode("utf-8")
    h._send(200, data, "text/csv; charset=utf-8", {"Content-Disposition": "attachment; filename=emp_list.csv"})


API = {
    ("POST", "/hr/emp/list.do"): lambda h, b: h._json({"list": _emp_filter(b)}),
    ("POST", "/hr/emp/popList.do"): lambda h, b: h._json({"list": _emp_filter(b)}),
    ("POST", "/hr/emp/detail.do"): lambda h, b: h._json({"emp": next((e for e in EMPS if e["empNo"] == b.get("empNo")), {})}),
    ("GET", "/hr/emp/excel.do"): _excel,
    ("POST", "/hr/appoint/list.do"): lambda h, b: h._json({"list": APPTS}),
    ("POST", "/hr/dept/list.do"): lambda h, b: h._json({"list": [d for d in DEPTS if not b.get("useYn") or d["useYn"] == b.get("useYn")]}),
    ("GET", "/cmm/code/dept.do"): lambda h, b: h._json({"list": [{"cd": d["deptCd"], "cdNm": d["deptNm"]} for d in DEPTS if not b.get("useYn") or d["useYn"] == b.get("useYn")]}),
    ("GET", "/cmm/code/list.do"): lambda h, b: h._json({"list": CODES.get(b.get("grpCd", ""), [])}),
    ("POST", "/hr/edu/list.do"): lambda h, b: h._json({"list": _edu_filter(b)}),
    ("POST", "/hr/edu/summary.do"): lambda h, b: h._json({"empCnt": len({e["empNo"] for e in _edu_filter(b)}),
                                                          "totalHours": sum(e["eduHours"] for e in _edu_filter(b))}),
    ("GET", "/hr/edu/excel.do"): lambda h, b: h._send(200, ("﻿사원번호,교육명\n" + "\n".join(f'{e["empNo"]},{e["eduNm"]}' for e in _edu_filter(b))).encode("utf-8"),
                                                       "text/csv; charset=utf-8", {"Content-Disposition": "attachment; filename=edu_list.csv"}),
}


def serve(port: int = 8091, write_log: Path | None = None, verbose: bool = False) -> ThreadingHTTPServer:
    if write_log:
        Handler.write_log = Path(write_log)
    httpd = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    httpd.verbose = verbose
    return httpd


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="HR 샘플 레거시 mock 서버")
    ap.add_argument("--port", type=int, default=8091)
    ap.add_argument("--write-log", default=str(ROOT / "writes.log"))
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args()
    srv = serve(a.port, Path(a.write_log), a.verbose)
    print(f"HR legacy mock: http://127.0.0.1:{a.port}/login.do  (write log: {a.write_log})", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
