"""`doctor` — 현장 실행 환경 점검. 결과를 표로 출력하고, 실패가 있으면 종료코드 1.

점검 항목: 설정 · 소스 경로 · 출력 폴더 쓰기 · 디스크 여유 · 한글 글꼴(와이어프레임) · 브라우저 실행 ·
          대상 URL 응답·운영계 차단 · DB 드라이버·접속(읽기 전용 질의 1건) · 데스크톱 캡처 실행 파일
네트워크·DB 점검은 짧은 타임아웃을 쓰고, 어떤 경우에도 데이터를 바꾸는 요청·질의는 보내지 않는다.
"""
from __future__ import annotations

import fnmatch
import importlib
import shutil
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Callable
from urllib.parse import urlparse

OK, WARN, FAIL, SKIP = "OK", "WARN", "FAIL", "SKIP"
WIREFRAME_TECHS = {"nexacro", "xplatform", "miplatform", "websquare", "winforms"}
WEB_TECHS = {"jsp-jquery", "vue", "react", "websquare", "aspnet-webforms", "aspnet-mvc", "nexacro"}
DB_DRIVERS = {"oracle": "oracledb", "tibero": "pyodbc", "postgresql": "psycopg", "mssql": "pyodbc",
              "mysql": "pymysql", "mariadb": "pymysql"}
PING_SQL = {"oracle": "SELECT 1 FROM DUAL", "tibero": "SELECT 1 FROM DUAL"}


@dataclass
class Check:
    status: str
    item: str
    detail: str


def _with_timeout(fn: Callable, seconds: float):
    """드라이버 접속처럼 멈출 수 있는 호출을 시간 제한으로 감싼다. (결과, 예외, 시간초과 여부)"""
    box: dict = {}

    def target():
        try:
            box["v"] = fn()
        except BaseException as e:  # noqa: BLE001
            box["e"] = e
    t = threading.Thread(target=target, daemon=True)
    t.start()
    t.join(seconds)
    if t.is_alive():
        return None, None, True
    return box.get("v"), box.get("e"), False


def check_paths(cfg) -> list[Check]:
    out: list[Check] = []
    root = cfg.source_root
    out.append(Check(OK if root and root.is_dir() else FAIL, "소스 루트", str(root)))
    for k, v in (cfg.get("source") or {}).items():
        if k in ("root", "includeSnippets", "commonScripts", "contextPath") or not isinstance(v, str):
            continue
        p = cfg.resolve(v)
        out.append(Check(OK if p and p.exists() else FAIL, f"source.{k}", f"{v} → {p}"))
    files = list(cfg.get("db.ddlFiles") or [])
    for key in ("menu.csv", "menu.sqlFile", "menu.nexacroDataset", "spa.router", "spa.reactRouter", "harness.workspace"):
        if cfg.get(key) and isinstance(cfg.get(key), str):
            files.append(cfg.get(key))
    for f in files:
        p = cfg.resolve(f)
        out.append(Check(OK if p and p.exists() else FAIL, "참조 파일", f"{f}"))
    return out


def check_output(cfg) -> list[Check]:
    out_dir = cfg.resolve(cfg.get("output", "out"))
    res: list[Check] = []
    try:
        out_dir.mkdir(parents=True, exist_ok=True)
        probe = out_dir / f".doctor-{uuid.uuid4().hex}.tmp"
        probe.write_text("ok", encoding="utf-8")
        probe.unlink()
        res.append(Check(OK, "출력 폴더 쓰기", str(out_dir)))
    except OSError as e:
        res.append(Check(FAIL, "출력 폴더 쓰기", f"{out_dir}: {e}"))
        return res
    free = shutil.disk_usage(out_dir).free
    gb = free / 1024 ** 3
    st = OK if gb >= 1 else (WARN if free >= 200 * 1024 ** 2 else FAIL)
    res.append(Check(st, "디스크 여유", f"{gb:.1f} GB (권장 1GB 이상 — 캡처 PNG·결과 패키지)"))
    return res


def check_font(cfg) -> list[Check]:
    ui = set(cfg.get("system.uiTech", []) or [])
    if not (ui & WIREFRAME_TECHS) and not cfg.get("wireframe.font"):
        return [Check(SKIP, "한글 글꼴", "와이어프레임을 쓰는 화면 기술이 아님")]
    try:
        from PIL import ImageFont  # noqa: F401
    except ImportError:
        return [Check(FAIL, "한글 글꼴", "Pillow 미설치 — 와이어프레임 PNG를 만들 수 없음")]
    from .wireframe import FONT_CANDIDATES
    cands = ([str(cfg.resolve(cfg.get("wireframe.font")))] if cfg.get("wireframe.font") else []) + list(FONT_CANDIDATES)
    found = next((c for c in cands if c and Path(c).is_file()), None)
    if found:
        return [Check(OK, "한글 글꼴", found)]
    return [Check(WARN, "한글 글꼴", "한글 TTF 를 찾지 못함 — 와이어프레임 글자가 깨질 수 있음 (wireframe.font 로 지정)")]


def check_browser(cfg, timeout: float = 30) -> list[Check]:
    ui = set(cfg.get("system.uiTech", []) or [])
    enabled = bool(cfg.get("capture.enabled", False)) and not cfg.get("capture.desktop")
    if not enabled and not (ui & WEB_TECHS):
        return [Check(SKIP, "브라우저", "웹 화면 기술·웹 캡처 설정 없음")]
    try:
        from playwright.sync_api import sync_playwright

        from .capture import browser_launch_args
    except ImportError as e:
        return [Check(FAIL if enabled else WARN, "브라우저", f"playwright 미설치: {e}")]

    def launch():
        with sync_playwright() as p:
            b = p.chromium.launch(**browser_launch_args(cfg))
            v = b.version
            b.close()
            return v
    v, err, timed_out = _with_timeout(launch, timeout)
    who = cfg.get("capture.browserPath") or f"channel={cfg.get('capture.browserChannel', 'chrome')}"
    if timed_out:
        return [Check(FAIL if enabled else WARN, "브라우저", f"{who}: {timeout:.0f}초 안에 실행되지 않음")]
    if err:
        return [Check(FAIL if enabled else WARN, "브라우저", f"{who}: {str(err).splitlines()[0][:160]}")]
    return [Check(OK, "브라우저", f"{who} 실행 가능 (버전 {v})" + ("" if enabled else " — 캡처는 꺼져 있음"))]


def check_target(cfg, timeout: float = 5) -> list[Check]:
    base = (cfg.get("capture.baseUrl") or "").rstrip("/")
    if not cfg.get("capture.enabled", False) or cfg.get("capture.desktop") or not base:
        return [Check(SKIP, "대상 URL", "웹 캡처가 꺼져 있거나 baseUrl 없음")]
    host = urlparse(base).hostname or ""
    deny = cfg.get("capture.denyHosts", []) or []
    res: list[Check] = []
    if any(fnmatch.fnmatch(host, d) for d in deny):
        return [Check(FAIL, "운영계 차단", f"{host} 가 denyHosts {deny} 와 일치 — 수집기는 실행을 거부합니다")]
    res.append(Check(OK if deny else WARN, "운영계 차단", f"denyHosts {deny}" if deny else "denyHosts 비어 있음 — 운영계 호스트를 등록하세요"))
    url = base + ((cfg.get("capture.login") or {}).get("url") or "/")
    try:
        req = urllib.request.Request(url, method="GET", headers={"User-Agent": "pmsx-collector-doctor"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            res.append(Check(OK, "대상 URL 응답", f"GET {url} → {r.status}"))
    except urllib.error.HTTPError as e:
        res.append(Check(WARN if e.code < 500 else FAIL, "대상 URL 응답", f"GET {url} → HTTP {e.code}"))
    except (urllib.error.URLError, OSError) as e:
        res.append(Check(FAIL, "대상 URL 응답", f"GET {url} → {getattr(e, 'reason', e)}"))
    return res


def check_db(cfg, timeout: float = 8) -> list[Check]:
    conn_cfg = cfg.get("db.connection")
    db_type = (cfg.get("db.type") or cfg.get("system.dbType") or "").lower()
    if not conn_cfg:
        return [Check(SKIP, "DB 접속", "db.connection 없음 (DDL 파일로 수집)")]
    mod = DB_DRIVERS.get(db_type)
    if not mod:
        return [Check(FAIL, "DB 드라이버", f"지원하지 않는 DB 종류: {db_type}")]
    try:
        importlib.import_module(mod)
    except ImportError:
        return [Check(FAIL, "DB 드라이버", f"{mod} 미설치 ({db_type})")]
    res = [Check(OK, "DB 드라이버", f"{mod} ({db_type})")]
    pw_env = conn_cfg.get("passwordEnv")
    if pw_env and pw_env not in __import__("os").environ and not conn_cfg.get("trusted"):
        res.append(Check(WARN, "DB 비밀번호", f"환경변수 {pw_env} 가 비어 있음"))

    def ping():
        from .dbmeta import _close, open_readonly
        d, conn = open_readonly(cfg)
        try:
            cur = conn.cursor()
            cur.execute(PING_SQL.get(db_type, "SELECT 1"))
            cur.fetchall()
        finally:
            _close(conn)
        return d.name
    t0 = time.time()
    v, err, timed_out = _with_timeout(ping, timeout)
    if timed_out:
        res.append(Check(FAIL, "DB 접속", f"{timeout:.0f}초 안에 응답 없음"))
    elif err:
        res.append(Check(FAIL, "DB 접속", str(err).splitlines()[0][:200]))
    else:
        res.append(Check(OK, "DB 접속", f"읽기 전용 질의 1건 성공 ({time.time() - t0:.1f}초)"))
    return res


def check_desktop(cfg) -> list[Check]:
    desk = cfg.get("capture.desktop")
    if not desk:
        return []
    res: list[Check] = []
    exe = cfg.resolve(desk.get("exe")) if desk.get("exe") else None
    res.append(Check(OK if exe and exe.is_file() else (FAIL if cfg.get("capture.enabled") else WARN),
                     "데스크톱 실행 파일", str(exe) if exe else "capture.desktop.exe 없음"))
    if sys.platform != "win32":
        res.append(Check(FAIL, "UI Automation", "Windows 에서만 동작"))
    else:
        try:
            importlib.import_module("pywinauto")
            res.append(Check(OK, "UI Automation", "pywinauto 사용 가능"))
        except ImportError:
            res.append(Check(FAIL, "UI Automation", "pywinauto 미설치"))
    return res


def check_config(cfg) -> list[Check]:
    from .pipeline import SERVER_PARSERS, SQL_PARSERS, UI_PARSERS
    res = [Check(OK, "설정 파일", f"{cfg.path} (시스템 {cfg.sys_code})")]
    for t in cfg.get("system.uiTech", []) or []:
        res.append(Check(OK if t in UI_PARSERS else FAIL, "화면 기술", t + ("" if t in UI_PARSERS else " — 지원 안 함")))
    for t in cfg.get("system.serverTech", []) or []:
        ok = t in SERVER_PARSERS or t in SQL_PARSERS
        res.append(Check(OK if ok else FAIL, "서버 기술", t + ("" if ok else " — 지원 안 함")))
    if not cfg.get("system.uiTech"):
        res.append(Check(FAIL, "화면 기술", "system.uiTech 가 비어 있음"))
    return res


def run_doctor(cfg, browser_timeout: float = 30, net_timeout: float = 5, db_timeout: float = 8,
               skip: set[str] | None = None) -> list[Check]:
    skip = skip or set()
    checks: list[Check] = []
    checks += check_config(cfg)
    checks += check_paths(cfg)
    checks += check_output(cfg)
    checks += check_font(cfg)
    if "browser" not in skip:
        checks += check_browser(cfg, browser_timeout)
    if "network" not in skip:
        checks += check_target(cfg, net_timeout)
    if "db" not in skip:
        checks += check_db(cfg, db_timeout)
    checks += check_desktop(cfg)
    return checks


def format_table(checks: list[Check]) -> str:
    icon = {OK: "✓", WARN: "!", FAIL: "✗", SKIP: "-"}
    w = max((len(c.item) for c in checks), default=4)
    lines = [f"{'상태':<6} {'항목':<{w}}  내용", "-" * (w + 40)]
    for c in checks:
        lines.append(f"{icon[c.status]} {c.status:<4} {c.item:<{w}}  {c.detail}")
    n_fail = sum(c.status == FAIL for c in checks)
    n_warn = sum(c.status == WARN for c in checks)
    lines.append("-" * (w + 40))
    lines.append(f"실패 {n_fail} · 경고 {n_warn} · 통과 {sum(c.status == OK for c in checks)}")
    return "\n".join(lines)
