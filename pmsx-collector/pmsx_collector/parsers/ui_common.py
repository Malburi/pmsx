"""화면 파서 공용 후처리 — 화면 URL 해석, 메뉴↔화면 연결, 화면 API↔서버 매핑, 팝업 타입 보정.

여러 화면 기술(JSP·Nexacro·Vue/React)이 한 시스템에 섞여도 같은 규칙으로 처리하도록 파서 밖으로 뺐다.
모든 함수는 여러 번 호출해도 결과가 같다(멱등) — 각 화면 파서가 끝날 때, 그리고 파이프라인 마지막에 한 번 더 부른다.
"""
from __future__ import annotations

import re

from ..keys import api_key, scr_key
from . import Context


def screen_for_url(ctx: Context, url: str) -> str | None:
    """메뉴·화면이동 URL → 화면 키. Nexacro 폼 URL(PY::PY0101.xfdl)과 SPA 경로(/emp)도 처리."""
    if not url:
        return None
    if url in ctx.screen_by_url:
        return ctx.screen_by_url[url]
    if "::" not in url:
        path = ctx.normalize_url(url)
        if path in ctx.screen_by_url:
            return ctx.screen_by_url[path]
        if path.rstrip("/") in ctx.screen_by_url:
            return ctx.screen_by_url[path.rstrip("/")]
    mm = re.search(r"([\w-]+)\.(?:do|jsp|html|xfdl|vue)$", url.split("?", 1)[0])
    if mm:
        cand = scr_key(ctx.config.sys_code, mm.group(1))
        return cand if cand in ctx.model.screens else None
    return None


def link_menus(ctx: Context) -> None:
    for menu in ctx.model.menus.values():
        if menu.get("url") and not menu.get("screenKey"):
            s = screen_for_url(ctx, menu["url"])
            if s:
                menu["screenKey"] = s


def link_server(ctx: Context) -> None:
    """화면이 부르는 API → 서버 핸들러(handles). 화면에서 안 부르는 서버 API 도 목록에 넣는다."""
    m = ctx.model
    done = ctx.linked_apis
    for akey, api in list(m.apis.items()):
        if akey in done:
            continue
        done.add(akey)
        routes = [r for r in ctx.route_for(api["path"]) if r.kind == "api"]
        if not routes:
            m.fail("parse", akey, "서버 URL 매핑을 찾지 못함")
            continue
        # REST: 같은 경로에 메서드별 핸들러가 있으면 메서드가 맞는 것만 연결
        same = [r for r in routes if r.methods and api["method"] in r.methods]
        routes = same or [r for r in routes if not r.methods] or routes
        for r in routes:
            ok = not r.methods or api["method"] in r.methods
            m.add_edge(akey, r.handler_key, "handles", confidence=0.99 if ok else 0.7,
                       evidence=None if ok else f"메서드 불일치: 화면 {api['method']} / 서버 {','.join(r.methods)}")
    known = {a["path"] for a in m.apis.values()}
    for r in ctx.routes:
        if r.kind == "api" and r.path not in known:
            method = r.methods[0] if r.methods else "GET"
            akey = api_key(method, r.path)
            m.add_api(akey, method, r.path)
            m.add_edge(akey, r.handler_key, "handles", confidence=0.99)
            done.add(akey)
            known.add(r.path)


def refine_popup_types(ctx: Context) -> None:
    """팝업 화면을 여는 버튼은 type=popup."""
    m = ctx.model
    for el in m.elements.values():
        if el["type"] != "button":
            continue
        for e in m.edges_from(el["key"], "event"):
            if any(n["to"] in m.screens and m.screens[n["to"]]["kind"] == "popup"
                   for n in m.edges_from(e["to"], "navigates")):
                el["type"] = "popup"
                break


def finalize_ui(ctx: Context) -> None:
    """모든 화면 파서가 끝난 뒤: (폴더 메뉴 생성) → 메뉴 연결 → 연결 못 한 메뉴 기록 → 서버 매핑 → 팝업 타입."""
    if ctx.config.get("menu.directory") and not ctx.model.menus:
        from .menu import build_directory_menu
        build_directory_menu(ctx)
    link_menus(ctx)
    for menu in ctx.model.menus.values():
        if menu.get("url") and not menu.get("screenKey"):
            ctx.model.fail("menu", menu["key"], f"메뉴 URL에 해당하는 화면 소스 없음: {menu['url']}")
    link_server(ctx)
    refine_popup_types(ctx)
