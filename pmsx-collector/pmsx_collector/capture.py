"""동적 캡처 (Playwright) — docs/04 §2-②.

테스트계에 로그인 → 메뉴/화면 순회 → 스크린샷 + 요소 좌표 → 비위험 버튼 클릭 + 요청 가로채기.

안전 규칙
  ★ 변경성 요청(POST/PUT/PATCH/DELETE)은 allowRequests 패턴이 아니면 기록 후 abort
  - 위험 버튼(라벨/ID 규칙)은 클릭하지 않음
  - alert/confirm 은 자동 '취소' → 확인 후 서버 호출하는 흐름은 진행되지 않음
  - 새 창(팝업)은 URL 만 기록하고 닫음, 다운로드는 취소
  ★ denyHosts(운영계)와 일치하면 실행 거부
"""
from __future__ import annotations

import fnmatch
import re
import json
import struct
from datetime import datetime
from pathlib import Path
from typing import Callable
from urllib.parse import parse_qs, urlparse

from playwright.sync_api import Error as PwError
from playwright.sync_api import sync_playwright

from .capture_types import Blocked, CaptureResult, DynElement, Navigation, Observed, ScreenCapture
from .parsers import Context

WRITE_METHODS = {"POST", "PUT", "PATCH", "DELETE"}
SELECTOR = "button, input:not([type=hidden]), select, textarea, table[id], a[onclick], a[id]"
COLLECT_JS = """([sel, excl]) => {
  const labelFor = {};
  document.querySelectorAll('label[for]').forEach(l => labelFor[l.htmlFor] = l.innerText.trim());
  const hasWord = s => /[0-9A-Za-z\\uAC00-\\uD7A3]/.test(s || '');
  const out = [];
  document.querySelectorAll(sel).forEach((el, i) => {
    const r = el.getBoundingClientRect();
    const st = getComputedStyle(el);
    if (r.width === 0 || r.height === 0 || st.visibility === 'hidden' || st.display === 'none') return;
    if (excl.some(x => el.closest(x))) return;          // 공통 레이아웃(GNB·LNB) 등 제외 영역
    const tag = el.tagName.toLowerCase();
    const type = (el.getAttribute('type') || '').toLowerCase();
    const cands = [
      (tag === 'button' || tag === 'a') ? el.innerText.trim() : '',
      (tag === 'input' && ['button','submit','reset'].includes(type)) ? el.value : '',
      el.id ? (labelFor[el.id] || '') : '',
      el.getAttribute('title') || '', el.getAttribute('placeholder') || '', el.getAttribute('aria-label') || ''];
    const label = (cands.find(hasWord) || '').trim();
    out.push({idx: i, tag, type, id: el.id || '', name: el.getAttribute('name') || '', label,
              x: r.left + window.scrollX, y: r.top + window.scrollY, w: r.width, h: r.height});
  });
  return out;
}"""


FINGERPRINT_JS = """([sel, i]) => {
  const el = document.querySelectorAll(sel)[i];
  if (!el) return null;
  // 모달·오버레이가 덮고 있으면 클릭할 수 없는 상태 → 다시 열어야 한다
  const r = el.getBoundingClientRect();
  const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
  let onTop = true;
  if (cx >= 0 && cy >= 0 && cx < window.innerWidth && cy < window.innerHeight) {
    const t = document.elementFromPoint(cx, cy);
    onTop = !!t && (t === el || el.contains(t) || t.contains(el));
  }
  return [el.tagName.toLowerCase(), el.id || '', el.getAttribute('name') || '', onTop];
}"""


def browser_launch_args(cfg) -> dict:
    """브라우저는 번들하지 않는다 — 고객 PC의 Chrome/Edge(channel) 또는 지정 실행파일(browserPath)을 쓴다.

    capture.browserPath    : chrome.exe / msedge.exe / chromium 실행파일 경로 (지정 시 channel 무시)
    capture.browserChannel : chrome(기본) | msedge | chrome-beta | msedge-beta ...
    """
    args = {"headless": bool(cfg.get("capture.headless", True))}
    path = cfg.get("capture.browserPath")
    if path:
        args["executable_path"] = str(cfg.resolve(path))
    else:
        args["channel"] = cfg.get("capture.browserChannel", "chrome")
    return args


def _static_events(ctx: Context, skey: str) -> dict[tuple[str, str], set[str]]:
    """정적 분석으로 알려진 요소별 이벤트 — ('id', btnX) / ('name', eduYear) → {'click', 'change', ...}"""
    m = ctx.model
    out: dict[tuple[str, str], set[str]] = {}
    for el in m.elements.values():
        if el["screenKey"] != skey:
            continue
        evs = {e.get("event") for e in m.edges_from(el["key"], "event") if e.get("event")}
        if not evs:
            continue
        eid = el["elementId"]
        if not eid.startswith("name:") and not re.fullmatch(r"(button|input|select|textarea|a|table)\d+", eid):
            out.setdefault(("id", eid), set()).update(evs)
        if el.get("_name"):
            out.setdefault(("name", el["_name"]), set()).update(evs)
    return out


def _png_size(path: Path) -> tuple[int, int]:
    with open(path, "rb") as f:
        head = f.read(24)
    return struct.unpack(">II", head[16:24])


class _State:
    def __init__(self):
        self.blocking = False
        self.recording = True
        self.owner: int | None = None
        self.sc: ScreenCapture | None = None


def capture_targets(ctx: Context) -> list[tuple[str | None, str]]:
    """캡처 대상 (화면 키, URL) — 메뉴 순서 → 메뉴에 없는 화면. 브라우저로 열 수 없는 URL·제외 경로는 뺀다."""
    cfg = ctx.config
    targets: list[tuple[str | None, str]] = []
    seen: set[str] = set()
    for menu in sorted(ctx.model.menus.values(), key=lambda m: (m.get("parentKey") or "", m.get("order", 0))):
        if menu.get("url") and menu["url"] not in seen:
            targets.append((menu.get("screenKey"), menu["url"]))
            seen.add(menu["url"])
    for skey, s in sorted(ctx.model.screens.items()):
        if s.get("url") and s["url"] not in seen:
            targets.append((skey, s["url"]))
            seen.add(s["url"])
    # 브라우저로 열 수 없는 URL(Nexacro 폼 URL)·캡처 제외 경로(capture.excludeUrls)
    excl_urls = cfg.get("capture.excludeUrls", []) or []
    return [(k, u) for k, u in targets if "::" not in u and not any(fnmatch.fnmatch(u, x) for x in excl_urls)]


def run_capture(ctx: Context, capture_dir: Path, log: Callable[[str], None] = print,
                skip_screens: set[str] | None = None) -> CaptureResult:
    """skip_screens: 증분 수집에서 이전 캡처를 재사용할 화면 키 — 이 화면들은 열지 않는다."""
    cfg = ctx.config
    res = CaptureResult()
    targets = [(k, u) for k, u in capture_targets(ctx) if not (k and skip_screens and k in skip_screens)]
    if not targets:
        log("    다시 캡처할 화면 없음 (모두 이전 캡처 재사용)")
        return res
    base = (cfg.get("capture.baseUrl") or "").rstrip("/")
    host = urlparse(base).hostname or ""
    if not base:
        res.errors.append("capture.baseUrl 미설정")
        return res
    if any(fnmatch.fnmatch(host, d) for d in cfg.get("capture.denyHosts", []) or []):
        res.errors.append(f"운영계 차단 목록(denyHosts)과 일치하는 호스트라 캡처를 거부했습니다: {host}")
        return res

    allow = cfg.get("capture.allowRequests", []) or []
    danger_labels = cfg.danger_labels
    danger_ids = cfg.danger_ids
    wait_ms = int(cfg.get("capture.clickWaitMs", 700))
    timeout = int(cfg.get("capture.pageTimeoutMs", 15000))
    masks = cfg.get("capture.maskSelectors", []) or []
    vp = cfg.get("capture.viewport", {"width": 1280, "height": 800})
    capture_dir.mkdir(parents=True, exist_ok=True)
    st = _State()

    def is_danger(d: DynElement) -> bool:
        lid = (d.id or "").lower()
        return any(w in (d.label or "") for w in danger_labels) or any(w in lid for w in danger_ids)

    def on_route(route):
        req = route.request
        path = urlparse(req.url).path
        if st.blocking and req.resource_type in ("xhr", "fetch", "document"):
            params: list[str] = []
            body = req.post_data or ""
            if body:
                try:
                    data = json.loads(body)
                    params = sorted(data.keys()) if isinstance(data, dict) else ["(list)"]
                except ValueError:
                    params = sorted(parse_qs(body).keys())
            else:
                params = sorted(parse_qs(urlparse(req.url).query).keys())
            is_write = req.method in WRITE_METHODS and not any(fnmatch.fnmatch(path, p) for p in allow)
            # 화면 문서 자체는 제외, 버튼 클릭으로 생긴 문서 요청(다운로드·이동)은 기록
            if st.sc is not None and st.recording and (req.resource_type != "document" or st.owner is not None):
                st.sc.observed.append(Observed(st.owner, req.method, ctx.normalize_url(path), params))
            if is_write and st.sc is not None:
                st.sc.blocked.append(Blocked(st.owner, req.method, req.url, datetime.now().isoformat(timespec="seconds")))
                return route.abort()
        return route.continue_()

    extra_events = bool(cfg.get("capture.extraEvents", True))
    with sync_playwright() as p:
        try:
            browser = p.chromium.launch(**browser_launch_args(cfg))
        except PwError as e:
            res.errors.append(f"브라우저 실행 실패 (설치된 Chrome/Edge 또는 capture.browserPath 필요): {str(e).splitlines()[0]}")
            return res
        context = browser.new_context(viewport=vp, accept_downloads=True, locale="ko-KR",
                                      device_scale_factor=float(cfg.get("capture.deviceScaleFactor", 1)))
        context.set_default_timeout(timeout)
        context.route("**/*", on_route)
        page = context.new_page()
        page.on("dialog", lambda d: d.dismiss())
        page.on("download", lambda d: d.cancel())

        # ---------- 로그인 ----------
        try:
            login = cfg.get("capture.login") or {}
            if login:
                page.goto(base + login.get("url", "/"))
                for step in login.get("steps", []):
                    if "fill" in step:
                        page.fill(step["fill"]["selector"], cfg.env_value(step["fill"]))
                    elif "click" in step:
                        page.click(step["click"])
                    elif "waitUrl" in step:
                        page.wait_for_url(step["waitUrl"])
                    elif "wait" in step:
                        page.wait_for_timeout(int(step["wait"]))
                log("    로그인 완료")
        except PwError as e:
            res.errors.append(f"로그인 실패: {str(e).splitlines()[0]}")
            browser.close()
            return res
        st.blocking = True

        excl_sel = list(cfg.get("capture.excludeSelectors", []) or [])

        for skey, url in targets:
            sc = ScreenCapture(skey, url)
            res.screens.append(sc)
            st.sc, st.owner = sc, None
            try:
                resp = page.goto(base + url, wait_until="networkidle")
                if resp is None or resp.status >= 400:
                    sc.error = f"화면 열기 실패 HTTP {resp.status if resp else '-'}: {url}"
                    continue
                if urlparse(page.url).path != urlparse(base + url).path:
                    sc.error = f"다른 화면으로 이동됨 ({page.url}) — 세션 만료 또는 권한 문제"
                    continue
                if not skey:
                    sc.error = f"화면은 열렸으나 소스를 찾지 못함: {url}"
                    continue
                for msel in masks:
                    page.add_style_tag(content=f"{msel}{{filter:blur(6px)!important}}")
                sid = skey.rsplit(":", 1)[-1]
                png = capture_dir / f"{sid}.png"
                page.screenshot(path=str(png), full_page=True)
                sc.file = f"captures/{png.name}"
                sc.width, sc.height = _png_size(png)
                sc.scale = float(page.evaluate("window.devicePixelRatio") or 1)
                sc.elements = [DynElement(**d) for d in page.evaluate(COLLECT_JS, [SELECTOR, excl_sel])]
                log(f"    {sid}: 요소 {len(sc.elements)}개")

                # ---------- 버튼 클릭 + 클릭 외 이벤트 ----------
                # 화면 재로딩은 상태가 바뀐 경우(다른 URL로 이동, 대상 요소 위치가 달라짐)에만 한다.
                def ensure_state(d: DynElement):
                    same = urlparse(page.url).path == urlparse(base + url).path
                    if same:
                        try:
                            fp = page.evaluate(FINGERPRINT_JS, [SELECTOR, d.idx])
                            same = bool(fp) and fp[:3] == [d.tag, d.id, d.name] and fp[3]
                        except PwError:
                            same = False
                    if not same:
                        st.owner, st.recording = None, False
                        page.goto(base + url, wait_until="networkidle")   # 상태 초기화 (요청 기록 안 함)
                        res.reloads += 1

                def act(pos: int, d: DynElement, label: str, fn):
                    ensure_state(d)
                    st.owner, st.recording = pos, True
                    before = set(context.pages)
                    n_obs = len(sc.observed)
                    try:
                        fn(page.locator(SELECTOR).nth(d.idx))
                        page.wait_for_timeout(wait_ms)
                        try:
                            page.wait_for_load_state("networkidle", timeout=3000)
                        except PwError:
                            pass
                    except PwError as e:
                        sc.error = (sc.error or "") + f" [{d.id or d.label} {label} 실패: {str(e).splitlines()[0][:80]}]"
                    navigated = False
                    for newp in set(context.pages) - before:
                        sc.navigations.append(Navigation(pos, newp.url))
                        newp.close()
                        navigated = True
                    if urlparse(page.url).path != urlparse(base + url).path:
                        sc.navigations.append(Navigation(pos, page.url))
                        navigated = True
                    if navigated:
                        # 이동한 화면이 로드하며 보낸 요청은 이 버튼이 아니라 그 화면의 onload 것이다
                        del sc.observed[n_obs:]
                    st.owner = None

                for pos, d in enumerate(sc.elements):
                    clickable = d.tag in ("button", "a") or (d.tag == "input" and d.type in ("button", "submit", "image"))
                    if not clickable:
                        continue
                    if is_danger(d):
                        d.danger = True
                        continue
                    act(pos, d, "클릭", lambda loc: loc.click(timeout=3000))
                    sc.clicked.append(pos)
                    res.clicks += 1

                if extra_events:
                    static_ev = _static_events(ctx, skey)
                    for pos, d in enumerate(sc.elements):
                        evs = static_ev.get(("id", d.id)) if d.id else None
                        evs = evs or (static_ev.get(("name", d.name)) if d.name else None) or set()
                        if not evs & {"dblclick", "change"} or is_danger(d):
                            continue
                        if "change" in evs:
                            act(pos, d, "change", lambda loc: loc.dispatch_event("change"))
                            res.extra_events += 1
                        if "dblclick" in evs:
                            def dbl(loc, tag=d.tag):
                                rows = loc.locator("tbody tr")
                                (rows.first if tag == "table" and rows.count() > 0 else loc).dblclick(timeout=3000)
                            act(pos, d, "dblclick", dbl)
                            res.extra_events += 1
            except PwError as e:
                sc.error = f"캡처 오류: {str(e).splitlines()[0][:160]}"
            finally:
                st.owner = None
        st.sc = None
        browser.close()
    return res
