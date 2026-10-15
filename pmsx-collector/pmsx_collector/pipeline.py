"""수집 파이프라인 — 정적 분석 → (동적 캡처) → 병합·매칭 → 추정 관계 → 패키징."""
from __future__ import annotations

import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Callable

from .config import Config
from .merge import apply_capture, compute_stats, infer_relations
from .model import ModelBuilder
from .package import write_package
from .parsers import LAYER_ORDER, Context
from .parsers.db_meta import DbMetaParser
from .parsers.ddl import DdlParser
from .parsers.jsp_jquery import JspJqueryParser
from .parsers.menu import MenuParser
from .parsers.mybatis import MyBatisParser
from .parsers.nexacro import NexacroParser
from .parsers.spa import ReactParser, VueParser
from .parsers.spring import SpringParser
from .parsers.ui_common import finalize_ui
from .validate import validate_model


def _lazy(module: str, cls: str):
    """R3 파서는 쓸 때만 import (tree-sitter-c-sharp 등 선택 의존성)."""
    def make():
        import importlib
        return getattr(importlib.import_module(f".parsers.{module}", __package__), cls)()
    make.key = f"{module}.{cls}"
    return make


UI_PARSERS = {"jsp-jquery": JspJqueryParser, "nexacro": NexacroParser, "vue": VueParser, "react": ReactParser,
              "websquare": _lazy("websquare", "WebSquareParser"), "miplatform": _lazy("xplatform", "XPlatformParser"),
              "xplatform": _lazy("xplatform", "XPlatformParser"), "winforms": _lazy("winforms", "WinFormsParser"),
              "aspnet-webforms": _lazy("aspnet", "AspNetWebFormsParser"), "aspnet-mvc": _lazy("aspnet", "AspNetMvcParser")}
SERVER_PARSERS = {"spring": SpringParser, "dotnet": _lazy("dotnet", "DotnetParser"),
                  "dotnet-data": _lazy("dotnet", "DotnetParser"), "struts1": _lazy("struts1", "Struts1Parser")}
SQL_PARSERS = {"mybatis": MyBatisParser, "queryxml": _lazy("queryxml", "QueryXmlParser")}


@dataclass
class RunResult:
    run_id: str
    model: dict
    out_dir: Path
    zip_path: Path
    errors: list[str] = field(default_factory=list)


def select_parsers(cfg: Config, m: ModelBuilder) -> list:
    chosen = []
    if cfg.get("db.ddlFiles"):
        chosen.append(DdlParser())
    if cfg.get("db.connection"):
        chosen.append(DbMetaParser())       # DDL 다음에 실행 → 같은 테이블은 DB 메타가 덮어씀
    made: set = set()

    def add(factory):
        k = getattr(factory, "key", factory)          # 같은 파서를 두 기술명으로 지정해도 한 번만 (miplatform+xplatform 등)
        if k not in made:
            made.add(k)
            chosen.append(factory())

    if cfg.get("queryXml"):
        add(SQL_PARSERS["queryxml"])           # 자체 프레임워크 쿼리 XML (ID → SQL)
    for tech in cfg.get("system.serverTech", []) or []:
        if tech in SQL_PARSERS:
            add(SQL_PARSERS[tech])
        elif tech in SERVER_PARSERS:
            add(SERVER_PARSERS[tech])
        else:
            m.fail("parse", f"serverTech:{tech}", "지원하지 않는 서버 기술 (파서 미구현)")
    if cfg.get("menu"):
        chosen.append(MenuParser())
    for tech in cfg.get("system.uiTech", []) or []:
        if tech in UI_PARSERS:
            add(UI_PARSERS[tech])
        else:
            m.fail("parse", f"uiTech:{tech}", "지원하지 않는 화면 기술")
    if cfg.get("harness.workspace"):
        from .parsers.harness import HarnessParser       # 기존 harness _workspace/index 결과 병합
        chosen.append(HarnessParser())
    # 메뉴는 화면 파서보다 먼저(라우터 메뉴는 SPA 파서가 라우트를 읽은 뒤), harness 는 맨 끝(우리 분석 결과의 빈 곳을 채움)
    def order(p):
        if p.name == "harness":
            return (9, 0)
        if p.name == "menu":
            return (LAYER_ORDER["ui"], 2 if cfg.get("menu.router") else 0)
        return (LAYER_ORDER[p.layer], 1)
    return sorted(chosen, key=order)


def render_wireframes(ctx: Context, capture_dir: Path, log: Callable[[str], None] = print) -> None:
    """실제 캡처가 없는 화면은 소스 좌표로 만든 와이어프레임을 캡처로 쓴다 (captures[].state = "wireframe")."""
    if not ctx.wireframes:
        return
    from .wireframe import render
    font = ctx.config.get("wireframe.font")
    n = 0
    for skey, spec in sorted(ctx.wireframes.items()):
        s = ctx.model.screens.get(skey)
        if s is None or s.get("captures"):
            continue
        sid = skey.rsplit(":", 1)[-1]
        png = capture_dir / f"{sid}.png"
        try:
            w, h = render(spec, png, font)
        except Exception as e:  # noqa: BLE001 - Pillow 미설치·폰트 문제 등은 기록 후 계속
            ctx.model.fail("capture", skey, f"와이어프레임 생성 실패: {str(e).splitlines()[0][:160]}")
            continue
        s.setdefault("captures", []).append({"file": f"captures/{png.name}", "width": w, "height": h,
                                             "state": "wireframe", "masked": False, "scale": 1})
        n += 1
    if n:
        log(f"  · 와이어프레임 {n}장 생성 (소스 좌표 기준)")


def _reuse_previous(cfg: Config, prev, info, out_root: Path, run_id: str, t0: float, log) -> RunResult:
    """소스·설정 변경이 없으면 이전 model.json 을 새 회차로 그대로 쓴다 (정적 분석·캡처 생략)."""
    import json
    from datetime import timezone

    from .incremental import copy_capture_png

    prev_dir = prev.run_dir(out_root)
    model = json.loads((prev_dir / "model.json").read_text(encoding="utf-8"))
    out_dir = out_root / run_id
    for s in model.get("screens", []):
        for c in s.get("captures", []):
            copy_capture_png(prev_dir, out_dir, c.get("file"))
    info.mode, info.reused = "reuse", sorted(s["key"] for s in model.get("screens", []))
    man = model["manifest"]
    man["runId"] = run_id
    man["collectedAt"] = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
    stats = {k: v for k, v in man.get("stats", {}).items() if not k.startswith("incremental")}
    stats.update(info.stats())
    stats["durationSec"] = round(time.time() - t0, 1)
    man["stats"] = stats
    log(f"  · 소스 변경 없음 → 이전 회차 {prev.run_id} 결과 재사용 (정적 분석·캡처 생략)")
    errors = validate_model(model)
    zip_path = write_package(cfg, model, out_dir, run_id, prev.warnings, info.to_dict())
    prev.run_id = run_id
    prev.save(out_root)
    return RunResult(run_id, model, out_dir, zip_path, errors)


def run(cfg: Config, capture: bool = True, log: Callable[[str], None] = print, incremental: bool = False) -> RunResult:
    from .incremental import (IncrementalInfo, State, capture_state, config_hash, copy_capture_png, diff_files,
                              scan_files, screen_hashes)

    t0 = time.time()
    run_id = f"{cfg.sys_code}-{datetime.now():%Y%m%d-%H%M%S}"
    out_root = cfg.resolve(cfg.get("output", "out"))
    chash = config_hash(cfg)
    files = scan_files(cfg)
    info = IncrementalInfo(enabled=incremental)
    prev = None
    if incremental:
        prev = State.load(out_root)
        if prev is None:
            info.reason = "이전 상태 파일(out/.state.json)이 없어 전체 수집"
        elif prev.sys_code != cfg.sys_code or prev.config_hash != chash:
            info.reason, prev = "설정 또는 수집기 버전이 바뀌어 전체 수집", None
        elif not (prev.run_dir(out_root) / "model.json").is_file():
            info.reason, prev = f"이전 회차 결과({prev.run_id})가 없어 전체 수집", None
        else:
            info.prev_run_id = prev.run_id
            info.changed = diff_files(prev.files, files)
            if not any(info.changed.values()):
                info.reason = "소스 변경 없음"
                return _reuse_previous(cfg, prev, info, out_root, run_id, t0, log)
            info.mode = "partial"
            n = sum(len(v) for v in info.changed.values())
            info.reason = f"변경 파일 {n}개 — 정적 분석은 전체 재실행(교차 참조 재계산), 캡처는 바뀐 화면만"
        log(f"  · 증분 수집: {info.reason}")

    m = ModelBuilder(cfg.sys_code, cfg.get("system.name", cfg.sys_code), cfg.get("system.uiTech", []) or [],
                     cfg.get("system.serverTech", []) or [], cfg.get("system.dbType"))
    ctx = Context(cfg, m)

    for p in select_parsers(cfg, m):
        log(f"  · 파서 {p.name}")
        p.parse(ctx)
        if not any(x == p.name or x.startswith(p.name + "(") for x in m.parsers_used):
            m.parsers_used.append(p.name)
    if getattr(ctx, "_cs_index", None) is not None:
        from .parsers.dotnet import finalize_dotnet
        finalize_dotnet(ctx)
    finalize_ui(ctx)
    hashes = screen_hashes(m, files)          # 캡처 결과를 합치기 전(정적 분석 결과)으로 계산 — 회차 간 비교 기준

    out_dir = out_root / run_id
    capture_dir = out_dir / "captures"
    capture_mode, result = "none", None
    desktop = bool(cfg.get("capture.desktop"))
    if capture and str(cfg.get("capture.mode", "")).lower() == "static-render":
        capture_mode = "render"             # 서버 없이 화면 소스를 브라우저로 그려 캡처 (render.static_capture)
    elif capture and cfg.get("capture.enabled", False):
        capture_mode = "desktop" if desktop else "web"
    if capture_mode == "render":
        from .render.static_capture import render_dep_hashes
        hashes = render_dep_hashes(ctx, hashes, files)      # include(공통 머리글 등)가 바뀌어도 다시 그린다
    reuse: dict[str, object] = {}
    if prev is not None and capture_mode != "none" and prev.capture_mode == capture_mode:
        for k, h in hashes.items():
            if (prev.screens.get(k) or {}).get("hash") == h:
                sc = prev.cached_capture(out_root, k)
                if sc is not None:
                    reuse[k] = sc

    if capture_mode == "desktop":
        from .capture_types import CaptureResult
        from .desktop_capture import rescale_static_boxes, run_desktop_capture   # pywinauto 는 쓸 때만
        prev_caps = {c.get("screen_key") for c in (prev.capture if prev else []) if c.get("screen_key")}
        if prev is not None and prev_caps and prev_caps <= set(reuse):
            log("  · 데스크톱 캡처: 바뀐 화면 없음 → 이전 캡처 재사용")
            result = CaptureResult(screens=[reuse[k] for k in sorted(prev_caps)])
        else:
            # 데스크톱 캡처는 메뉴를 따라 창을 여는 방식이라 화면 단위로 건너뛰지 않는다 (바뀐 화면이 있으면 전체)
            log("  · 데스크톱 캡처 (UI Automation)")
            result = run_desktop_capture(ctx, capture_dir, log)
            reuse = {}
        apply_capture(ctx, result)
        rescale_static_boxes(ctx, result)
        m.parsers_used.append("desktop-capture")
    elif capture_mode == "render":
        from .render.static_capture import run_static_render
        if reuse:
            log(f"  · 정적 렌더링: 이전 결과 재사용 {len(reuse)}화면")
        result = run_static_render(ctx, capture_dir, log, skip_screens=set(reuse))
        result.screens.extend(reuse[k] for k in sorted(reuse))
        apply_capture(ctx, result)
        m.parsers_used.append("static-render")
    elif capture_mode == "web":
        from .capture import run_capture   # playwright 는 캡처할 때만 로드
        log("  · 동적 캡처 (Playwright)" + (f" — 이전 캡처 재사용 {len(reuse)}화면" if reuse else ""))
        result = run_capture(ctx, capture_dir, log, skip_screens=set(reuse))
        result.screens.extend(reuse[k] for k in sorted(reuse))
        apply_capture(ctx, result)
        m.parsers_used.append("capture")
    else:
        log("  · 동적 캡처 생략 (정적 분석만)")
    if result is not None and prev is not None:
        for k, sc in reuse.items():
            copy_capture_png(prev.run_dir(out_root), out_dir, sc.file)
        info.reused = sorted(k for k in reuse if any(sc.screen_key == k for sc in result.screens))
        info.recaptured = sorted({sc.screen_key for sc in result.screens if sc.screen_key and sc.screen_key not in reuse})
    render_wireframes(ctx, capture_dir, log)

    infer_relations(ctx)
    m.capture_stats.update(info.stats())
    stats = compute_stats(m, time.time() - t0)
    model = m.to_dict(run_id, stats)
    errors = validate_model(model)
    zip_path = write_package(cfg, model, out_dir, run_id, m.warnings, info.to_dict() if incremental else None)
    caps, screens = capture_state(result, out_dir, hashes)
    State(cfg.sys_code, chash, run_id, files, screens, caps, capture_mode, m.warnings).save(out_root)
    return RunResult(run_id, model, out_dir, zip_path, errors)
