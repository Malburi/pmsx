"""WebSquare(5/SP) 화면 파서 — docs/13 §9.

화면 XML (XHTML + xforms + w2 네임스페이스)
  · <xf:submission id ref target action method ev:submitdone> → 서브미션 정의
  · <body> 아래 컴포넌트: xf:trigger(버튼)·xf:input·xf:select1·w2:inputCalendar·w2:gridView·w2:tabControl·xf:group …
    style="position:absolute;left:10px;top:40px;width:…;height:…" 를 컨테이너(xf:group·w2:content) 기준으로 누적 → 절대 좌표
  · ev:onclick="scwin.btn_search_onclick" 등 이벤트 → 스크립트 함수(event 엣지), body ev:onpageload → onload
  · ref="data:dma_search.EMP_NM", gridView dataList="data:dlt_emp" → 바인딩
<script> 의 scwin.fn = function(){…} / function fn(){…}
  · $p.executeSubmission("sbm_x") (또는 설정 websquare.submissionCallers) → 서브미션 action 으로 request 엣지,
    ev:submitdone 콜백 → calls(조건 "콜백")
  · $p.ajax({action|url}) → request, $p.openPopup("/ui/…xml") (설정 frontNavigations) → navigates(팝업)
  · 공통 스크립트(websquare.commonScripts) 의 gcm.fn = function … 도 함수로 등록
런타임 없이 좌표로 와이어프레임 캡처를 만든다(실제 런타임은 웹이므로 Playwright 캡처도 가능).
"""
from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

from tree_sitter import Node

from ..keys import api_key, el_key, fn_key, scr_key
from . import Context
from .jsp_jquery import url_arg
from .ts import parse, string_value, text
from .ui_common import link_menus, link_server, refine_popup_types, screen_for_url

COMPONENTS = {
    "trigger": "button", "button": "button", "anchor": "link", "input": "input", "secret": "input", "textarea": "input",
    "select1": "combo", "select": "check", "autoComplete": "combo", "multiselect": "combo", "inputCalendar": "date",
    "calendar": "date", "gridView": "grid", "tabControl": "tab", "treeview": "tree", "checkbox": "check",
    "radio": "radio", "upload": "file", "fileUpload": "file", "wframe": "other", "editor": "input", "spinner": "input",
}
LABEL_TAGS = {"textbox", "span", "output"}
CONTAINERS = {"group", "content", "generator", "floatingLayer", "pageInherit"}
WIRE_KIND = {"button": "button", "link": "static", "input": "edit", "combo": "combo", "date": "calendar", "check": "check",
             "radio": "radio", "grid": "grid", "tree": "other", "file": "edit", "other": "other"}
EVENTS = {"onclick": "click", "ondblclick": "dblclick", "onchange": "change", "onviewchange": "change",
          "oncellclick": "click", "oncelldblclick": "dblclick", "onrowindexchange": "change", "onkeyup": "keyup",
          "onkeydown": "keydown", "onblur": "blur", "onfocus": "focus", "onitemclick": "click", "onpageload": "onload"}
DEFAULT_SUBMISSION_CALLERS = {"$p.executeSubmission": 0, "$c.sbm.execute": 0, "com.sbm.execute": 0, "gcm.executeSubmission": 0}
DEFAULT_NAVS = {"$p.openPopup": {"urlArg": 0, "popup": True}, "$c.win.openPopup": {"urlArg": 0, "popup": True},
                "com.win.openPopup": {"urlArg": 0, "popup": True}, "$p.url": {"urlArg": 0, "popup": False}}
CONFIRMS = {"confirm", "$p.confirm", "$c.win.confirm", "com.win.confirm", "gcm.confirm"}
FN_TYPES = ("function_expression", "arrow_function", "function", "generator_function")
TAB_HEADER = 26


def local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def attrs(el: ET.Element) -> dict[str, str]:
    """네임스페이스 접두어를 뗀 속성 (ev:onclick → onclick)."""
    return {local(k): v for k, v in el.attrib.items()}


def css_box(style: str | None, pw: float, ph: float) -> tuple[float, float, float, float] | None:
    if not style:
        return None
    st = {k.strip().lower(): v.strip() for k, v in (p.split(":", 1) for p in style.split(";") if ":" in p)}
    if st.get("position") not in ("absolute",):
        return None

    def num(key: str, base: float) -> float | None:
        v = st.get(key)
        if not v:
            return None
        try:
            if v.endswith("%"):
                return base * float(v[:-1]) / 100
            return float(re.sub(r"px$", "", v))
        except ValueError:
            return None

    l, t, w, h = num("left", pw), num("top", ph), num("width", pw), num("height", ph)
    if l is None or t is None:
        return None
    return l, t, w if w is not None else 100.0, h if h is not None else 24.0


@dataclass
class WsFn:
    key: str
    name: str
    node: Node
    line: int


@dataclass
class WsScript:
    file: str
    lang: str
    src: str
    offset: int
    common: bool
    functions: dict[str, WsFn] = field(default_factory=dict)
    tree: object = None

    def load(self) -> "WsScript":
        self.tree = parse("js", self.src)
        for n in self.tree.root_node.named_children:
            if n.type == "function_declaration":
                self._add(text(n.child_by_field_name("name")), n)
            elif n.type == "expression_statement" and n.named_children and n.named_children[0].type == "assignment_expression":
                a = n.named_children[0]
                val = a.child_by_field_name("right")
                name = text(a.child_by_field_name("left"))
                if val is not None and val.type in FN_TYPES and re.fullmatch(r"[\w$]+(\.[\w$]+)*", name):
                    self._add(name, val)
            elif n.type in ("lexical_declaration", "variable_declaration"):
                for d in n.named_children:
                    v = d.child_by_field_name("value") if d.type == "variable_declarator" else None
                    if v is not None and v.type in FN_TYPES:
                        self._add(text(d.child_by_field_name("name")), v)
        return self

    def _add(self, name: str, node: Node) -> None:
        if name and name not in self.functions:
            self.functions[name] = WsFn(fn_key(self.lang, self.file, name), name, node, node.start_point[0] + 1 + self.offset)

    def line(self, node: Node) -> int:
        return node.start_point[0] + 1 + self.offset


class WebSquareParser:
    name = "websquare"
    layer = "ui"

    def detect(self, root: Path) -> float:
        return 1.0 if any("inswave.com/websquare" in p.read_text(encoding="utf-8", errors="ignore")[:800]
                          for p in root.rglob("*.xml")) else 0.0

    def parse(self, ctx: Context) -> None:
        cfg, m = ctx.config, ctx.model
        self.ctx = ctx
        self.root = cfg.source_dir("websquare")
        if not self.root or not self.root.is_dir():
            m.fail("parse", "source.websquare", "WebSquare 화면 경로 없음")
            return
        self.prefix = "/" + (cfg.get("websquare.urlPrefix", "") or "").strip("/")
        self.sub_callers = dict(DEFAULT_SUBMISSION_CALLERS)
        self.sub_callers.update(cfg.get("websquare.submissionCallers", {}) or {})
        self.fapis: dict = cfg.get("frontApis", {}) or {}
        self.fnavs: dict = dict(DEFAULT_NAVS)
        self.fnavs.update(cfg.get("frontNavigations", {}) or {})
        self.pending_nav: list[tuple[str, str, float, str, bool]] = []
        # 공통 스크립트
        self.common_scope: dict[str, WsFn] = {}
        self.scope: dict[str, WsFn] = {}
        self.subs: dict[str, dict] = {}
        for rel in cfg.get("websquare.commonScripts", []) or []:
            f = self.root / rel.lstrip("/")
            if not f.is_file():
                m.fail("parse", rel, "공통 스크립트 파일 없음")
                continue
            sc = WsScript(cfg.rel(f), "js", f.read_text(encoding="utf-8"), 0, True).load()
            self.common_scope.update(sc.functions)
            self.scope, self.skey = dict(self.common_scope), None
            for fn in sc.functions.values():
                self._register(sc, fn)
                self._scan(sc, fn.node, fn.key)
        # 화면 (URL 먼저 등록 → 팝업 대상 해석)
        docs: list[tuple[Path, ET.Element, str]] = []
        for f in sorted(self.root.rglob("*.xml")):
            raw = f.read_text(encoding="utf-8", errors="ignore")
            if "inswave.com/websquare" not in raw[:2000]:
                continue
            try:
                doc = ET.fromstring(f.read_bytes())
            except ET.ParseError as e:
                m.fail("parse", cfg.rel(f), f"WebSquare XML 파싱 실패: {e}")
                continue
            skey = scr_key(cfg.sys_code, f.stem)
            ctx.screen_by_url[self.url_of(f)] = skey
            docs.append((f, doc, skey))
        for f, doc, skey in docs:
            self._screen(f, doc, skey)
        self._resolve_navigation()
        link_menus(ctx)
        link_server(ctx)
        refine_popup_types(ctx)

    def url_of(self, f: Path) -> str:
        rel = f.relative_to(self.root).as_posix()
        return (self.prefix.rstrip("/") + "/" + rel) if self.prefix != "/" else "/" + rel

    # ---------- 화면 ----------
    def _screen(self, f: Path, doc: ET.Element, skey: str) -> None:
        ctx, cfg, m = self.ctx, self.ctx.config, self.ctx.model
        head = next((c for c in doc if local(c.tag) == "head"), None)
        body = next((c for c in doc if local(c.tag) == "body"), None)
        sid = f.stem
        title = None
        if head is not None:
            t = next((c for c in head if local(c.tag) == "title"), None)
            title = (t.text or "").strip() if t is not None else None
        # 서브미션
        self.subs = {}
        for sub in (doc.iter() if head is None else head.iter()):
            if local(sub.tag) == "submission":
                a = attrs(sub)
                self.subs[a.get("id", "")] = a
        # 스크립트
        sc = None
        raw = f.read_text(encoding="utf-8")
        for s in (head.iter() if head is not None else []):
            if local(s.tag) == "script" and (s.text or "").strip() and not s.get("src"):
                code = s.text.lstrip("\n")
                start = raw.find(code[:40])
                sc = WsScript(cfg.rel(f), "xml", code, raw.count("\n", 0, start) if start >= 0 else 0, False).load()
                break
        self.scope = dict(self.common_scope)
        if sc:
            self.scope.update(sc.functions)
        self.skey = skey
        if sc:
            for fn in sc.functions.values():
                self._register(sc, fn)
                self._scan(sc, fn.node, fn.key)

        comps: list[dict] = []
        statics: list[dict] = []
        wire: list[dict] = []
        W, H = float(cfg.get("websquare.width", 1280)), float(cfg.get("websquare.height", 720))
        if body is not None:
            self._walk(body, "", 0.0, 0.0, W, H, comps, statics, wire, True)
        if not title:
            t = next((s["text"] for s in statics if "tit" in s.get("cls", "")), None)
            title = t or sid
        H2 = max([0.0] + [c["box"][1] + c["box"][3] + 10 for c in comps + statics if c.get("box")])
        W2 = max([0.0] + [c["box"][0] + c["box"][2] + 10 for c in comps + statics if c.get("box")])
        m.screens[skey] = {"key": skey, "screenId": sid, "name": title,
                           "kind": "popup" if re.search(r"P\d{2}$", sid) else "page", "uiTech": "websquare",
                           "sourceFile": cfg.rel(f), "url": self.url_of(f), "captures": []}

        for seq, c in enumerate(comps, 1):
            ekey = el_key(skey, c["path"])
            label = c["label"] or self._label_for(c, statics) or c["id"]
            entry = {"key": ekey, "screenKey": skey, "elementId": c["path"], "label": label, "type": c["type"],
                     "matched": "static-only", "_seq": seq, "_tag": c["tag"]}
            if c.get("box"):
                x, y, w, h = c["box"]
                entry["box"] = {"x": round(x, 1), "y": round(y, 1), "w": round(w, 1), "h": round(h, 1), "captureIndex": 0}
            if c.get("binding"):
                entry["binding"] = c["binding"]
            m.elements[ekey] = entry
            for ev, handler in c["events"].items():
                fn = self._handler(handler)
                if fn:
                    m.add_edge(ekey, fn.key, "event", event=EVENTS.get(ev, ev[2:]), confidence=0.99, evidence=cfg.rel(f))
                else:
                    m.fail("parse", ekey, f"이벤트 핸들러 '{handler}' 정의를 찾지 못함 ({ev})")
        # onload: body ev:onpageload 또는 scwin.onpageload 관례
        onload = None
        if body is not None and attrs(body).get("onpageload"):
            onload = self._handler(attrs(body)["onpageload"])
        onload = onload or (sc.functions.get("scwin.onpageload") if sc else None)
        if onload:
            m.add_edge(skey, onload.key, "event", event="onload", confidence=0.99, evidence=cfg.rel(f))
            ctx.screen_onload.setdefault(skey, []).append(onload.key)
        if cfg.get("websquare.wireframe", True) and wire:
            ctx.wireframes[skey] = {"width": max(W2, 400.0), "height": max(H2, 300.0), "title": title, "items": wire}

    def _handler(self, value: str) -> WsFn | None:
        mm = re.match(r"\s*([\w$.]+)\s*(\(.*\))?\s*;?\s*$", value or "", re.S)
        return self.scope.get(mm.group(1)) if mm else None

    def _walk(self, node: ET.Element, prefix: str, ox: float, oy: float, pw: float, ph: float,
              comps: list, statics: list, wire: list, visible: bool) -> None:
        for el in node:
            tag = local(el.tag)
            a = attrs(el)
            box = css_box(a.get("style"), pw, ph)
            abs_box = (ox + box[0], oy + box[1], box[2], box[3]) if box else None
            cid = a.get("id", tag)
            if tag in LABEL_TAGS:
                txt = a.get("label") or a.get("value") or (el.text or "").strip()
                if abs_box:
                    statics.append({"id": cid, "text": txt, "box": abs_box, "cls": a.get("class", "")})
                    if visible:
                        wire.append({"kind": "title" if "tit" in a.get("class", "") else "static", "box": list(abs_box), "text": txt})
                continue
            if tag in CONTAINERS:
                if abs_box and visible and tag == "group":
                    wire.append({"kind": "div", "box": list(abs_box)})
                nx, ny, nw, nh = (abs_box if abs_box else (ox, oy, pw, ph))
                self._walk(el, prefix, nx, ny, nw, nh, comps, statics, wire, visible)
                continue
            typ = COMPONENTS.get(tag)
            if typ is None:
                if len(el):
                    self._walk(el, prefix, ox, oy, pw, ph, comps, statics, wire, visible)
                continue
            if tag == "select1" and a.get("appearance") == "full":
                typ = "radio"
            events = {k: v for k, v in a.items() if k.startswith("on") and v}
            comp = {"id": cid, "path": f"{prefix}{cid}", "tag": tag, "type": typ, "box": abs_box, "events": events,
                    "label": "", "binding": None}
            ref = a.get("ref") or ""
            if ref.startswith("data:"):
                comp["binding"] = ref[5:]
            if typ == "button" or typ == "link":
                lab = next((c for c in el if local(c.tag) == "label"), None)
                comp["label"] = ((lab.text or "") if lab is not None else (a.get("label") or a.get("value") or el.text or "")).strip()
            if tag == "gridView":
                dl = (a.get("dataList") or "").replace("data:", "")
                heads, cols = [], []
                for part in el:
                    pt = local(part.tag)
                    for col in part.iter():
                        if local(col.tag) != "column":
                            continue
                        ca = attrs(col)
                        if pt == "header":
                            heads.append((ca.get("value") or "", float(ca.get("width") or 80)))
                        elif pt == "gridBody":
                            cols.append(ca.get("id", ""))
                comp["binding"] = f"{dl}: {', '.join(cols)}" if cols else dl or None
                if abs_box and visible:
                    wire.append({"kind": "grid", "box": list(abs_box), "cols": heads})
                comps.append(comp)
                continue
            if tag == "tabControl":
                tabs = [attrs(t).get("label", attrs(t).get("id", "")) for t in el if local(t.tag) == "tabs"]
                comp["label"] = "/".join(tabs)
                if abs_box and visible:
                    wire.append({"kind": "tab", "box": list(abs_box), "tabs": tabs})
                comps.append(comp)
                contents = [c for c in el if local(c.tag) == "content"]
                for i, content in enumerate(contents):
                    cbox = css_box(attrs(content).get("style"), box[2] if box else pw, box[3] if box else ph)
                    bx = abs_box[0] if abs_box else ox
                    by = (abs_box[1] if abs_box else oy) + TAB_HEADER
                    if cbox:
                        bx, by = bx + cbox[0], (abs_box[1] if abs_box else oy) + cbox[1]
                    # WebSquare id 는 화면 안에서 유일 — 탭 콘텐츠 안 요소도 접두어 없이 id 그대로
                    self._walk(content, prefix, bx, by, box[2] if box else pw, (box[3] if box else ph) - TAB_HEADER,
                               comps, statics, wire, visible and i == 0)
                continue
            if abs_box and visible:
                wire.append({"kind": WIRE_KIND.get(typ, "other"), "box": list(abs_box),
                             "text": comp["label"] if typ in ("button", "check", "radio") else (a.get("placeholder") or "")})
            comps.append(comp)

    @staticmethod
    def _label_for(c: dict, statics: list[dict]) -> str | None:
        if not c.get("box"):
            return None
        x, y, w, h = c["box"]
        best, dist = None, 1e9
        for s in statics:
            sx, sy, sw, sh = s["box"]
            if not s["text"]:
                continue
            if c["type"] in ("grid", "tab"):
                if sy + sh <= y + 2 and y - (sy + sh) < 45 and sx < x + w and sx + sw > x and y - (sy + sh) < dist:
                    best, dist = s["text"], y - (sy + sh)
            elif abs((sy + sh / 2) - (y + h / 2)) <= 8 and sx + sw <= x + 6 and x - (sx + sw) < 200 and x - (sx + sw) < dist:
                best, dist = s["text"], x - (sx + sw)
        return best

    # ---------- 스크립트 ----------
    def _register(self, sc: WsScript, fn: WsFn) -> None:
        node = fn.node
        snippet = None
        if self.ctx.config.include_snippets:
            snippet = "\n".join(sc.src.splitlines()[node.start_point[0]: node.end_point[0] + 1])
        self.ctx.model.add_code_unit(fn.key, sc.lang, sc.file, fn.name, fn.line, node.end_point[0] + 1 + sc.offset,
                                     snippet, sc.common)

    def _scan(self, sc: WsScript, node: Node, fkey: str, state: dict | None = None) -> None:
        state = state if state is not None else {"confirm": None}
        for child in node.children:
            if child.type == "function_declaration":
                continue
            if child.type == "call_expression":
                self._call(sc, child, fkey, state)
            self._scan(sc, child, fkey, state)

    def _call(self, sc: WsScript, call: Node, fkey: str, state: dict) -> None:
        m = self.ctx.model
        callee = call.child_by_field_name("function")
        name = re.sub(r"^(this|window)\.", "", text(callee))
        an = call.child_by_field_name("arguments")
        args = an.named_children if an else []
        ev = f"{sc.file}:{sc.line(call)}"
        cond = f'confirm("{state["confirm"]}")' if state.get("confirm") else None
        if name in CONFIRMS and args:
            state["confirm"] = string_value(args[0]) or text(args[0])
            return
        if name in self.sub_callers:
            i = int(self.sub_callers[name])
            sid = string_value(args[i]) if len(args) > i else None
            sub = self.subs.get(sid or "")
            if sub is None:
                if not sc.common:
                    m.fail("parse", fkey, f"서브미션 '{sid or text(args[i]) if len(args) > i else ''}' 정의를 찾지 못함 ({ev})")
                return
            self._request(fkey, (sub.get("method") or "post").upper(), sub.get("action", ""), 0.95, cond,
                          f"{ev} (submission {sid}: ref={sub.get('ref', '')} target={sub.get('target', '')})")
            cb = sub.get("submitdone")
            target = self._handler(cb) if cb else None
            if target and target.key != fkey:
                m.add_edge(fkey, target.key, "calls", confidence=0.9, condition="콜백", evidence=ev)
            return
        if name in ("$p.ajax", "$c.sbm.ajax") or name in self.fapis:
            if name in self.fapis:
                spec = self.fapis[name]
                url, conf = url_arg(args[spec.get("urlArg", 0)] if len(args) > spec.get("urlArg", 0) else None)
                method = spec.get("method", "POST")
            else:
                url, conf, method = None, 0.0, "POST"
                if args and args[0].type == "object":
                    for pair in args[0].named_children:
                        if pair.type != "pair":
                            continue
                        k = text(pair.child_by_field_name("key")).strip("'\"")
                        v = pair.child_by_field_name("value")
                        if k in ("action", "url"):
                            url, conf = url_arg(v)
                        elif k in ("method", "type") and string_value(v):
                            method = string_value(v).upper()
            if url:
                self._request(fkey, method, url, conf, cond, ev)
            elif not sc.common:
                m.fail("parse", fkey, f"{name} URL 을 해석하지 못함 ({ev})")
            return
        if name in self.fnavs:
            spec = self.fnavs[name]
            i = spec.get("urlArg", 0)
            url, conf = url_arg(args[i] if len(args) > i else None)
            if url:
                self.pending_nav.append((fkey, url, conf, ev, bool(spec.get("popup", True))))
            return
        target = self.scope.get(name)
        if target is not None and target.key != fkey:
            m.add_edge(fkey, target.key, "calls", confidence=0.95, condition=cond, evidence=ev)
        for a in args:          # 콜백으로 넘긴 함수 참조
            if a.type in ("identifier", "member_expression"):
                ref = self.scope.get(re.sub(r"^this\.", "", text(a)))
                if ref is not None and ref.key != fkey:
                    m.add_edge(fkey, ref.key, "calls", confidence=0.85, condition="콜백", evidence=ev)

    def _request(self, fkey: str, method: str, url: str, conf: float, cond: str | None, ev: str) -> None:
        if not url:
            return
        path = self.ctx.normalize_url(url)
        akey = api_key(method, path)
        self.ctx.model.add_api(akey, method, path)
        self.ctx.model.add_edge(fkey, akey, "request", confidence=conf, condition=cond, evidence=ev)

    def _resolve_navigation(self) -> None:
        m = self.ctx.model
        for fkey, url, conf, ev, popup in self.pending_nav:
            path = url.split("?", 1)[0]
            target = self.ctx.screen_by_url.get(path) or self.ctx.screen_by_url.get(self.ctx.normalize_url(path)) \
                or screen_for_url(self.ctx, url)
            if not target:
                mm = re.search(r"([\w-]+)\.xml$", path)
                cand = scr_key(self.ctx.config.sys_code, mm.group(1)) if mm else None
                target = cand if cand in m.screens else None
            if not target:
                m.fail("parse", fkey, f"{'팝업' if popup else '이동'} 대상 화면을 찾지 못함: {url} ({ev})")
                continue
            m.add_edge(fkey, target, "navigates", confidence=conf, evidence=ev)
            if popup:
                m.screens[target]["kind"] = "popup"
