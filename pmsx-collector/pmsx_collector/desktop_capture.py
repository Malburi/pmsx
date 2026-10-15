"""데스크톱 캡처 (FR-CL-006) — Windows UI Automation(pywinauto uia 백엔드) + Win32 메시지.

WinForms(·MiPlatform/XPlatform 런타임 등) 실행 파일을 띄워
  ① 메인 창 캡처 → ② 메뉴(정적 분석으로 알아낸 메뉴 경로)를 UIA Expand/Invoke 로 열어 화면을 하나씩 연다
  ③ 창 클라이언트 영역 캡처(PrintWindow) + 컨트롤 좌표(클라이언트 기준, DPI 배율로 나눈 논리 px) 수집
     AutomationId = WinForms 컨트롤 Name = 디자이너 필드명 → 정적 요소와 id 로 매칭
  ④ 위험하지 않은 버튼만 클릭(BM_CLICK 메시지) — 메시지 상자는 '아니요/취소'로 닫고,
     새로 뜬 폼은 팝업 이동으로 기록·캡처 후 닫는다(WM_CLOSE)
  ⑤ 끝나면 앱 프로세스를 반드시 종료한다.
마우스·키보드 입력을 쓰지 않으므로 잠긴 화면·원격 세션에서도 동작하고 사용자의 마우스를 뺏지 않는다.

설정 (collector.yaml)
  capture:
    enabled: true
    desktop:
      exe: src/HrWin/bin/Release/net7.0-windows/HrWin.exe
      args: []
      mainWindowTitle: "인사관리 시스템"      # 정규식, 없으면 첫 창
      startTimeoutSec: 20
      clickWaitMs: 800
      clickButtons: true
      skipLabels: [닫기, 종료, 취소, 선택, 확인]   # 창을 닫는 버튼은 누르지 않음
"""
from __future__ import annotations

import ctypes
import threading
import time
from pathlib import Path
from typing import Callable

from .capture_types import CaptureResult, DynElement, Navigation, ScreenCapture
from .parsers import Context

INTERACTIVE = {"Button": ("button", ""), "Edit": ("input", "text"), "ComboBox": ("select", ""),
               "DataGrid": ("table", ""), "Table": ("table", ""), "CheckBox": ("input", "checkbox"),
               "RadioButton": ("input", "radio"), "Tab": ("tab", ""), "Tree": ("tree", ""), "List": ("select", ""),
               "Spinner": ("input", "number"), "Hyperlink": ("a", ""), "Calendar": ("input", "date")}
DIALOG_CLASS = "#32770"
WM_CLOSE, WM_COMMAND, BM_CLICK = 0x0010, 0x0111, 0x00F5
IDOK, IDCANCEL, IDNO = 1, 2, 7
PW_CLIENTONLY, PW_RENDERFULLCONTENT = 1, 2
DEFAULT_SKIP = ["닫기", "종료", "취소", "선택", "확인", "Close", "OK", "Cancel"]


def _dpi_aware() -> None:
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:  # noqa: BLE001
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:  # noqa: BLE001
            pass


def _scale(hwnd: int) -> float:
    try:
        return ctypes.windll.user32.GetDpiForWindow(hwnd) / 96.0 or 1.0
    except Exception:  # noqa: BLE001
        return 1.0


def _client(hwnd: int) -> tuple[int, int, int, int]:
    """클라이언트 영역 (화면 x, 화면 y, 너비, 높이) — 물리 px."""
    import win32gui
    x, y = win32gui.ClientToScreen(hwnd, (0, 0))
    l, t, r, b = win32gui.GetClientRect(hwnd)
    return x, y, r - l, b - t


def print_window(hwnd: int):
    """창 클라이언트 영역을 PIL 이미지로 — 화면을 직접 찍지 않아 가려지거나 잠긴 화면에서도 된다."""
    import win32gui
    import win32ui
    from PIL import Image
    _, _, w, h = _client(hwnd)
    hdc = win32gui.GetWindowDC(hwnd)
    src = win32ui.CreateDCFromHandle(hdc)
    mem = src.CreateCompatibleDC()
    bmp = win32ui.CreateBitmap()
    bmp.CreateCompatibleBitmap(src, max(w, 1), max(h, 1))
    mem.SelectObject(bmp)
    try:
        ctypes.windll.user32.PrintWindow(hwnd, mem.GetSafeHdc(), PW_CLIENTONLY | PW_RENDERFULLCONTENT)
        info = bmp.GetInfo()
        img = Image.frombuffer("RGB", (info["bmWidth"], info["bmHeight"]), bmp.GetBitmapBits(True), "raw", "BGRX", 0, 1)
        return img.copy()
    finally:
        win32gui.DeleteObject(bmp.GetHandle())
        mem.DeleteDC()
        src.DeleteDC()
        win32gui.ReleaseDC(hwnd, hdc)


def process_windows(pid: int) -> dict[int, tuple[str, str]]:
    """프로세스의 보이는 최상위 창 → (클래스, 제목)."""
    import win32gui
    import win32process
    out: dict[int, tuple[str, str]] = {}

    def cb(h, _):
        try:
            if win32gui.IsWindowVisible(h) and win32process.GetWindowThreadProcessId(h)[1] == pid:
                cls = win32gui.GetClassName(h)
                if cls not in ("SysShadow", "IME", "MSCTFIME UI"):
                    out[h] = (cls, win32gui.GetWindowText(h))
        except Exception:  # noqa: BLE001
            pass
        return True

    win32gui.EnumWindows(cb, None)
    return out


def dialog_text(hwnd: int) -> str:
    import win32gui
    texts: list[str] = []

    def cb(h, _):
        if win32gui.GetClassName(h) == "Static" and win32gui.GetWindowText(h):
            texts.append(win32gui.GetWindowText(h))
        return True

    try:
        win32gui.EnumChildWindows(hwnd, cb, None)
    except Exception:  # noqa: BLE001
        pass
    return " ".join(texts)[:200]


def dismiss_dialog(hwnd: int) -> str:
    """메시지 상자 → '아니요'(IDNO), 없으면 '취소'(IDCANCEL), 그래도 안 닫히면 WM_CLOSE. 반환: 메시지."""
    import win32gui
    msg = dialog_text(hwnd)
    for cmd in (IDNO, IDCANCEL, IDOK):
        try:
            if win32gui.GetDlgItem(hwnd, cmd):
                win32gui.PostMessage(hwnd, WM_COMMAND, cmd, 0)
                return msg
        except Exception:  # noqa: BLE001 - 해당 버튼 없음
            continue
    win32gui.PostMessage(hwnd, WM_CLOSE, 0, 0)
    return msg


class _DialogWatchdog:
    """동기 호출(UIA Invoke 등)이 모달 메시지 상자 때문에 막히지 않도록, 일정 시간 뒤 대화상자를 닫는다."""

    def __init__(self, pid: int, delay: float, known: set[int]):
        self.pid, self.delay, self.known = pid, delay, set(known)
        self.messages: list[str] = []
        self._stop = threading.Event()
        self._t = threading.Thread(target=self._run, daemon=True)

    def __enter__(self):
        self._t.start()
        return self

    def __exit__(self, *exc):
        self._stop.set()
        self._t.join(timeout=2)

    def _run(self):
        end = time.time() + 30
        while not self._stop.is_set() and time.time() < end:
            if self._stop.wait(self.delay):
                return
            for h, (cls, _) in process_windows(self.pid).items():
                if cls == DIALOG_CLASS and h not in self.known:
                    self.messages.append(dismiss_dialog(h))
                    self.known.add(h)


class DesktopCapture:
    def __init__(self, ctx: Context, capture_dir: Path, log: Callable[[str], None]):
        self.ctx, self.cfg, self.log = ctx, ctx.config, log
        self.dir = capture_dir
        self.res = CaptureResult()
        self.captured: dict[str, ScreenCapture] = {}
        self.wait = int(self.cfg.get("capture.desktop.clickWaitMs", 800)) / 1000
        self.skip = list(self.cfg.get("capture.desktop.skipLabels", DEFAULT_SKIP) or [])
        self.by_title: dict[str, str] = {}
        for k, s in ctx.model.screens.items():
            if s.get("uiTech") in ("winforms", "miplatform", "xplatform"):
                self.by_title.setdefault(s["name"], k)

    # ---------- 공통 ----------
    def is_danger(self, d: DynElement) -> bool:
        lid = (d.id or "").lower()
        return any(w in (d.label or "") for w in self.cfg.danger_labels) or any(w in lid for w in self.cfg.danger_ids)

    def windows(self) -> dict[int, tuple[str, str]]:
        return process_windows(self.app.process)

    def wrap(self, hwnd: int):
        from pywinauto import Desktop
        return Desktop(backend="uia").window(handle=hwnd).wrapper_object()

    def new_windows(self, before: set[int], timeout: float) -> list[tuple[int, str, str]]:
        end = time.time() + timeout
        while time.time() < end:
            new = [(h, c, t) for h, (c, t) in self.windows().items() if h not in before]
            if new:
                time.sleep(0.4)          # 창 그리기·컨트롤 생성 대기
                return [(h, c, t) for h, (c, t) in self.windows().items() if h not in before]
            time.sleep(0.15)
        return []

    def close_window(self, hwnd: int) -> None:
        import win32gui
        try:
            win32gui.PostMessage(hwnd, WM_CLOSE, 0, 0)
        except Exception:  # noqa: BLE001
            return
        end = time.time() + 3
        while time.time() < end and hwnd in self.windows():
            for h, (c, _) in self.windows().items():       # 닫기 확인 창
                if c == DIALOG_CLASS:
                    dismiss_dialog(h)
            time.sleep(0.15)

    # ---------- 캡처 ----------
    def capture(self, hwnd: int, skey: str) -> ScreenCapture:
        if skey in self.captured:
            return self.captured[skey]
        scale = _scale(hwnd)
        cx, cy, cw, ch = _client(hwnd)
        sc = ScreenCapture(skey, self.ctx.model.screens[skey].get("url", skey), scale=scale)
        img = print_window(hwnd)
        sid = skey.rsplit(":", 1)[-1]
        self.dir.mkdir(parents=True, exist_ok=True)
        png = self.dir / f"{sid}.png"
        img.save(png, "PNG", optimize=True)
        sc.file, sc.width, sc.height = f"captures/{png.name}", img.size[0], img.size[1]
        static_ids = {e["elementId"] for e in self.ctx.model.elements.values() if e["screenKey"] == skey}
        sc._hwnds = {}  # type: ignore[attr-defined]
        for c in self.wrap(hwnd).descendants():
            ei = c.element_info
            aid, ctype = ei.automation_id or "", ei.control_type or ""
            if not ei.handle or not aid or aid.isdigit():
                continue
            if ctype not in INTERACTIVE and aid not in static_ids:
                continue
            r = c.rectangle()
            x, y = (r.left - cx) / scale, (r.top - cy) / scale
            w, h = r.width() / scale, r.height() / scale
            if w <= 0 or h <= 0 or x < -1 or y < -1:
                continue
            tag, typ = INTERACTIVE.get(ctype, ("other", ""))
            d = DynElement(len(sc.elements), tag, typ, aid, aid, ei.name or "", x, y, w, h)
            d.danger = tag == "button" and self.is_danger(d)
            sc._hwnds[d.idx] = ei.handle  # type: ignore[attr-defined]
            sc.elements.append(d)
        self.captured[skey] = sc
        self.res.screens.append(sc)
        return sc

    def click_buttons(self, hwnd: int, sc: ScreenCapture, depth: int = 0) -> None:
        import win32gui
        if not self.cfg.get("capture.desktop.clickButtons", True):
            return
        for d in list(sc.elements):
            if d.tag != "button" or d.danger or any(d.label.strip().startswith(s) for s in self.skip):
                continue
            bh = sc._hwnds.get(d.idx)  # type: ignore[attr-defined]
            if not bh or not win32gui.IsWindow(bh) or not win32gui.IsWindowEnabled(bh):
                continue
            before = set(self.windows())
            win32gui.PostMessage(bh, BM_CLICK, 0, 0)
            self.res.clicks += 1
            sc.clicked.append(d.idx)
            for h, cls, title in self.new_windows(before, self.wait):
                if cls == DIALOG_CLASS:
                    dismiss_dialog(h)
                    continue
                target = self.by_title.get(title)
                if target:
                    sc.navigations.append(Navigation(d.idx, target))
                    if target not in self.captured:
                        psc = self.capture(h, target)        # 팝업도 캡처 (메뉴에 없는 화면)
                        if depth < 1:
                            self.click_buttons(h, psc, depth + 1)
                self.close_window(h)

    def open_menu(self, main_hwnd: int, path: list[str]) -> list[str]:
        """UIA Expand → Invoke (입력 장치 없이). 모달 메시지 상자는 감시 스레드가 닫는다. 반환: 닫은 메시지."""
        main = self.wrap(main_hwnd)
        cur = next((x for x in main.descendants(control_type="MenuItem") if x.window_text() == path[0]), None)
        if cur is None:
            raise LookupError(f"메뉴 항목 없음: {path[0]}")
        for label in path[1:]:
            cur.expand()
            time.sleep(0.3)
            nxt = next((x for x in cur.descendants(control_type="MenuItem") if x.window_text() == label), None)
            if nxt is None:
                raise LookupError(f"메뉴 항목 없음: {' > '.join(path)}")
            cur = nxt
        with _DialogWatchdog(self.app.process, 1.5, set(self.windows())) as wd:
            try:
                cur.invoke()
            except Exception:  # noqa: BLE001 - 모달 대화상자로 Invoke 가 시간 초과되면 감시 스레드가 닫은 뒤 예외가 난다
                if not wd.messages:
                    time.sleep(2)
                if not wd.messages:
                    raise
        return wd.messages

    # ---------- 실행 ----------
    def run(self) -> CaptureResult:
        cfg = self.cfg
        exe = cfg.resolve(cfg.get("capture.desktop.exe"))
        if not exe or not exe.is_file():
            self.res.errors.append(f"데스크톱 캡처 실행 파일 없음: {cfg.get('capture.desktop.exe')}")
            return self.res
        try:
            from pywinauto import Application
        except ImportError:
            self.res.errors.append("pywinauto 미설치 — 데스크톱 캡처 불가")
            return self.res
        _dpi_aware()
        args = " ".join(f'"{a}"' for a in cfg.get("capture.desktop.args", []) or [])
        timeout = int(cfg.get("capture.desktop.startTimeoutSec", 20))
        self.app = Application(backend="uia").start(f'"{exe}" {args}'.strip(), work_dir=str(exe.parent), timeout=timeout)
        try:
            title = cfg.get("capture.desktop.mainWindowTitle")
            main = self.app.window(title_re=title) if title else self.app.top_window()
            main.wait("visible", timeout=timeout)
            main_hwnd = main.handle
            menus = getattr(self.ctx, "desktop_menus", [])
            main_key = self.by_title.get(main.window_text()) or (menus[0]["ownerScreen"] if menus else None)
            if main_key:
                self.capture(main_hwnd, main_key)
            for mp in menus:
                label = " > ".join(mp["path"])
                before = set(self.windows())
                try:
                    msgs = self.open_menu(main_hwnd, mp["path"])
                except Exception as e:  # noqa: BLE001
                    self.res.errors.append(f"메뉴 열기 실패 {label}: {str(e)[:120]}")
                    continue
                new = self.new_windows(before, max(self.wait, 2.0))
                for h, cls, _t in new:
                    if cls == DIALOG_CLASS:
                        msgs.append(dismiss_dialog(h))
                forms = [(h, t) for h, cls, t in new if cls != DIALOG_CLASS]
                if msgs and not forms:
                    self.res.errors.append(f"메뉴 '{label}' → 화면 대신 메시지 상자: {' / '.join(msgs)}")
                    continue
                if not forms:
                    self.res.errors.append(f"메뉴 '{label}' 클릭 후 새 화면이 열리지 않음")
                    continue
                for h, t in forms:
                    skey = self.by_title.get(t) or mp.get("screenKey")
                    if not skey or skey not in self.ctx.model.screens:
                        self.res.errors.append(f"열린 창 '{t}' 에 해당하는 화면 소스 없음")
                    elif skey not in self.captured:
                        sc = self.capture(h, skey)
                        self.click_buttons(h, sc)
                    self.close_window(h)
        finally:
            try:
                self.app.kill()
            except Exception:  # noqa: BLE001
                pass
        return self.res


def run_desktop_capture(ctx: Context, capture_dir: Path, log: Callable[[str], None] = print) -> CaptureResult:
    return DesktopCapture(ctx, capture_dir, log).run()


def rescale_static_boxes(ctx: Context, result: CaptureResult) -> None:
    """캡처에서 못 찾은(숨은 탭 등) 정적 요소의 디자이너 좌표를 캡처 좌표계로 옮긴다 — 매칭된 요소로 선형 보정."""
    m = ctx.model
    for sc in result.screens:
        if not sc.screen_key or sc.screen_key not in m.screens:
            continue
        els = [e for e in m.elements.values() if e["screenKey"] == sc.screen_key and e.get("_sbox")]
        pairs = [(e["_sbox"], e["box"]) for e in els if e.get("matched") == "full" and e.get("box")]
        if len(pairs) < 2:
            continue

        def fit(xs, ys):
            n = len(xs)
            mx, my = sum(xs) / n, sum(ys) / n
            var = sum((x - mx) ** 2 for x in xs)
            a = sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / var if var else 1.0
            return a, my - a * mx

        ax, bx = fit([p[0][0] for p in pairs], [p[1]["x"] for p in pairs])
        ay, by = fit([p[0][1] for p in pairs], [p[1]["y"] for p in pairs])
        for e in els:
            if e.get("matched") != "full":
                x, y, w, h = e["_sbox"]
                e["box"] = {"x": round(ax * x + bx, 1), "y": round(ay * y + by, 1), "w": round(ax * w, 1),
                            "h": round(ay * h, 1), "captureIndex": 0}
