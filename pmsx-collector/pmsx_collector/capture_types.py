"""동적 캡처 결과 자료형 (playwright 없이도 import 가능)."""
from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class DynElement:
    idx: int                 # querySelectorAll 순번 (클릭 시 locator.nth 에 사용)
    tag: str
    type: str
    id: str
    name: str
    label: str
    x: float
    y: float
    w: float
    h: float
    danger: bool = False


@dataclass
class Observed:
    owner: int | None        # DynElement 리스트 위치, None = 화면 로드(onload)
    method: str
    path: str
    params: list[str] = field(default_factory=list)


@dataclass
class Navigation:
    owner: int | None
    url: str


@dataclass
class Blocked:
    owner: int | None
    method: str
    url: str
    at: str


@dataclass
class ScreenCapture:
    screen_key: str | None   # None = 메뉴 URL 은 있으나 화면 소스 없음
    url: str
    file: str | None = None  # 패키지 내 상대 경로 captures/HR0101.png
    width: int = 0
    height: int = 0
    scale: float = 1.0       # devicePixelRatio (이미지 px / CSS px)
    elements: list[DynElement] = field(default_factory=list)
    observed: list[Observed] = field(default_factory=list)
    navigations: list[Navigation] = field(default_factory=list)
    blocked: list[Blocked] = field(default_factory=list)
    clicked: list[int] = field(default_factory=list)
    error: str | None = None
    state: str = ""         # captures[].state (빈 값 = "초기", static-render = "rendered")
    warnings: list[str] = field(default_factory=list)


@dataclass
class CaptureResult:
    screens: list[ScreenCapture] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    reloads: int = 0         # 클릭 사이 화면 재로딩 횟수 (상태가 바뀐 경우에만)
    clicks: int = 0
    extra_events: int = 0    # 클릭 외 이벤트(dblclick·change) 시도 횟수
