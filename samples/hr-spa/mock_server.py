"""
교육관리시스템(ED) 샘플 SPA - 캡처용 mock 런타임 (표준 라이브러리만 사용)

  - web/dist 의 빌드 결과를 서빙 (SPA 경로는 index.html 로 fallback)
  - /api/**  JSON 샘플 데이터
  - 쓰기 요청(POST/PUT/DELETE) 이 도달하면 writes.log 에 기록 (수집기 차단 검증용)

빌드: cd web && npm install && npm run build
실행: python mock_server.py [--port 8092] [--write-log writes.log]
"""
import argparse
import json
import re
import threading
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parent
DIST = ROOT / "web" / "dist"
TYPES = {".html": "text/html; charset=utf-8", ".js": "application/javascript; charset=utf-8",
         ".css": "text/css; charset=utf-8", ".svg": "image/svg+xml", ".ico": "image/x-icon"}

COURSES = [
    {"courseId": "C2026-001", "courseNm": "공공데이터 활용 실무", "startDt": "2026-03-02", "endDt": "2026-03-06", "capacity": 30, "deptCd": "D01"},
    {"courseId": "C2026-002", "courseNm": "정보보안 기본", "startDt": "2026-04-13", "endDt": "2026-04-14", "capacity": 50, "deptCd": "D02"},
    {"courseId": "C2026-003", "courseNm": "AI 업무 자동화", "startDt": "2026-05-18", "endDt": "2026-05-22", "capacity": 20, "deptCd": "D01"},
]
APPLIES = [
    {"applyId": 101, "courseNm": "공공데이터 활용 실무", "empNm": "김교육", "status": "REQ"},
    {"applyId": 102, "courseNm": "정보보안 기본", "empNm": "이수강", "status": "OK"},
]
DEPTS = [{"deptCd": "D01", "deptNm": "정보화담당관"}, {"deptCd": "D02", "deptNm": "감사담당관"}]
STATS = [{"deptNm": "정보화담당관", "courseCnt": 2, "doneCnt": 41, "rate": 82}, {"deptNm": "감사담당관", "courseCnt": 1, "doneCnt": 47, "rate": 94}]


def make_handler(write_log: Path | None, verbose: bool):
    lock = threading.Lock()

    class H(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            if verbose:
                super().log_message(fmt, *args)

        def _send(self, code: int, body: bytes, ctype: str):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _json(self, data, code=200):
            self._send(code, json.dumps(data, ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8")

        def _write(self):
            n = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(n).decode("utf-8", "replace") if n else ""
            line = f"{datetime.now().isoformat(timespec='seconds')} WRITE {self.command} {self.path} {body[:200]}"
            if verbose:
                print(line)
            if write_log is not None:
                with lock, open(write_log, "a", encoding="utf-8") as f:
                    f.write(line + "\n")
            self._json({"result": "ok"})

        def do_GET(self):
            path = urlparse(self.path).path
            if path.startswith("/api/"):
                if path == "/api/edu/courses":
                    return self._json(COURSES)
                if path == "/api/edu/courses/excel":
                    return self._send(200, b"PK-sample-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                m = re.fullmatch(r"/api/edu/courses/([\w-]+)", path)
                if m:
                    return self._json(next((c for c in COURSES if c["courseId"] == m.group(1)), {}))
                if path in ("/api/edu/applies", "/api/edu/my-applies"):
                    return self._json(APPLIES)
                if path == "/api/cmm/depts":
                    return self._json(DEPTS)
                if path == "/api/stat/edu":
                    return self._json(STATS)
                return self._json({"message": "not found"}, 404)
            f = (DIST / path.lstrip("/")).resolve()
            if path != "/" and f.is_file() and DIST.resolve() in f.parents:
                return self._send(200, f.read_bytes(), TYPES.get(f.suffix, "application/octet-stream"))
            index = DIST / "index.html"
            if not index.is_file():
                return self._send(500, "web/dist 가 없습니다. cd web && npm run build".encode("utf-8"), "text/plain; charset=utf-8")
            return self._send(200, index.read_bytes(), TYPES[".html"])

        def do_POST(self):
            return self._write()

        def do_PUT(self):
            return self._write()

        def do_DELETE(self):
            return self._write()

    return H


def serve(port: int = 8092, write_log: Path | None = None, verbose: bool = False) -> ThreadingHTTPServer:
    return ThreadingHTTPServer(("127.0.0.1", port), make_handler(write_log, verbose))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8092)
    ap.add_argument("--write-log", default=str(ROOT / "writes.log"))
    a = ap.parse_args()
    httpd = serve(a.port, Path(a.write_log), verbose=True)
    print(f"교육관리 SPA mock: http://127.0.0.1:{httpd.server_address[1]}  (쓰기 로그: {a.write_log})")
    httpd.serve_forever()


if __name__ == "__main__":
    main()
