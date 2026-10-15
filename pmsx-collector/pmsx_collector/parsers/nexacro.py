"""Nexacro(N / 14 / 17) 화면 파서 — docs/11 §7.

XFDL(Form)
  · Layout 좌표: left/top/width/height(+right/bottom, %) 또는 position="absolute l t r b" (Nexacro 14/XPlatform)
    Div·Tab(Tabpage) 같은 컨테이너의 오프셋을 누적해 화면 원점 기준 절대 좌표로 만든다
  · 컴포넌트 → UI 요소(타입 매핑), Static 은 요소가 아니라 라벨로 사용
  · 이벤트 속성(onclick·oncelldblclick·onitemchanged …) → 스크립트 함수(event 엣지), Form onload → 화면 onload
  · Dataset 바인딩: Grid binddataset(+Format body bind:컬럼), BindItem(컴포넌트 value ↔ Dataset 컬럼), Combo innerdataset
Script / 공통 xjs(include "Lib::x.xjs")
  · this.fn = function(…){} (N) / function fn(…){} (14) — 매개변수 타입 표기(obj:nexacro.Button)는 제거 후 구문 분석
  · transaction(svcId, "svc::url", inDs, outDs, args, callback) 및 래퍼(frontApis) → request 엣지, 콜백 함수 → calls
  · 팝업/메뉴 열기(frontNavigations) → navigates
런타임 없이 XFDL 좌표로 와이어프레임 PNG 명세를 만든다 → 파이프라인이 captures[state=wireframe] 로 렌더링.
"""
from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlparse

from tree_sitter import Node

from ..keys import api_key, el_key, fn_key, scr_key
from . import Context
from .jsp_jquery import url_arg
from .ts import parse, string_value, text
from .ui_common import link_menus, link_server, refine_popup_types, screen_for_url

COMPONENT_TYPES = {
    "Button": "button", "Edit": "input", "MaskEdit": "input", "TextArea": "input", "Spin": "input",
    "Combo": "combo", "ListBox": "combo", "MultiCombo": "combo", "Calendar": "date", "DateField": "date",
    "CheckBox": "check", "CheckBoxSet": "check", "Radio": "radio", "Grid": "grid", "Tab": "tab",
    "FileUpload": "file", "FileUpTransfer": "file", "ImageViewer": "other", "WebBrowser": "other",
    "Menu": "tree", "TreeView": "tree", "ListView": "grid", "PopupDiv": "other",
}
WIRE_KIND = {"button": "button", "input": "edit", "combo": "combo", "date": "calendar", "check": "check",
             "radio": "radio", "grid": "grid", "tab": "tab", "file": "edit", "tree": "other", "other": "other"}
EVENT_NAMES = {"onclick": "click", "oncellclick": "click", "oncelldblclick": "dblclick", "ondblclick": "dblclick",
               "onitemchanged": "change", "onchanged": "change", "oncloseup": "change", "onkeyup": "keyup",
               "onkeydown": "keydown", "onsetfocus": "focus", "onkillfocus": "blur", "oneditclick": "click",
               "onheadclick": "click", "onrbuttondown": "contextmenu", "onload": "onload"}
FN_TYPES = ("function_expression", "arrow_function", "function", "generator_function")
TAB_HEADER = 26


# ---------------------------------------------------------------- 스크립트 전처리
def _blank(m: re.Match) -> str:
    return re.sub(r"[^\n]", " ", m.group(0))


def preprocess_script(src: str) -> tuple[str, list[str]]:
    """include 문 추출·제거, 매개변수/변수 타입 표기 제거 — 줄·열 위치는 유지."""
    includes = re.findall(r'^\s*#?include\s+"([^"]+)"\s*;?', src, re.M)
    src = re.sub(r'^\s*#?include\s+"[^"]+"\s*;?', _blank, src, flags=re.M)

    def strip_params(m: re.Match) -> str:
        inner = re.sub(r"(\b[\w$]+)\s*:\s*[\w$.]+", lambda x: x.group(1) + " " * (len(x.group(0)) - len(x.group(1))), m.group(2))
        return m.group(1) + inner + ")"

    src = re.sub(r"(\bfunction\b\s*[\w$]*\s*\()([^)]*)\)", strip_params, src)
    # var a:Number = 1;  →  var a        = 1;
    src = re.sub(r"(\bvar\s+[\w$]+)(\s*:\s*[\w$.]+)", lambda m: m.group(1) + " " * len(m.group(2)), src)
    return src, includes


@dataclass
class NxFn:
    key: str
    name: str
    node: Node
    line: int


@dataclass
class NxScript:
    file: str             # 프로젝트 기준 경로 (PY/PY0101.xfdl, Lib/lib_comm.xjs)
    lang: str             # xfdl | js(공통 xjs)
    src: str
    offset: int           # 파일 안에서 스크립트 시작 줄 - 1
    common: bool
    includes: list[str] = field(default_factory=list)
    functions: dict[str, NxFn] = field(default_factory=dict)
    tree: object = None

    def load(self) -> "NxScript":
        self.src, self.includes = preprocess_script(self.src)
        self.tree = parse("js", self.src)
        for n in self.tree.root_node.named_children:
            if n.type == "function_declaration":
                self._add(text(n.child_by_field_name("name")), n)
            elif n.type == "expression_statement" and n.named_children and n.named_children[0].type == "assignment_expression":
                a = n.named_children[0]
                left = text(a.child_by_field_name("left"))
                val = a.child_by_field_name("right")
                name = re.sub(r"^(this|window)\.", "", left)
                if val is not None and val.type in FN_TYPES and re.fullmatch(r"[\w$]+", name):
                    self._add(name, val)
            elif n.type in ("lexical_declaration", "variable_declaration"):
                for d in n.named_children:
                    v = d.child_by_field_name("value") if d.type == "variable_declarator" else None
                    if v is not None and v.type in FN_TYPES:
                        self._add(text(d.child_by_field_name("name")), v)
        return self

    def _add(self, name: str, node: Node):
        if name and name not in self.functions:
            self.functions[name] = NxFn(fn_key(self.lang, self.file, name), name, node, node.start_point[0] + 1 + self.offset)

    def line(self, node: Node) -> int:
        return node.start_point[0] + 1 + self.offset


# ---------------------------------------------------------------- 좌표
def _num(v: str | None, base: float) -> float | None:
    if v is None or v == "":
        return None
    v = v.strip()
    try:
        if v.endswith("%"):
            return base * float(v[:-1]) / 100
        return float(re.sub(r"px$", "", v))
    except ValueError:
        return None       # 다른 컴포넌트 기준 좌표("btn_a:10") 등은 해석 안 함


def comp_box(el: ET.Element, pw: float, ph: float) -> tuple[float, float, float, float] | None:
    """부모 기준 (x, y, w, h)."""
    pos = el.get("position")
    if pos:
        mm = re.match(r"\s*absolute\s+(-?[\d.]+)\s+(-?[\d.]+)\s+(-?[\d.]+)\s+(-?[\d.]+)", pos)
        if mm:
            l, t, r, b = map(float, mm.groups())
            return l, t, r - l, b - t
    left, top = _num(el.get("left"), pw), _num(el.get("top"), ph)
    right, bottom = _num(el.get("right"), pw), _num(el.get("bottom"), ph)
    width, height = _num(el.get("width"), pw), _num(el.get("height"), ph)
    if width is None and left is not None and right is not None:
        width = pw - left - right
    if left is None and right is not None and width is not None:
        left = pw - right - width
    if height is None and top is not None and bottom is not None:
        height = ph - top - bottom
    if top is None and bottom is not None and height is not None:
        top = ph - bottom - height
    if None in (left, top, width, height):
        return None
    return left, top, width, height


def _layout(node: ET.Element) -> ET.Element | None:
    lays = node.find("Layouts")
    if lays is not None:
        first = lays.find("Layout")
        if first is not None:
            return first
    return None


# ---------------------------------------------------------------- 파서
class NexacroParser:
    name = "nexacro"
    layer = "ui"
    source_key = "nexacro"            # source.<key> = 프로젝트 루트
    cfg_ns = "nexacro"                # <ns>.services / <ns>.serviceUrls / <ns>.wireframe
    tx_names = ("transaction",)       # 기본 트랜잭션 함수 (MiPlatform: Transaction)

    def detect(self, root: Path) -> float:
        return 1.0 if any(root.rglob("*.xfdl")) else 0.0

    def parse(self, ctx: Context) -> None:
        cfg, m = ctx.config, ctx.model
        self.ctx = ctx
        self.root = cfg.source_dir(self.source_key)
        if not self.root or not self.root.is_dir():
            m.fail("parse", f"source.{self.source_key}", f"{self.name} 프로젝트 경로 없음")
            return
        self.services, self.service_urls = self._services()
        self.fapis: dict = cfg.get("frontApis", {}) or {}
        self.fnavs: dict = cfg.get("frontNavigations", {}) or {}
        self.common: dict[str, NxScript] = {}
        self.common_scope: dict[str, NxFn] = {}
        self.pending_nav: list[tuple[str, str, float, str, bool]] = []
        # 화면 URL(폼 URL)을 먼저 등록해야 팝업 대상을 해석할 수 있다
        self.forms: list[tuple[Path, ET.Element, str]] = []
        for f, form in self.load_forms():
            sid = form.get("id") or f.stem
            skey = scr_key(cfg.sys_code, sid)
            ctx.screen_by_url[self.form_url(f)] = skey
            self.forms.append((f, form, skey))
        for f, form, skey in self.forms:
            self._form(f, form, skey)
        self._resolve_navigation()
        link_menus(ctx)
        link_server(ctx)
        refine_popup_types(ctx)

    def load_forms(self) -> list[tuple[Path, ET.Element]]:
        """화면 파일 → Nexacro N 모양의 Form 요소 (XPlatform·MiPlatform 파서는 정규화해서 돌려준다)."""
        out = []
        for f in sorted(self.root.rglob("*.xfdl")):
            try:
                doc = ET.fromstring(f.read_bytes())
            except ET.ParseError as e:
                self.ctx.model.fail("parse", self.ctx.config.rel(f), f"XFDL 파싱 실패: {e}")
                continue
            form = doc.find("Form") if doc.tag == "FDL" else (doc if doc.tag == "Form" else None)
            if form is not None:
                out.append((f, form))
        return out

    def ui_tech(self, f: Path) -> str:
        return "nexacro"

    def script_lang(self, f: Path) -> str:
        return "xfdl"

    def script_text(self, f: Path, script_el: ET.Element) -> tuple[str, int]:
        """(스크립트, 파일 안 시작 줄 - 1)."""
        code = (script_el.text or "").lstrip("\n")
        raw = f.read_text(encoding="utf-8")
        start = raw.find(code[:40])
        return code, (raw.count("\n", 0, start) if start >= 0 else 0)

    # ---------- 프로젝트 설정 ----------
    def _services(self) -> tuple[dict[str, str], dict[str, str]]:
        """TypeDefinition 의 Services → (폼/스크립트 접두어 → 폴더, 서비스 URL 접두어 → 서버 경로). 설정이 우선."""
        forms: dict[str, str] = {}
        urls: dict[str, str] = {}
        for td in [*self.root.glob("TypeDefinition.xml"), *self.root.glob("typedefinition.xml"), *self.root.glob("default_typedef.xml")]:
            try:
                doc = ET.fromstring(td.read_bytes())
            except ET.ParseError:
                continue
            for s in doc.iter("Service"):
                pid, typ, url = s.get("prefixid"), (s.get("type") or "").lower(), s.get("url") or ""
                if not pid:
                    continue
                if typ in ("form", "js", "css", "file") or url.startswith("./"):
                    forms[pid] = url.lstrip("./").rstrip("/") + "/"
                else:
                    urls[pid] = urlparse(url).path or "/"
        forms.update(self.ctx.config.get(f"{self.cfg_ns}.services", {}) or {})
        urls.update(self.ctx.config.get(f"{self.cfg_ns}.serviceUrls", {}) or {})
        return forms, urls

    def form_url(self, f: Path) -> str:
        """파일 → 폼 URL (PY::PY0101.xfdl). 서비스 폴더에 없으면 프로젝트 상대 경로."""
        rel = f.relative_to(self.root).as_posix()
        for pid, folder in sorted(self.services.items(), key=lambda kv: -len(kv[1])):
            folder = folder.strip("/")
            if folder and rel.startswith(folder + "/"):
                return f"{pid}::{rel[len(folder) + 1:]}"
        return rel

    def resolve_service_file(self, url: str) -> Path | None:
        if "::" in url:
            pid, rest = url.split("::", 1)
            folder = self.services.get(pid, pid + "/").strip("/")
            p = self.root / folder / rest
        else:
            p = self.root / url
        return p if p.is_file() else None

    def service_path(self, url: str) -> str:
        """transaction URL "svc::py/calc/selectList.do" → 서버 경로 "/py/calc/selectList.do"."""
        if "::" in url:
            pid, rest = url.split("::", 1)
            base = self.service_urls.get(pid, "/")
            url = base.rstrip("/") + "/" + rest.lstrip("/")
        return self.ctx.normalize_url(url)

    # ---------- 공통 스크립트 ----------
    def _include(self, inc: str) -> None:
        if inc in self.common:
            return
        f = self.resolve_service_file(inc)
        m = self.ctx.model
        if f is None:
            m.fail("parse", inc, f"include 대상 스크립트 없음: {inc}")
            self.common[inc] = None   # type: ignore[assignment]
            return
        sc = NxScript(f.relative_to(self.root).as_posix(), "js", f.read_text(encoding="utf-8"), 0, True).load()
        self.common[inc] = sc
        for sub in sc.includes:
            self._include(sub)
        self.common_scope.update(sc.functions)
        saved = getattr(self, "scope", None)
        self.scope, self.skey = dict(self.common_scope), None
        for fn in sc.functions.values():
            self._register(sc, fn)
            self._scan(sc, fn.node, fn.key)
        self.scope = saved

    def _register(self, sc: NxScript, fn: NxFn) -> None:
        node = fn.node
        snippet = None
        if self.ctx.config.include_snippets:
            snippet = "\n".join(sc.src.splitlines()[node.start_point[0]: node.end_point[0] + 1])
        self.ctx.model.add_code_unit(fn.key, sc.lang, sc.file, fn.name, fn.line, node.end_point[0] + 1 + sc.offset,
                                     snippet, sc.common)

    # ---------- 화면 ----------
    def _form(self, f: Path, form: ET.Element, skey: str) -> None:
        ctx, cfg, m = self.ctx, self.ctx.config, self.ctx.model
        rel = f.relative_to(self.root).as_posix()
        sid = skey.rsplit(":", 1)[-1]
        fw, fh = self._form_size(form)
        title = form.get("titletext") or sid
        m.screens[skey] = {"key": skey, "screenId": sid, "name": title,
                           "kind": "popup" if re.search(r"P\d{2}$", sid) else "page",
                           "uiTech": self.ui_tech(f), "sourceFile": cfg.rel(f), "url": self.form_url(f), "captures": []}

        # Dataset 정의·바인딩
        datasets: dict[str, list[str]] = {}
        objs = form.find("Objects")
        for ds in (objs.findall("Dataset") if objs is not None else []):
            datasets[ds.get("id", "")] = [c.get("id", "") for c in ds.iter("Column")]
        binds: dict[str, str] = {}
        bind = form.find("Bind")
        for bi in (bind.findall("BindItem") if bind is not None else []):
            comp = (bi.get("compid") or "").replace(".form.", ".")
            if bi.get("propid") in ("value", "text") and bi.get("datasetid"):
                binds[comp] = f"{bi.get('datasetid')}.{bi.get('columnid')}"

        # 컴포넌트 (좌표 누적)
        comps: list[dict] = []
        statics: list[dict] = []
        wire: list[dict] = []
        lay = _layout(form)
        if lay is not None:
            self._walk(lay, "", 0.0, 0.0, fw, fh, comps, statics, wire)
        H = max([fh] + [c["box"][1] + c["box"][3] + 10 for c in comps + statics])
        W = max([fw] + [c["box"][0] + c["box"][2] + 10 for c in comps + statics])

        # 스크립트
        script_el = form.find("Script")
        sc = None
        if script_el is not None and (script_el.text or "").strip():
            code, offset = self.script_text(f, script_el)
            sc = NxScript(rel, self.script_lang(f), code, offset, False).load()
            for inc in sc.includes:
                self._include(inc)
        self.scope = dict(self.common_scope)
        if sc:
            self.scope.update(sc.functions)
        self.skey = skey
        if sc:
            for fn in sc.functions.values():
                self._register(sc, fn)
                self._scan(sc, fn.node, fn.key)

        # 요소
        for seq, c in enumerate(comps, 1):
            eid = c["path"]
            ekey = el_key(skey, eid)
            label = c["text"] or self._label_for(c, statics) or c["id"]
            entry = {"key": ekey, "screenKey": skey, "elementId": eid, "label": label, "type": c["type"],
                     "matched": "static-only", "_seq": seq, "_tag": c["tag"],
                     "box": {"x": round(c["box"][0], 1), "y": round(c["box"][1], 1), "w": round(c["box"][2], 1),
                             "h": round(c["box"][3], 1), "captureIndex": 0}}
            binding = None
            if c.get("binddataset"):
                cols = c.get("cols") or datasets.get(c["binddataset"], [])
                binding = f"{c['binddataset']}: {', '.join(cols)}" if cols else c["binddataset"]
            elif eid in binds:
                binding = binds[eid]
            if c.get("innerdataset"):
                binding = (binding + " · " if binding else "") + f"코드 {c['innerdataset']}"
            if binding:
                entry["binding"] = binding
            m.elements[ekey] = entry
            for attr, handler in c["events"].items():
                fn = self.scope.get(handler)
                if fn:
                    m.add_edge(ekey, fn.key, "event", event=EVENT_NAMES.get(attr, attr[2:]), confidence=0.99,
                               evidence=f"{cfg.rel(f)}:{c['line']}" if c.get("line") else cfg.rel(f))
                else:
                    m.fail("parse", ekey, f"이벤트 핸들러 '{handler}' 정의를 찾지 못함 ({attr})")
        # Form 이벤트 (onload 등)
        for attr, handler in form.attrib.items():
            if attr.startswith("on") and handler:
                fn = self.scope.get(handler)
                if fn:
                    m.add_edge(skey, fn.key, "event", event=EVENT_NAMES.get(attr, attr[2:]), confidence=0.99,
                               evidence=cfg.rel(f))
                    if attr == "onload":
                        ctx.screen_onload.setdefault(skey, []).append(fn.key)

        if cfg.get(f"{self.cfg_ns}.wireframe", True):
            ctx.wireframes[skey] = {"width": W, "height": H, "title": title, "items": wire}

    @staticmethod
    def _form_size(form: ET.Element) -> tuple[float, float]:
        pos = form.get("position")
        if pos:
            mm = re.match(r"\s*absolute\s+(-?[\d.]+)\s+(-?[\d.]+)\s+(-?[\d.]+)\s+(-?[\d.]+)", pos)
            if mm:
                l, t, r, b = map(float, mm.groups())
                return r - l, b - t
        lay = _layout(form)
        w = _num(form.get("width"), 0) or (_num(lay.get("width"), 0) if lay is not None else None) or 1280
        h = _num(form.get("height"), 0) or (_num(lay.get("height"), 0) if lay is not None else None) or 720
        return w, h

    def _walk(self, layout: ET.Element, prefix: str, ox: float, oy: float, pw: float, ph: float,
              comps: list, statics: list, wire: list) -> None:
        for el in layout:
            tag = el.tag
            box = comp_box(el, pw, ph)
            if box is None:
                continue
            x, y, w, h = ox + box[0], oy + box[1], box[2], box[3]
            cid = el.get("id", tag)
            path = f"{prefix}{cid}"
            if tag == "Static":
                txt = el.get("text") or ""
                statics.append({"id": cid, "text": txt, "box": (x, y, w, h)})
                is_title = "title" in (el.get("cssclass") or "").lower()
                wire.append({"kind": "title" if is_title else "static", "box": [x, y, w, h], "text": txt})
                continue
            if tag == "Div":
                wire.append({"kind": "div", "box": [x, y, w, h]})
                inner = _layout(el)
                if inner is not None:
                    self._walk(inner, path + ".", x, y, w, h, comps, statics, wire)
                continue
            events = {k: v for k, v in el.attrib.items() if k.startswith("on") and v}
            typ = COMPONENT_TYPES.get(tag, "other")
            comp = {"id": cid, "path": path, "tag": tag, "type": typ, "box": (x, y, w, h),
                    "text": el.get("text") or "", "events": events}
            if tag == "Grid":
                comp["binddataset"] = el.get("binddataset")
                head = [c.get("text") or "" for c in el.iter("Cell") if self._band(el, c) == "head"]
                comp["cols"] = [re.sub(r"^bind:", "", c.get("text") or "") for c in el.iter("Cell")
                                if self._band(el, c) == "body" and (c.get("text") or "").startswith("bind:")]
                sizes = [float(c.get("size") or 80) for c in el.iter("Column")]
                wire.append({"kind": "grid", "box": [x, y, w, h],
                             "cols": list(zip(head, sizes + [80.0] * (len(head) - len(sizes))))})
                comp["text"] = ""
            elif tag == "Tab":
                pages = el.find("Tabpages")
                tps = pages.findall("Tabpage") if pages is not None else []
                names = [tp.get("text") or tp.get("id", "") for tp in tps]
                wire.append({"kind": "tab", "box": [x, y, w, h], "tabs": names})
                comp["text"] = "/".join(names)
                comps.append(comp)             # 탭 자체를 먼저, 탭 안 컴포넌트는 그 뒤에
                for i, tp in enumerate(tps):
                    inner = _layout(tp)
                    if inner is not None:      # 첫 탭(기본 표시)만 와이어프레임에 그린다
                        self._walk(inner, f"{path}.{tp.get('id')}.", x, y + TAB_HEADER, w, h - TAB_HEADER,
                                   comps, statics, wire if i == 0 else [])
                continue
            else:
                wire.append({"kind": WIRE_KIND.get(typ, "other"), "box": [x, y, w, h],
                             "text": el.get("text") if tag in ("Button", "CheckBox", "Radio") else (el.get("displaynulltext") or "")})
                if tag in ("Combo", "ListBox"):
                    comp["innerdataset"] = el.get("innerdataset")
            comps.append(comp)

    @staticmethod
    def _band(grid: ET.Element, cell: ET.Element) -> str | None:
        for band in grid.iter("Band"):
            if cell in list(band):
                return band.get("id")
        return None

    @staticmethod
    def _label_for(c: dict, statics: list[dict]) -> str | None:
        """같은 줄 왼쪽의 가장 가까운 Static, 그리드는 바로 위 Static."""
        x, y, w, h = c["box"]
        best, dist = None, 1e9
        for s in statics:
            sx, sy, sw, sh = s["box"]
            if not s["text"]:
                continue
            if c["type"] in ("grid", "tab"):
                if sy + sh <= y + 2 and y - (sy + sh) < 45 and sx < x + w and sx + sw > x:
                    d = y - (sy + sh)
                    if d < dist:
                        best, dist = s["text"], d
            elif abs((sy + sh / 2) - (y + h / 2)) <= 8 and sx + sw <= x + 6 and x - (sx + sw) < 200:
                d = x - (sx + sw)
                if d < dist:
                    best, dist = s["text"], d
        return best

    # ---------- 스크립트 스캔 ----------
    def _scan(self, sc: NxScript, node: Node, fkey: str, state: dict | None = None) -> None:
        state = state if state is not None else {"confirm": None}
        for child in node.children:
            if child.type == "function_declaration":
                continue
            if child.type == "call_expression":
                self._call(sc, child, fkey, state)
            self._scan(sc, child, fkey, state)

    def _callee_name(self, callee: Node) -> tuple[str, bool]:
        """(이름, this/전역 호출 여부). this.fn_x() → ('fn_x', True), fn_x() → ('fn_x', True), this.ds.addRow() → ('ds.addRow', False)"""
        t = text(callee)
        t2 = re.sub(r"^(this|window|nexacro\.getApplication\(\))\.", "", t)
        return t2, "." not in t2

    def _call(self, sc: NxScript, call: Node, fkey: str, state: dict) -> None:
        m = self.ctx.model
        callee = call.child_by_field_name("function")
        args_node = call.child_by_field_name("arguments")
        args = args_node.named_children if args_node else []
        ev = f"{sc.file}:{sc.line(call)}"
        name, simple = self._callee_name(callee)
        cond = f'confirm("{state["confirm"]}")' if state.get("confirm") else None
        if not simple:
            return
        if name in ("confirm", "Confirm") and args:          # MiPlatform: Confirm()
            state["confirm"] = string_value(args[0]) or text(args[0])
            return
        if name in self.fapis or name in self.tx_names:
            spec = self.fapis.get(name, {"urlArg": 1, "method": "POST", "callbackArg": 5})
            ui = spec.get("urlArg", 1)
            url, conf = url_arg(args[ui] if len(args) > ui else None)
            if url:
                path = self.service_path(url)
                akey = api_key(spec.get("method", "POST"), path)
                m.add_api(akey, spec.get("method", "POST"), path)
                sid = string_value(args[0]) if args else None
                ind = string_value(args[2]) if len(args) > 2 else None
                outd = string_value(args[3]) if len(args) > 3 else None
                detail = " · ".join(x for x in (f"svcId={sid}" if sid else "", f"in: {ind}" if ind else "",
                                                 f"out: {outd}" if outd else "") if x)
                m.add_edge(fkey, akey, "request", confidence=conf, condition=cond,
                           evidence=f"{ev}" + (f" ({detail})" if detail else ""))
            elif not sc.common:
                m.fail("parse", fkey, f"{name} URL 인자를 해석하지 못함 ({ev})")
            ci = spec.get("callbackArg")
            if ci is not None and len(args) > ci:
                cb = string_value(args[ci])
                if cb and cb in self.scope and self.scope[cb].key != fkey:
                    m.add_edge(fkey, self.scope[cb].key, "calls", confidence=0.9, condition="콜백", evidence=ev)
            return
        if name in self.fnavs:
            spec = self.fnavs[name]
            ui = spec.get("urlArg", 0)
            url, conf = url_arg(args[ui] if len(args) > ui else None)
            if url:
                self.pending_nav.append((fkey, url, conf, ev, bool(spec.get("popup", True))))
            ci = spec.get("callbackArg")
            if ci is not None and len(args) > ci:
                cb = string_value(args[ci])
                if cb and cb in self.scope and self.scope[cb].key != fkey:
                    m.add_edge(fkey, self.scope[cb].key, "calls", confidence=0.85, condition="팝업 닫힘 콜백", evidence=ev)
            return
        target = self.scope.get(name)
        if target is not None and target.key != fkey:
            m.add_edge(fkey, target.key, "calls", confidence=0.95, condition=cond, evidence=ev)

    def _resolve_navigation(self) -> None:
        m = self.ctx.model
        for fkey, url, conf, ev, popup in self.pending_nav:
            target = screen_for_url(self.ctx, url)
            if not target:
                m.fail("parse", fkey, f"{'팝업' if popup else '이동'} 대상 화면을 찾지 못함: {url} ({ev})")
                continue
            m.add_edge(fkey, target, "navigates", confidence=conf, evidence=ev)
            if popup:
                m.screens[target]["kind"] = "popup"
