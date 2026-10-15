"""병합·매칭(동적 ↔ 정적), 추정 관계, 통계."""
from __future__ import annotations

import re
from collections import defaultdict
from urllib.parse import urlparse

from .capture_types import CaptureResult, DynElement
from .keys import api_key, el_key, tbl_key
from .parsers import Context, path_match
from .parsers.jsp_jquery import static_type

# ---------------------------------------------------------------- 매칭


def _match(static: list[dict], dyn: list[DynElement]) -> dict[int, dict]:
    """① id → ② name → ③ 라벨+타입 순으로 매칭. 반환: 동적 위치 → 정적 요소."""
    out: dict[int, dict] = {}
    used: set[str] = set()
    rules = [
        lambda s, d: d.id and s["elementId"] == d.id,
        lambda s, d: d.name and s.get("_name") == d.name,
        lambda s, d: d.label and s["label"] == d.label and s["type"] in (static_type(d.tag, d.type), "popup"),
    ]
    for rule in rules:
        for i, d in enumerate(dyn):
            if i in out:
                continue
            for s in static:
                if s["key"] not in used and rule(s, d):
                    out[i] = s
                    used.add(s["key"])
                    break
    return out


def _reachable_fns(m, owner: str, depth: int = 4) -> list[str]:
    """요소/화면 → 이벤트 핸들러 → calls 를 따라 도달하는 함수들 (순서 유지)."""
    seen: list[str] = []
    frontier = [e["to"] for e in m.edges_from(owner, "event")]
    for _ in range(depth):
        nxt = []
        for f in frontier:
            if f in seen or not f.startswith("fn:"):
                continue
            seen.append(f)
            nxt += [e["to"] for e in m.edges_from(f, "calls")]
        frontier = nxt
    return seen


def apply_capture(ctx: Context, result: CaptureResult) -> None:
    m = ctx.model
    m.capture_stats.update({"captureClicks": result.clicks, "captureReloads": result.reloads,
                            "captureExtraEvents": result.extra_events})
    for err in result.errors:
        m.fail("capture", "capture", err)
    for sc in result.screens:
        if sc.error:
            m.fail("capture", sc.screen_key or sc.url, sc.error)
        if not sc.screen_key or sc.screen_key not in m.screens:
            continue
        skey = sc.screen_key
        for w in sc.warnings:
            m.warn("capture", skey, w)
        if sc.file:
            m.screens[skey].setdefault("captures", []).append(
                {"file": sc.file, "width": sc.width, "height": sc.height, "state": sc.state or "초기",
                 "masked": bool(ctx.config.get("capture.maskSelectors")), "scale": round(sc.scale, 2)})
        static = [e for e in m.elements.values() if e["screenKey"] == skey]
        matched = _match(static, sc.elements)
        owner_key: dict[int, str] = {}
        for i, d in enumerate(sc.elements):
            box = {"x": round(d.x, 1), "y": round(d.y, 1), "w": round(d.w, 1), "h": round(d.h, 1), "captureIndex": 0}
            s = matched.get(i)
            if s is not None:
                s["box"], s["matched"] = box, "full"
            elif not d.id and any(ms["label"] == d.label and ms["type"] == static_type(d.tag, d.type)
                                  for ms in matched.values()):
                # 그리드 행마다 반복되는 버튼(행 삭제 등) — 첫 번째만 요소로 둔다
                continue
            else:
                eid = d.id or f"dyn{i + 1}"
                s = m.elements.setdefault(el_key(skey, eid), {
                    "key": el_key(skey, eid), "screenKey": skey, "elementId": eid, "label": d.label or eid,
                    "type": static_type(d.tag, d.type), "matched": "dynamic-only", "_seq": 500 + i})
                s["box"] = box
            if d.danger:
                s["danger"] = True
            owner_key[i] = s["key"]

        def owner(o: int | None) -> str:
            return owner_key.get(o, skey) if o is not None else skey

        # 관측된 요청 → 정적 request 엣지 확인 또는 동적 엣지 추가
        for ob in sc.observed:
            if ob.path in ctx.screen_by_url or ob.path == urlparse(sc.url).path:
                continue    # 화면 문서 요청은 navigations 로 처리
            own = owner(ob.owner)
            # /api/x/C2026-001 처럼 값이 들어간 관측 경로는 정적 분석의 /api/x/{id} 로 합친다
            tmpl = next((a for a in m.apis.values() if a["method"] == ob.method and "{" in a["path"]
                         and path_match(a["path"], ob.path)), None)
            path = tmpl["path"] if tmpl else ob.path
            akey = api_key(ob.method, path)
            api = m.add_api(akey, ob.method, path)
            if ob.params:
                api["observedParams"] = sorted(set(api.get("observedParams", [])) | set(ob.params))
            fns = _reachable_fns(m, own)
            hit = next((e for f in fns for e in m.edges_from(f, "request") if e["to"] == akey), None)
            ev = f"관측: {'화면 로드' if ob.owner is None else own.split('#')[-1] + ' 클릭'}"
            if hit:
                m.add_edge(hit["from"], akey, "request", source="dynamic", confidence=1.0, evidence=ev)
            else:
                m.add_edge(fns[0] if fns else own, akey, "request", source="dynamic", confidence=1.0, evidence=ev)
                if not m.edges_from(akey, "handles"):
                    routes = [r for r in ctx.route_for(path) if r.kind == "api"]
                    same = [r for r in routes if r.methods and ob.method in r.methods]
                    for r in same or [r for r in routes if not r.methods]:
                        m.add_edge(akey, r.handler_key, "handles", confidence=0.99)
        # 관측된 화면 이동(팝업)
        for nv in sc.navigations:
            own = owner(nv.owner)
            path = ctx.normalize_url(urlparse(nv.url).path or nv.url)
            target = nv.url if nv.url in m.screens else ctx.screen_by_url.get(path)     # 데스크톱 캡처는 화면 키로 기록
            if not target:
                mm = re.search(r"/(\w+)\.do$", path)
                cand = f"scr:{ctx.config.sys_code}:{mm.group(1)}" if mm else None
                target = cand if cand in m.screens else None
            if not target:
                continue
            fns = _reachable_fns(m, own)
            hit = next((e for f in fns for e in m.edges_from(f, "navigates") if e["to"] == target), None)
            if hit:
                m.add_edge(hit["from"], target, "navigates", source="dynamic", confidence=1.0, evidence="관측: 새 창")
            else:
                m.add_edge(fns[0] if fns else own, target, "navigates", source="dynamic", confidence=1.0, evidence="관측: 새 창")
            m.screens[target]["kind"] = "popup"
        for b in sc.blocked:
            m.blocked.append({"elementKey": owner(b.owner), "method": b.method, "url": b.url, "at": b.at})

# ---------------------------------------------------------------- 추정 관계


def infer_relations(ctx: Context) -> None:
    """FK 없는 레거시: SQL 조인 조건 빈도로 테이블 관계 추정 (docs/04 §추정 관계)."""
    m, cfg = ctx.model, ctx.config
    min_ev = int(cfg.get("inference.minEvidence", 3))
    pk = ctx.table_pk
    fk_pairs = {(r["from"], r["to"], tuple(map(tuple, r["columns"]))) for r in m.relations if r["kind"] == "fk"}
    evidence: dict[tuple, set[str]] = defaultdict(set)
    for sql_key, (lt, lc), (rt, rc) in ctx.join_evidence:
        if (lt, lc) == (rt, rc):
            continue
        l_pk, r_pk = lc in pk.get(lt, []), rc in pk.get(rt, [])
        if r_pk and not l_pk:
            child, parent = (lt, lc), (rt, rc)
        elif l_pk and not r_pk:
            child, parent = (rt, rc), (lt, lc)
        elif l_pk and r_pk:
            # 둘 다 PK 구성 컬럼: 단일 PK 쪽을 부모로
            if len(pk.get(rt, [])) == 1 and len(pk.get(lt, [])) != 1:
                child, parent = (lt, lc), (rt, rc)
            elif len(pk.get(lt, [])) == 1 and len(pk.get(rt, [])) != 1:
                child, parent = (rt, rc), (lt, lc)
            else:
                continue
        else:
            continue
        evidence[(child, parent)].add(sql_key)
    for (child, parent), sqls in sorted(evidence.items()):
        ck, pk_ = tbl_key(cfg.schema, child[0]), tbl_key(cfg.schema, parent[0])
        cols = ((child[1], parent[1]),)
        if (ck, pk_, cols) in fk_pairs or len(sqls) < min_ev:
            continue
        conf = 0.5 + 0.1 * min(len(sqls), 3)
        if child[1] == parent[1]:
            conf += 0.1
        if len(pk.get(parent[0], [])) == 1:
            conf += 0.05
        m.relations.append({"from": ck, "to": pk_, "columns": [[child[1], parent[1]]], "kind": "inferred",
                            "evidenceCount": len(sqls), "confidence": round(min(conf, 0.95), 2)})

# ---------------------------------------------------------------- 통계


def compute_stats(m, duration: float) -> dict[str, float]:
    els = list(m.elements.values())
    buttons = [e for e in els if e["type"] in ("button", "popup", "link")]
    with_handler = [b for b in buttons if m.edges_from(b["key"], "event")]

    def reaches_api(key: str) -> bool:
        return any(m.edges_from(f, "request") for f in _reachable_fns(m, key, 6))

    out_edges: dict[str, list[str]] = defaultdict(list)
    for e in m.edges.values():
        if e["kind"] != "navigates":
            out_edges[e["from"]].append(e["to"])

    def reaches_data(key: str, depth: int = 10) -> bool:
        """버튼 → … → SQL·프로시저·테이블 도달 (API 를 거치지 않는 데스크톱·WebForms 포함)."""
        seen, frontier = {key}, [key]
        for _ in range(depth):
            nxt = []
            for f in frontier:
                for t in out_edges.get(f, []):
                    if t.startswith(("sql:", "sp:", "tbl:")):
                        return True
                    if t not in seen:
                        seen.add(t)
                        nxt.append(t)
            frontier = nxt
        return False

    edges = list(m.edges.values())
    return {
        "menus": len(m.menus),
        "screens": len(m.screens),
        "screensCaptured": sum(1 for s in m.screens.values() if any(c.get("state") != "wireframe" for c in s.get("captures", []))),
        "screensRendered": sum(1 for s in m.screens.values() if any(c.get("state") == "rendered" for c in s.get("captures", []))),
        "screensWireframe": sum(1 for s in m.screens.values()
                                if s.get("captures") and all(c.get("state") == "wireframe" for c in s["captures"])),
        "elements": len(els),
        "elementsMatchedFull": sum(1 for e in els if e["matched"] == "full"),
        "elementMatchRate": round(sum(1 for e in els if e["matched"] == "full") / len(els), 3) if els else 0,
        "buttons": len(buttons),
        "buttonsWithHandler": len(with_handler),
        "handlerLinkRate": round(len(with_handler) / len(buttons), 3) if buttons else 0,
        "buttonsReachingApi": sum(1 for b in with_handler if reaches_api(b["key"])),
        "buttonsReachingData": sum(1 for b in with_handler if reaches_data(b["key"])),
        "codeUnits": len(m.code_units),
        "apis": len(m.apis),
        "sqlStatements": len(m.sql),
        "tables": len(m.tables),
        "relationsFk": sum(1 for r in m.relations if r["kind"] == "fk"),
        "relationsInferred": sum(1 for r in m.relations if r["kind"] == "inferred"),
        "edges": len(edges),
        "edgesDynamic": sum(1 for e in edges if e["source"] == "dynamic"),
        "blockedRequests": len(m.blocked),
        "failures": len(m.failures),
        "warnings": len(getattr(m, "warnings", [])),
        **m.capture_stats,
        "durationSec": round(duration, 1),
    }
