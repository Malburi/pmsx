"""파서 플러그인 — docs/04 §2-③.

모든 파서는 같은 인터페이스(Parser)를 구현하고, 공유 Context 의 ModelBuilder 에 조각을 넣는다.
레이어 순서(db → sql → server → ui)대로 실행해 뒤 파서가 앞 파서의 인덱스를 참조한다.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Literal, Protocol

from ..config import Config
from ..model import ModelBuilder

Layer = Literal["db", "sql", "server", "ui"]
LAYER_ORDER = {"db": 0, "sql": 1, "server": 2, "ui": 3}


@dataclass
class Route:
    """서버 URL 매핑 1건."""
    path: str
    methods: list[str]          # 비어 있으면 모든 메서드
    handler_key: str            # fn:java:...#Class.method
    view: str | None            # 화면 뷰 이름 (JSP 렌더링이면)
    kind: Literal["api", "screen"]


@dataclass
class Context:
    config: Config
    model: ModelBuilder
    # db
    table_pk: dict[str, list[str]] = field(default_factory=dict)          # TB_EMP -> [EMP_NO]
    table_cols: dict[str, set[str]] = field(default_factory=dict)
    # sql
    mapper_namespaces: dict[str, str] = field(default_factory=dict)       # FQN -> 키에 쓸 이름(단축, 충돌 시 FQN)
    sql_by_ref: dict[str, str] = field(default_factory=dict)              # "ns.id"(전체·단축) -> sql 키 (iBatis/SqlSession 문자열 호출용)
    join_evidence: list[tuple[str, tuple[str, str], tuple[str, str]]] = field(default_factory=list)  # (sqlKey, (t,c), (t,c))
    # server
    routes: list[Route] = field(default_factory=list)
    # ui
    screen_by_url: dict[str, str] = field(default_factory=dict)          # /hr/emp/HR0101.do -> scr key
    static_elements: dict[str, list[dict]] = field(default_factory=dict)  # scr key -> 정적 요소
    screen_onload: dict[str, list[str]] = field(default_factory=dict)     # scr key -> onload 코드 단위 키
    wireframes: dict[str, dict] = field(default_factory=dict)             # scr key -> 와이어프레임 그리기 명세 (Nexacro 등)
    linked_apis: set[str] = field(default_factory=set)                    # ui_common.link_server 처리 완료 API
    spa_routes: list[dict] = field(default_factory=list)                  # SPA 라우트 (라우터 메뉴용)
    routine_bodies: dict[str, tuple[str, str, str]] = field(default_factory=dict)  # sp 키 -> (본문, 방언, 근거 위치)
    sql_fragments: dict[str, object] = field(default_factory=dict)        # MyBatis <sql id> (ns.id·id) → XML 요소
    view_actions: dict[str, list[str]] = field(default_factory=dict)      # Struts: JSP 뷰 → 그 화면을 보여 주는 Action 경로들

    def normalize_url(self, url: str) -> str:
        """경로만 남긴다. 단 source.keepQueryParams(예: Struts DispatchAction 의 method)는 엔드포인트를
        구분하는 값이므로 `?method=save` 한 개만 남긴다 (값이 정적으로 확정된 경우)."""
        url = url.strip()
        keep = self.config.get("source.keepQueryParams") or []
        kept = ""
        if keep and "?" in url:
            query = url.split("?", 1)[1].split("#", 1)[0]
            for part in query.split("&"):
                k, _, v = part.partition("=")
                if k.strip() in keep and re.fullmatch(r"[\w.-]+", v.strip() or "-"):
                    v = v.strip()
                    if self.config.get("source.queryParamIgnoreCase"):
                        v = v.lower()        # 디스패처가 메서드명을 대소문자 구분 없이 찾는 프레임워크 (doSaveexam ← saveExam)
                    kept = f"?{k.strip()}={v}"
                    break
        url = url.split("?", 1)[0].split("#", 1)[0].split(";jsessionid", 1)[0].strip()
        cp = self.config.context_path
        if cp and url.startswith(cp + "/"):
            url = url[len(cp):]
        if not url.startswith("/"):
            url = "/" + url
        return url + kept

    def route_for(self, path: str) -> list[Route]:
        """정확히 일치하는 매핑 우선, 없으면 경로 변수({id}·:id)를 와일드카드로 본 매칭."""
        path = self.normalize_url(path)
        exact = [r for r in self.routes if r.path == path]
        if exact:
            return exact
        return [r for r in self.routes if path_match(r.path, path)]


def _is_var(seg: str) -> bool:
    return (seg.startswith("{") and seg.endswith("}")) or seg.startswith(":")


def path_match(a: str, b: str) -> bool:
    """'/api/edu/courses/{courseId}' ~ '/api/edu/courses/{id}' ~ '/api/edu/courses/3'"""
    sa, sb = a.strip("/").split("/"), b.strip("/").split("/")
    return len(sa) == len(sb) and all(x == y or _is_var(x) or _is_var(y) for x, y in zip(sa, sb))


class Parser(Protocol):
    name: str
    layer: Layer

    def detect(self, root: Path) -> float: ...           # 이 경로에 적용 가능성(0~1)

    def parse(self, ctx: Context) -> None: ...
