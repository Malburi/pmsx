"""정적 렌더링 캡처 (capture.mode: static-render) — 테스트 서버 없이 화면 그림·요소 좌표를 얻는다.

1) 화면 소스(JSP)를 정적 HTML 로 변환 (render.jsp_static)
2) 로컬 정적 서버(127.0.0.1, 읽기 전용)가 변환 HTML 과 원본 정적 리소스(CSS·이미지·JS)를 제공
3) Playwright 로 열어 스크린샷 + data-pmsx 표식 요소의 좌표 → 정적 요소에 box 부여 (captures[].state = "rendered")

안전: 외부 호스트 요청·XHR/fetch·폼 전송·다른 문서로의 이동은 전부 차단(서버 없음). alert/confirm 자동 닫기,
window.open 무시. 원본 소스 폴더에는 아무것도 쓰지 않는다(변환 HTML 은 메모리에서만 제공).
"""
from __future__ import annotations

import asyncio
import hashlib
import mimetypes
import re
import threading
import time
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable
from urllib.parse import parse_qs, unquote, urlparse

from ..capture_types import CaptureResult, DynElement, ScreenCapture
from ..parsers import Context

MIME = {".js": "text/javascript", ".css": "text/css", ".html": "text/html", ".htm": "text/html",
        ".gif": "image/gif", ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".svg": "image/svg+xml",
        ".ico": "image/x-icon", ".woff": "font/woff", ".woff2": "font/woff2", ".ttf": "font/ttf", ".eot": "application/vnd.ms-fontobject",
        ".json": "application/json", ".swf": "application/x-shockwave-flash", ".bmp": "image/bmp", ".webp": "image/webp"}
TEXT_EXT = {".js", ".css"}

INIT_JS = """(() => {
  const noop = () => null;
  try { window.alert = noop; window.confirm = () => false; window.prompt = () => null; window.print = noop; } catch (e) {}
  try { window.open = () => null; window.showModalDialog = noop; window.showModelessDialog = noop; } catch (e) {}
  try { HTMLFormElement.prototype.submit = noop; } catch (e) {}
})();"""

COLLECT_JS = """() => {
  const out = [];
  const seen = new Set();
  document.querySelectorAll('[data-pmsx]').forEach(el => {
    const n = parseInt(el.getAttribute('data-pmsx'), 10);
    if (seen.has(n)) return;
    const r = el.getBoundingClientRect();
    const st = getComputedStyle(el);
    if (r.width < 1 || r.height < 1 || st.visibility === 'hidden' || st.display === 'none') return;
    seen.add(n);
    out.push({n, tag: el.tagName.toLowerCase(), type: (el.getAttribute('type') || '').toLowerCase(),
              x: r.left + window.scrollX, y: r.top + window.scrollY, w: r.width, h: r.height});
  });
  const body = document.body;
  const H = Math.max(document.documentElement.scrollHeight || 0, body ? body.scrollHeight : 0);
  const W = Math.max(document.documentElement.scrollWidth || 0, body ? body.scrollWidth : 0);
  const text = body ? (body.innerText || '').replace(/\\s+/g, '') : '';
  const visual = document.querySelectorAll('img, input, select, textarea, table, button').length;
  const frameset = !!document.querySelector('frameset');
  const pick = s => { const e = document.querySelector(s); return e ? (e.innerText || '').replace(/\\s+/g, ' ').trim() : ''; };
  const title = (document.title || '').trim() || pick('h1') || pick('h2') || pick('caption');
  return {els: out, H, W, textLen: text.length, visual, frameset, title};
}"""


class _Site:
    """변환 HTML(메모리) + 정적 리소스(원본 폴더, 읽기 전용) 서버."""

    def __init__(self, roots: list[tuple[str, Path]], fallbacks: list[Path], context_root: str):
        self.roots = sorted(((p, f.resolve()) for p, f in roots), key=lambda x: -len(x[0]))
        self.fallbacks = [f.resolve() for f in fallbacks if f and f.is_dir()]
        self.context_root = context_root
        self.overlay: dict[str, bytes] = {}
        self.lock = threading.Lock()
        site = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):  # noqa: N802
                site.serve(self)

            def do_HEAD(self):  # noqa: N802
                site.serve(self, head=True)

            def do_POST(self):  # noqa: N802
                self.send_response(405)
                self.end_headers()

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.httpd.daemon_threads = True
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)

    def start(self) -> "_Site":
        self.thread.start()
        return self

    def stop(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()

    def put(self, token: str, html: str) -> None:
        with self.lock:
            self.overlay[token] = html.encode("utf-8")

    def drop(self, token: str) -> None:
        with self.lock:
            self.overlay.pop(token, None)

    def _file(self, path: str) -> Path | None:
        if self.context_root and path.startswith(self.context_root + "/"):
            path = path[len(self.context_root):]
        cands: list[tuple[Path, Path]] = []
        for prefix, folder in self.roots:
            if path.startswith(prefix):
                cands.append((folder, folder / path[len(prefix):].lstrip("/")))
        for folder in self.fallbacks:
            cands.append((folder, folder / path.lstrip("/")))
        for root, f in cands:
            try:
                r = f.resolve()
            except OSError:
                continue
            if r.is_file() and (r == root or root in r.parents):
                if "WEB-INF" in r.parts or "META-INF" in r.parts:
                    continue                      # 서버 내부 파일은 제공하지 않음
                return r
        return None

    def serve(self, h: BaseHTTPRequestHandler, head: bool = False) -> None:
        u = urlparse(h.path)
        q = parse_qs(u.query)
        body, ctype = None, "application/octet-stream"
        tok = (q.get("__pmsx") or [None])[0]
        if tok:
            with self.lock:
                body = self.overlay.get(tok)
            ctype = "text/html; charset=utf-8"
        else:
            f = self._file(unquote(u.path))
            if f is not None:
                ext = f.suffix.lower()
                ctype = MIME.get(ext) or mimetypes.guess_type(f.name)[0] or "application/octet-stream"
                try:
                    body = f.read_bytes()
                except OSError:
                    body = None
                if body is not None and ext in TEXT_EXT:
                    # 레거시 리소스는 EUC-KR 이 많다 → UTF-8 로 바꿔 내보낸다 (깨진 한글 방지)
                    from ..textio import decode_auto
                    body = decode_auto(body).encode("utf-8")
                    ctype += "; charset=utf-8"
        if body is None:
            h.send_response(404)
            h.send_header("Content-Length", "0")
            h.end_headers()
            return
        h.send_response(200)
        h.send_header("Content-Type", ctype)
        h.send_header("Content-Length", str(len(body)))
        h.send_header("Cache-Control", "max-age=3600")
        h.end_headers()
        if not head:
            try:
                h.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass


@dataclass
class _Target:
    skey: str
    jsp: Path
    url_path: str
    file_stem: str


def render_targets(ctx: Context, only: set[str] | None = None, skip: set[str] | None = None) -> list[_Target]:
    cfg, m = ctx.config, ctx.model
    root = cfg.source_root
    sel = cfg.get("render.screens")            # 일부 화면만 (미리보기·시험용): 화면 ID 목록
    if sel:
        from ..keys import scr_key
        want = {scr_key(cfg.sys_code, str(x)) for x in sel}
        only = want if only is None else (only & want)
    out: list[_Target] = []
    used: set[str] = set()
    for skey, s in sorted(m.screens.items()):
        src = s.get("sourceFile") or ""
        if not src.lower().endswith((".jsp", ".jspf", ".html", ".htm")):
            continue
        if only is not None and skey not in only or skey in (skip or ()):
            continue
        jsp = (root / src) if not Path(src).is_absolute() else Path(src)
        if not jsp.is_file():
            continue
        url = urlparse(s.get("url") or "").path or "/" + src
        sid = s["screenId"]
        stem = re.sub(r"[^\w.-]", "_", sid)[:80]
        if stem.lower() in used:
            stem = f"{stem}-{hashlib.sha1(skey.encode()).hexdigest()[:6]}"
        used.add(stem.lower())
        out.append(_Target(skey, jsp, url if url.startswith("/") else "/" + url, stem))
    max_n = cfg.get("render.maxScreens")
    if max_n:
        out = out[: int(max_n)]
    return out


def render_dep_hashes(ctx: Context, hashes: dict[str, str], files: dict[str, str]) -> dict[str, str]:
    """증분 렌더용 화면 해시: 정적 분석 해시 + include 파일 내용 (공통 머리글·버튼 영역이 바뀌면 다시 그림)."""
    from .jsp_static import JspStaticRenderer
    r = JspStaticRenderer(ctx)
    cfg = ctx.config
    out = dict(hashes)
    for t in render_targets(ctx):
        try:
            deps = r.screen_deps(t.jsp)
        except OSError:
            continue
        sig = "|".join(f"{cfg.rel(d)}:{files.get(cfg.rel(d), '')}" for d in deps)
        out[t.skey] = hashlib.sha1(f"{hashes.get(t.skey, '')}|{sig}".encode("utf-8")).hexdigest()
    return out


def run_static_render(ctx: Context, capture_dir: Path, log: Callable[[str], None] = print,
                      skip_screens: set[str] | None = None) -> CaptureResult:
    from .jsp_static import JspStaticRenderer
    cfg = ctx.config
    t0 = time.time()
    result = CaptureResult()
    targets = render_targets(ctx, skip=skip_screens)
    if not targets:
        return result
    renderer = JspStaticRenderer(ctx)
    roots = [(str(p), cfg.resolve(f)) for p, f in ((cfg.get("render.scriptRoots") or cfg.get("source.scriptRoots") or {}).items())]
    fallbacks = [cfg.resolve(x) for x in (cfg.get("render.staticRoots") or [])] or [cfg.source_dir("webapp"), cfg.source_root]
    site = _Site([(p, f) for p, f in roots if f and f.is_dir()], [f for f in fallbacks if f], renderer.context_root).start()
    capture_dir.mkdir(parents=True, exist_ok=True)
    log(f"  · 정적 렌더링 캡처: {len(targets)}화면 (로컬 서버 127.0.0.1:{site.port}, 원본 읽기 전용)")
    try:
        asyncio.run(_render_all(ctx, renderer, site, targets, capture_dir, result, log))
    finally:
        site.stop()
    ok = sum(1 for s in result.screens if s.file)
    ctx.model.capture_stats.update({"renderScreens": len(targets), "renderOk": ok,
                                    "renderSec": round(time.time() - t0, 1)})
    log(f"  · 정적 렌더링 완료: {ok}/{len(targets)}화면, {time.time() - t0:.0f}초")
    return result


async def _render_all(ctx, renderer, site: _Site, targets: list[_Target], capture_dir: Path,
                      result: CaptureResult, log) -> None:
    from playwright.async_api import async_playwright

    from ..capture import browser_launch_args
    cfg = ctx.config
    width = int(cfg.get("render.width", 1280))
    max_h = int(cfg.get("render.maxHeight", 4000))
    timeout = int(float(cfg.get("render.timeoutSec", 10)) * 1000)
    parallel = max(1, min(int(cfg.get("render.parallel", 5)), 12))
    fmt = (cfg.get("render.imageFormat") or "jpeg").lower()
    quality = int(cfg.get("render.jpegQuality", 72))
    settle = int(cfg.get("render.settleMs", 400))
    origin = f"http://127.0.0.1:{site.port}"
    done = 0
    lock = asyncio.Lock()

    async with async_playwright() as p:
        browser = await p.chromium.launch(**browser_launch_args(cfg))
        context = await browser.new_context(viewport={"width": width, "height": 900}, device_scale_factor=1,
                                            accept_downloads=False, java_script_enabled=True,
                                            bypass_csp=True, service_workers="block")
        await context.add_init_script(INIT_JS)

        async def on_route(route):
            req = route.request
            url = req.url
            if not url.startswith(origin + "/"):
                return await route.abort()                         # 외부 호스트 차단
            if req.method != "GET" or req.resource_type in ("xhr", "fetch", "websocket", "eventsource"):
                return await route.abort()                         # 서버 없음 → 데이터 요청 차단
            if req.is_navigation_request() and "__pmsx=" not in url:
                return await route.abort()                         # 다른 화면으로 이동·폼 전송·프레임 로드 차단
            await route.continue_()
        await context.route("**/*", on_route)

        sem = asyncio.Semaphore(parallel)

        async def one(t: _Target):
            nonlocal done
            async with sem:
                sc = await _render_one(ctx, renderer, site, context, t, capture_dir, width, max_h, timeout, fmt,
                                       quality, settle, origin)
                result.screens.append(sc)
                async with lock:
                    done += 1
                    if done % 100 == 0 or done == len(targets):
                        log(f"    - {done}/{len(targets)}")

        await asyncio.gather(*(one(t) for t in targets))
        await context.close()
        await browser.close()


async def _render_one(ctx, renderer, site, context, t: _Target, capture_dir: Path, width: int, max_h: int,
                      timeout: int, fmt: str, quality: int, settle: int, origin: str) -> ScreenCapture:
    sc = ScreenCapture(screen_key=t.skey, url=t.url_path, state="rendered")
    try:
        rs = renderer.render_screen(t.skey, t.jsp)
    except Exception as e:  # noqa: BLE001 - 변환 실패는 화면 단위로 기록하고 계속
        sc.error = f"정적 렌더링 변환 실패: {str(e).splitlines()[0][:160]}"
        return sc
    if rs.frameset:
        sc.error = "프레임셋 화면 — 본문 프레임 화면들을 각각 렌더링함 (이 화면은 건너뜀)"
        return sc
    token = hashlib.sha1(t.skey.encode("utf-8")).hexdigest()[:16]
    site.put(token, rs.html.lstrip())
    page = await context.new_page()
    page.on("dialog", lambda d: asyncio.ensure_future(d.dismiss()))
    page.on("popup", lambda pp: asyncio.ensure_future(pp.close()))
    js_errors: list[str] = []
    page.on("pageerror", lambda e: js_errors.append(str(e)[:120]))
    try:
        try:
            await page.goto(f"{origin}{t.url_path}?__pmsx={token}", wait_until="load", timeout=timeout)
        except Exception as e:  # noqa: BLE001 - 시간 초과여도 그려진 만큼은 캡처
            if "Timeout" not in type(e).__name__ and "timeout" not in str(e).lower():
                sc.error = f"정적 렌더링 실패: {str(e).splitlines()[0][:160]}"
                return sc
        if settle:
            await page.wait_for_timeout(settle)
        info = await page.evaluate(COLLECT_JS)
        if info["frameset"]:
            sc.error = "프레임셋 화면 — 본문 프레임 화면들을 각각 렌더링함 (이 화면은 건너뜀)"
            return sc
        if info["textLen"] == 0 and info["visual"] == 0:
            sc.error = "정적 렌더링 결과 빈 화면 (서버 코드로만 내용을 만드는 화면)"
            return sc
        s = ctx.model.screens.get(t.skey)
        name = (s or {}).get("name") or ""
        if s is not None and info.get("title") and ("<" in name or "%>" in name or name == s.get("screenId")):
            s["name"] = info["title"][:120]       # 화면명이 태그(bean:message)·ID 뿐이면 렌더된 제목으로
        h = int(min(max(info["H"], 300), max_h))
        ext = "jpg" if fmt in ("jpg", "jpeg") else "png"
        rel = f"captures/{t.file_stem}.{ext}"
        shot = dict(path=str(capture_dir / f"{t.file_stem}.{ext}"), full_page=True,
                    clip={"x": 0, "y": 0, "width": width, "height": h}, timeout=timeout)
        if ext == "jpg":
            shot.update(type="jpeg", quality=quality)
        await page.screenshot(**shot)
        sc.file, sc.width, sc.height, sc.scale = rel, width, h, 1.0
        keymap = rs.keymap
        m = ctx.model
        for i, e in enumerate(info["els"]):
            key = keymap.get(e["n"])
            el = m.elements.get(key) if key else None
            if el is None or e["y"] >= h:
                continue
            sc.elements.append(DynElement(idx=i, tag=e["tag"], type=e["type"], id=el["elementId"],
                                          name=el.get("_name") or el.get("name") or "", label=el.get("label") or "",
                                          x=e["x"], y=e["y"], w=min(e["w"], width - e["x"]), h=min(e["h"], h - e["y"])))
        if js_errors:
            sc.warnings = [f"스크립트 오류 {len(js_errors)}건 (예: {js_errors[0]})"]
        return sc
    finally:
        site.drop(token)
        await page.close()
