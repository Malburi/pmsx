"""SPA 화면 파서 — Vue(2/3 SFC, Options·Composition API)·React(JSX/TSX). docs/11 §7.

라우터 → 화면(경로↔컴포넌트), 템플릿/JSX → UI 요소·이벤트, 스크립트 → 함수 단위 코드,
import 그래프를 따라 API 모듈 함수 → axios/fetch 요청(request), router.push·<router-link>·navigate() → 화면 이동,
대화상자 컴포넌트(…Dialog/…Modal/…Popup)는 팝업 화면으로 보고, 여는 함수(visible=true / setOpen(true))에서 navigates.

URL 해석: 리터럴 / 템플릿 리터럴(`/api/x/${id}` → /api/x/{id}) / 문자열 상수 결합 / axios.create({ baseURL }) 접두어.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from html.parser import HTMLParser
from pathlib import Path

from tree_sitter import Node

from ..keys import api_key, el_key, fn_key, scr_key
from . import Context
from .ts import parse, string_value, text, walk
from .ui_common import link_menus, link_server, refine_popup_types

HTTP_METHODS = {"get", "post", "put", "delete", "patch", "head"}
DEFAULT_CLIENTS = {"axios", "http", "api", "request", "$http", "instance", "client", "service", "$axios"}
FN_TYPES = ("function_expression", "arrow_function", "function", "generator_function", "function_declaration")
EXTS = ["", ".ts", ".js", ".tsx", ".jsx", ".vue", "/index.ts", "/index.js", "/index.tsx", "/index.jsx"]
DIALOG_RE = re.compile(r"(Dialog|Modal|Popup|Drawer)$", re.I)

TAG_TYPES = {
    "button": "button", "el-button": "button", "v-btn": "button", "a-button": "button", "q-btn": "button",
    "Button": "button", "IconButton": "button", "LoadingButton": "button",
    "input": "input", "el-input": "input", "v-text-field": "input", "a-input": "input", "q-input": "input",
    "TextField": "input", "Input": "input", "el-input-number": "input",
    "textarea": "input", "v-textarea": "input",
    "select": "combo", "el-select": "combo", "v-select": "combo", "a-select": "combo", "q-select": "combo",
    "Select": "combo", "Autocomplete": "combo", "v-autocomplete": "combo",
    "el-date-picker": "date", "v-date-picker": "date", "a-date-picker": "date", "DatePicker": "date",
    "el-checkbox": "check", "v-checkbox": "check", "Checkbox": "check",
    "el-radio": "radio", "el-radio-group": "radio", "v-radio-group": "radio", "RadioGroup": "radio",
    "table": "grid", "el-table": "grid", "v-data-table": "grid", "a-table": "grid", "q-table": "grid",
    "DataGrid": "grid", "Table": "grid", "AgGridReact": "grid",
    "el-tabs": "tab", "v-tabs": "tab", "Tabs": "tab",
    "el-upload": "file", "v-file-input": "file", "Upload": "file",
    "a": "link", "router-link": "link", "Link": "link", "RouterLink": "link", "NavLink": "link",
}
EVENT_MAP = {"click": "click", "dblclick": "dblclick", "doubleclick": "dblclick", "change": "change", "input": "change",
             "keyup": "keyup", "keydown": "keydown", "submit": "submit", "row-click": "click", "row-dblclick": "dblclick",
             "rowclick": "click", "rowdoubleclick": "dblclick", "update:model-value": "change", "update:modelvalue": "change",
             "select": "change", "blur": "blur", "focus": "focus", "celldoubleclick": "dblclick"}


# ---------------------------------------------------------------- 모듈(JS/TS)
@dataclass
class JsModule:
    path: Path
    rel: str
    lang: str                 # 키·code unit lang: js | ts
    grammar: str              # tree-sitter: js | ts | tsx
    src: str
    offset: int = 0           # .vue 의 <script> 시작 줄 - 1
    tree: object = None
    functions: dict[str, Node] = field(default_factory=dict)
    imports: dict[str, tuple[Path | None, str]] = field(default_factory=dict)   # 지역명 → (모듈 경로, 가져온 이름 | default | *)
    exports: dict[str, str] = field(default_factory=dict)                       # 내보낸 이름 → 지역 함수명
    consts: dict[str, str] = field(default_factory=dict)                        # 문자열 상수
    clients: dict[str, str] = field(default_factory=dict)                       # axios 인스턴스명 → baseURL
    default_export: str | None = None
    fn_spans: set = field(default_factory=set)                                  # 함수 노드 범위 (본문 스캔 시 중첩 함수 건너뛰기)

    def line(self, node: Node) -> int:
        return node.start_point[0] + 1 + self.offset

    def fn_key(self, name: str) -> str:
        return fn_key(self.lang, self.rel, name)


def _fn_value(v: Node | None) -> Node | None:
    """함수 값이면 함수 노드. useCallback(() => …) 같은 래퍼도 벗긴다."""
    if v is None:
        return None
    if v.type in FN_TYPES:
        return v
    if v.type == "call_expression":
        args = v.child_by_field_name("arguments")
        callee = text(v.child_by_field_name("function"))
        if args and args.named_children and callee in ("useCallback", "useMemo", "computed", "debounce", "throttle", "_.debounce"):
            a0 = args.named_children[0]
            return a0 if a0.type in FN_TYPES else None
    return None


def _pkey(n: Node | None) -> str:
    return text(n).strip("'\"") if n is not None else ""


class Project:
    """웹 소스 루트 단위 모듈 캐시 + import 해석."""

    def __init__(self, ctx: Context, root: Path):
        self.ctx, self.root = ctx, root
        self.alias: dict[str, str] = {"@": "src", "~": "src"}
        self.alias.update(ctx.config.get("spa.alias", {}) or {})
        self.modules: dict[Path, JsModule | None] = {}
        self.clients = set(DEFAULT_CLIENTS) | set(ctx.config.get("spa.httpClients", []) or [])
        self.base_url = ctx.config.get("spa.apiBaseUrl")

    def rel(self, p: Path) -> str:
        try:
            return p.resolve().relative_to(self.ctx.config.source_root).as_posix()
        except ValueError:
            return p.as_posix()

    def resolve(self, spec: str, from_file: Path) -> Path | None:
        if spec.startswith("."):
            base = (from_file.parent / spec)
        else:
            hit = next((a for a in sorted(self.alias, key=len, reverse=True) if spec == a or spec.startswith(a + "/")), None)
            if hit is None:
                return None                      # 외부 패키지
            base = self.root / self.alias[hit] / spec[len(hit):].lstrip("/")
        for e in EXTS:
            p = Path(str(base) + e)
            if p.is_file():
                return p.resolve()
        return None

    def module(self, path: Path) -> JsModule | None:
        path = path.resolve()
        if path in self.modules:
            return self.modules[path]
        self.modules[path] = None                # 순환 import 방지
        raw = path.read_text(encoding="utf-8")
        if path.suffix == ".vue":
            sfc = parse_sfc(raw)
            if not sfc.script:
                return None
            lang = "ts" if sfc.script_lang in ("ts", "tsx") else "js"
            mod = JsModule(path, self.rel(path), lang, "tsx" if sfc.script_lang == "tsx" else lang, sfc.script, sfc.script_offset)
        else:
            ext = path.suffix.lower()
            grammar = {".ts": "ts", ".mts": "ts", ".tsx": "tsx", ".jsx": "js"}.get(ext, "js")
            mod = JsModule(path, self.rel(path), "ts" if grammar in ("ts", "tsx") else "js", grammar, raw)
        self._load(mod)
        self.modules[path] = mod
        return mod

    def _load(self, mod: JsModule) -> None:
        mod.tree = parse(mod.grammar, mod.src)
        root = mod.tree.root_node
        for n in walk(root):
            t = n.type
            if t == "function_declaration":
                mod.functions.setdefault(text(n.child_by_field_name("name")), n)
            elif t == "variable_declarator":
                name_node = n.child_by_field_name("name")
                val = n.child_by_field_name("value")
                fnode = _fn_value(val)
                if fnode is not None and name_node is not None and name_node.type == "identifier":
                    mod.functions.setdefault(text(name_node), fnode)
                elif val is not None and name_node is not None and name_node.type == "identifier":
                    sv = self._str(mod, val)
                    if sv is not None:
                        mod.consts.setdefault(text(name_node), sv)
                    self._client_decl(mod, text(name_node), val)
            elif t == "pair":
                fnode = _fn_value(n.child_by_field_name("value"))
                if fnode is not None:
                    mod.functions.setdefault(_pkey(n.child_by_field_name("key")), fnode)
            elif t == "method_definition":
                mod.functions.setdefault(_pkey(n.child_by_field_name("name")), n)
            elif t == "import_statement":
                self._import(mod, n)
        for n in root.named_children:
            if n.type == "export_statement":
                self._export(mod, n)
        mod.fn_spans = {(n.start_byte, n.end_byte) for n in mod.functions.values()}

    def _client_decl(self, mod: JsModule, name: str, val: Node) -> None:
        """const http = axios.create({ baseURL: '/api' })"""
        if val.type != "call_expression" or text(val.child_by_field_name("function")) not in ("axios.create", "create"):
            return
        base = ""
        args = val.child_by_field_name("arguments")
        if args and args.named_children and args.named_children[0].type == "object":
            for p in args.named_children[0].named_children:
                if p.type == "pair" and _pkey(p.child_by_field_name("key")) == "baseURL":
                    base = self._str(mod, p.child_by_field_name("value")) or ""
        mod.clients[name] = base

    def _import(self, mod: JsModule, n: Node) -> None:
        src = n.child_by_field_name("source")
        spec = string_value(src) if src is not None else None
        if not spec:
            return
        target = self.resolve(spec, mod.path)
        clause = next((c for c in n.named_children if c.type == "import_clause"), None)
        if clause is None:
            return
        for c in clause.named_children:
            if c.type == "identifier":
                mod.imports[text(c)] = (target, "default")
            elif c.type == "namespace_import":
                ids = [x for x in c.named_children if x.type == "identifier"]
                if ids:
                    mod.imports[text(ids[-1])] = (target, "*")
            elif c.type == "named_imports":
                for s in c.named_children:
                    if s.type == "import_specifier":
                        name = text(s.child_by_field_name("name"))
                        alias = s.child_by_field_name("alias")
                        mod.imports[text(alias) if alias is not None else name] = (target, name)

    def _export(self, mod: JsModule, n: Node) -> None:
        decl = n.child_by_field_name("declaration")
        val = n.child_by_field_name("value")
        is_default = any(c.type == "default" for c in n.children)
        if decl is not None:
            if decl.type in ("function_declaration", "generator_function_declaration", "class_declaration"):
                nm = text(decl.child_by_field_name("name"))
                mod.exports[nm] = nm
                if is_default:
                    mod.exports["default"] = nm
                    mod.default_export = nm
            elif decl.type in ("lexical_declaration", "variable_declaration"):
                for d in decl.named_children:
                    if d.type == "variable_declarator":
                        nm = text(d.child_by_field_name("name"))
                        mod.exports[nm] = nm
        elif is_default and val is not None:
            if val.type == "identifier":
                mod.exports["default"] = text(val)
                mod.default_export = text(val)
            elif val.type == "object":
                for p in val.named_children:
                    if p.type == "pair":
                        k = _pkey(p.child_by_field_name("key"))
                        v = p.child_by_field_name("value")
                        mod.exports[k] = text(v) if v is not None and v.type == "identifier" else k
                    elif p.type == "method_definition":
                        k = _pkey(p.child_by_field_name("name"))
                        mod.exports[k] = k
                    elif p.type == "shorthand_property_identifier":
                        mod.exports[text(p)] = text(p)
        for c in n.named_children:
            if c.type == "export_clause":
                for s in c.named_children:
                    if s.type == "export_specifier":
                        name = text(s.child_by_field_name("name"))
                        alias = s.child_by_field_name("alias")
                        mod.exports[text(alias) if alias is not None else name] = name

    # ---------- 값 해석 ----------
    def _str(self, mod: JsModule, node: Node | None) -> str | None:
        """문자열 리터럴·상수·상수 결합 → 문자열 (해석 불가 None)."""
        if node is None:
            return None
        v = string_value(node)
        if v is not None and node.type != "template_string":
            return v
        if node.type == "identifier":
            return mod.consts.get(text(node))
        return None

    def url(self, mod: JsModule, node: Node | None) -> tuple[str | None, float]:
        """URL 인자 → (경로, 신뢰도). `/a/${id}` → /a/{id}, BASE + '/x' → 상수 대입, '/a/' + id → /a/{id}."""
        if node is None:
            return None, 0
        if node.type in ("string", "string_fragment"):
            v = string_value(node)
            return (v, 0.95) if v is not None else (None, 0)
        if node.type == "identifier":
            v = mod.consts.get(text(node))
            return (v, 0.9) if v is not None else (None, 0)
        if node.type == "template_string":
            out, ok = [], True
            for c in node.children:
                if c.type == "string_fragment":
                    out.append(text(c))
                elif c.type == "template_substitution":
                    inner = c.named_children[0] if c.named_children else None
                    cv = mod.consts.get(text(inner)) if inner is not None and inner.type == "identifier" else None
                    if cv is not None:
                        out.append(cv)
                    else:
                        out.append("{" + (re.findall(r"[\w$]+", text(inner))[-1] if inner is not None and re.findall(r"[\w$]+", text(inner)) else "v") + "}")
                elif c.type == "escape_sequence":
                    out.append(text(c))
            s = "".join(out)
            return (s, 0.9) if ok and s else (None, 0)
        if node.type == "binary_expression" and text(node.child_by_field_name("operator")) == "+":
            l, lc = self.url(mod, node.child_by_field_name("left"))
            r, rc = self.url(mod, node.child_by_field_name("right"))
            rn = node.child_by_field_name("right")
            if l is not None and r is None and rn is not None:
                ids = re.findall(r"[\w$]+", text(rn))
                r, rc = "{" + (ids[-1] if ids else "v") + "}", 0.85
            if l is not None and r is not None:
                return l + r, min(lc, rc, 0.9)
            return (l, 0.8) if l is not None else (None, 0)
        if node.type == "parenthesized_expression" and node.named_children:
            return self.url(mod, node.named_children[0])
        return None, 0

    # ---------- 함수 해석 ----------
    def resolve_export(self, path: Path | None, name: str, depth: int = 0) -> tuple[JsModule, str] | None:
        if path is None or depth > 5:
            return None
        mod = self.module(path)
        if mod is None:
            return None
        local = mod.exports.get(name, name)
        if local in mod.functions:
            return mod, local
        if local in mod.imports:                 # re-export
            p2, n2 = mod.imports[local]
            return self.resolve_export(p2, n2, depth + 1)
        return None

    def resolve_name(self, mod: JsModule, nm: str) -> tuple[JsModule, str] | None:
        if nm in mod.functions:
            return mod, nm
        if nm in mod.imports:
            p, n2 = mod.imports[nm]
            return self.resolve_export(p, n2)
        return None

    def resolve_callee(self, mod: JsModule, callee: Node) -> tuple[JsModule, str] | None:
        if callee.type == "identifier":
            return self.resolve_name(mod, text(callee))
        if callee.type == "member_expression":
            obj = callee.child_by_field_name("object")
            prop = text(callee.child_by_field_name("property"))
            ot = text(obj)
            if ot == "this" and prop in mod.functions:
                return mod, prop
            if obj.type == "identifier" and ot in mod.imports:
                p, kind = mod.imports[ot]
                if kind in ("*", "default"):
                    return self.resolve_export(p, prop)
        return None

    def client_base(self, mod: JsModule, obj: Node) -> str | None:
        """요청 클라이언트면 baseURL(없으면 ''), 아니면 None."""
        ot = text(obj)
        if ot in mod.clients:
            return mod.clients[ot]
        if ot in mod.imports:
            p, n2 = mod.imports[ot]
            m2 = self.module(p) if p else None
            if m2 is not None:
                local = m2.exports.get(n2, n2) if n2 != "default" else (m2.default_export or "")
                if local in m2.clients:
                    return m2.clients[local]
            if ot in self.clients or n2 == "axios":
                return ""
            return None
        if ot in self.clients:
            return ""
        return None


# ---------------------------------------------------------------- Vue SFC
@dataclass
class Sfc:
    template: str = ""
    template_offset: int = 0
    script: str = ""
    script_offset: int = 0
    script_lang: str = "js"


def parse_sfc(raw: str) -> Sfc:
    sfc = Sfc()
    tm = re.search(r"^<template(\s[^>]*)?>", raw, re.M)
    if tm:
        end = raw.rfind("</template>")
        sfc.template = raw[tm.end(): end if end > tm.end() else len(raw)]
        sfc.template_offset = raw.count("\n", 0, tm.end())
    scripts = list(re.finditer(r"<script(\s[^>]*)?>(.*?)</script>", raw, re.S))
    # <script setup> 우선, 둘 다 있으면 이어 붙인다(줄 위치는 첫 블록 기준 근사)
    scripts.sort(key=lambda s: 0 if "setup" in (s.group(1) or "") else 1)
    if scripts:
        first = scripts[0]
        attrs = first.group(1) or ""
        lm = re.search(r'lang\s*=\s*["\'](\w+)["\']', attrs)
        sfc.script_lang = lm.group(1) if lm else "js"
        sfc.script_offset = raw.count("\n", 0, first.start(2))
        sfc.script = first.group(2) + "".join("\n" + s.group(2) for s in scripts[1:])
    return sfc


VOID_TAGS = {"input", "br", "img", "hr", "meta", "link", "col", "source", "area", "wbr"}
FORM_ITEM_TAGS = {"el-form-item", "a-form-item", "q-field", "v-input"}
TABLE_TAGS = {"table", "el-table", "v-data-table", "a-table", "q-table"}
ATTR_RE = re.compile(r"""([@:#\w.-]+(?::[\w.-]+)?)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'))?""")


class _VueTemplate(HTMLParser):
    """템플릿 → 요소 후보. 태그·속성 원문(대소문자)을 보존하려고 get_starttag_text 를 다시 읽는다.

    열린 태그 스택의 각 프레임이 '이 태그가 닫힐 때 끝낼 일'(텍스트 수집 대상, 폼 라벨, 테이블)을 들고 있다.
    """

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.items: list[dict] = []
        self.labels: dict[str, str] = {}
        self.stack: list[dict] = []
        self._label_for: str | None = None
        self._label_buf: list[str] = []

    def _attrs(self, raw: str, real: str) -> dict[str, str]:
        a: dict[str, str] = {}
        body = raw[len(real) + 1:].rstrip(">").rstrip("/")
        for am in ATTR_RE.finditer(body):
            a[am.group(1)] = am.group(2) if am.group(2) is not None else (am.group(3) if am.group(3) is not None else "")
        return a

    def _find(self, key: str):
        for fr in reversed(self.stack):
            if fr.get(key) is not None:
                return fr[key]
        return None

    def handle_starttag(self, tag, attrs, self_closing: bool = False):
        raw = self.get_starttag_text() or ""
        mt = re.match(r"<\s*([\w:.-]+)", raw)
        real = mt.group(1) if mt else tag
        a = self._attrs(raw, real)
        line = self.getpos()[0]
        frame: dict = {"tag": tag}
        if real in FORM_ITEM_TAGS and a.get("label"):
            frame["form_label"] = a["label"]
        if tag == "label" and a.get("for"):
            self._label_for, self._label_buf = a["for"], []
        table = self._find("table")
        if table is not None and real in ("el-table-column", "v-data-table-column", "a-table-column"):
            table["cols"].append(a.get("prop") or a.get("label") or "")
            if a.get("label"):
                table["heads"].append(a["label"])
        if tag == "th" and table is not None:
            frame["th"] = []
        typ = TAG_TYPES.get(real) or TAG_TYPES.get(tag)
        ev = _events(a)
        if typ or ev or DIALOG_RE.search(real) or real[:1].isupper():
            item = {"tag": real, "type": typ, "attrs": a, "events": ev, "line": line, "text": "", "cols": [], "heads": [],
                    "form_label": self._find("form_label"), "grid": self._find("table")}
            self.items.append(item)
            if typ in ("button", "link") or (typ is None and ev):
                frame["text_target"] = item
            if typ == "grid":
                frame["table"] = item
        if self_closing or tag in VOID_TAGS:
            return
        self.stack.append(frame)

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs, self_closing=True)

    def handle_endtag(self, tag):
        if tag == "label" and self._label_for:
            self.labels[self._label_for] = re.sub(r"\s+", " ", "".join(self._label_buf)).strip()
            self._label_for = None
        # 같은 태그의 가장 가까운 프레임까지 닫는다 (잘못 짝지어진 태그 허용)
        idx = next((i for i in range(len(self.stack) - 1, -1, -1) if self.stack[i]["tag"] == tag), None)
        if idx is None:
            return
        for fr in reversed(self.stack[idx:]):
            if fr.get("th") is not None:
                t = re.sub(r"\s+", " ", "".join(fr["th"])).strip()
                table = self._find_below("table", idx)
                if table is not None:
                    table["heads"].append(t)
                    table["cols"].append(t)
        del self.stack[idx:]

    def _find_below(self, key: str, idx: int):
        for fr in reversed(self.stack[:idx + 1]):
            if fr.get(key) is not None:
                return fr[key]
        return None

    def handle_data(self, data):
        tgt = self._find("text_target")
        if tgt is not None:
            tgt["text"] += data
        if self._label_for:
            self._label_buf.append(data)
        for fr in reversed(self.stack):
            if fr.get("th") is not None:
                fr["th"].append(data)
                break


def _events(attrs: dict) -> dict[str, str]:
    out = {}
    for k, v in attrs.items():
        name = None
        if k.startswith("@"):
            name = k[1:]
        elif k.startswith("v-on:"):
            name = k[5:]
        if name is None:
            continue
        base = name.split(".")[0].lower()
        out[EVENT_MAP.get(base, base)] = v
    return out


# ---------------------------------------------------------------- 파서 공통
class _SpaBase:
    name = "spa"
    layer = "ui"
    tech = "vue"

    def detect(self, root: Path) -> float:
        return 0.0

    def parse(self, ctx: Context) -> None:
        cfg, m = ctx.config, ctx.model
        self.ctx = ctx
        web = cfg.source_dir(self.source_key)
        if not web or not web.is_dir():
            m.fail("parse", f"source.{self.source_key}", f"{self.tech} 소스 경로 없음")
            return
        self.web = web
        self.proj = Project(ctx, web)
        self.pending_nav: list[tuple[str, str, float, str, bool]] = []
        self.route_by_name: dict[str, str] = {}
        self.dialog_screens: dict[Path, str] = {}
        routes = self.read_routes()
        if not routes:
            m.fail("parse", f"source.{self.source_key}", "라우터 정의(routes)를 찾지 못했습니다 (spa.router 설정 확인)")
        ctx.spa_routes.extend(routes)
        for r in routes:
            if r.get("name"):
                self.route_by_name[r["name"]] = r["path"]
        for r in routes:
            if r.get("file"):
                self.screen(r["file"], r)
        self._resolve_navigation()
        link_menus(ctx)
        link_server(ctx)
        refine_popup_types(ctx)

    # ---------- 라우터 ----------
    def router_files(self) -> list[Path]:
        cfg = self.ctx.config
        conf = cfg.get(f"spa.router") if self.tech == "vue" else cfg.get("spa.reactRouter")
        if conf:
            return [cfg.resolve(c) for c in (conf if isinstance(conf, list) else [conf])]
        out = []
        for p in sorted(self.web.rglob("*")):
            if p.suffix in (".js", ".ts", ".jsx", ".tsx") and "node_modules" not in p.parts and "dist" not in p.parts:
                head = p.read_text(encoding="utf-8", errors="ignore")
                if re.search(r"createRouter|new VueRouter|createBrowserRouter|<Route\b|useRoutes", head):
                    out.append(p)
        return out

    def read_routes(self) -> list[dict]:
        routes: list[dict] = []
        for rf in self.router_files():
            if rf is None or not rf.is_file():
                continue
            mod = self.proj.module(rf)
            if mod is None:
                continue
            for n in walk(mod.tree.root_node):
                if n.type == "array" and any(self._is_route_obj(c) for c in n.named_children):
                    parent_of = n.parent
                    # 하위 children 배열은 상위 객체에서 처리
                    if parent_of is not None and parent_of.type == "pair" and _pkey(parent_of.child_by_field_name("key")) == "children":
                        continue
                    for c in n.named_children:
                        if self._is_route_obj(c):
                            self._route_obj(mod, c, "", None, routes)
                elif n.type in ("jsx_self_closing_element", "jsx_element"):
                    self._jsx_route(mod, n, routes)
        return routes

    @staticmethod
    def _is_route_obj(n: Node) -> bool:
        return n.type == "object" and any(p.type == "pair" and _pkey(p.child_by_field_name("key")) == "path" for p in n.named_children)

    def _route_obj(self, mod: JsModule, obj: Node, prefix: str, parent_title: str | None, out: list[dict]) -> None:
        r: dict = {}
        children = None
        for p in obj.named_children:
            if p.type != "pair":
                continue
            k, v = _pkey(p.child_by_field_name("key")), p.child_by_field_name("value")
            if k == "path":
                r["path"] = string_value(v) or ""
            elif k == "name":
                r["name"] = string_value(v)
            elif k in ("component", "element", "Component"):
                r["file"] = self._component_file(mod, v)
            elif k in ("meta", "handle"):
                for mp in (v.named_children if v is not None and v.type == "object" else []):
                    if mp.type == "pair":
                        mk = _pkey(mp.child_by_field_name("key"))
                        mv = mp.child_by_field_name("value")
                        r[mk] = string_value(mv) if string_value(mv) is not None else text(mv)
            elif k == "children" and v is not None and v.type == "array":
                children = v
        path = r.get("path", "")
        full = path if path.startswith("/") else (prefix.rstrip("/") + "/" + path if path else prefix)
        r["path"] = full or "/"
        title = r.get("title")
        if r.get("file"):
            r.setdefault("menuGroup", parent_title)
            out.append(r)
        if children is not None:
            for c in children.named_children:
                if self._is_route_obj(c):
                    self._route_obj(mod, c, full, title or parent_title, out)

    def _jsx_route(self, mod: JsModule, n: Node, out: list[dict]) -> None:
        tag_node = n if n.type == "jsx_self_closing_element" else n.child_by_field_name("open_tag")
        if tag_node is None or text(tag_node.child_by_field_name("name")) != "Route":
            return
        r: dict = {}
        for a in tag_node.named_children:
            if a.type != "jsx_attribute":
                continue
            kids = a.named_children
            k = text(kids[0]) if kids else ""
            v = kids[1] if len(kids) > 1 else None
            if k == "path":
                r["path"] = string_value(v) or ""
            elif k in ("element", "component") and v is not None:
                r["file"] = self._component_file(mod, v)
        if r.get("file"):
            r["path"] = r.get("path") or "/"
            out.append(r)

    def _component_file(self, mod: JsModule, v: Node | None) -> Path | None:
        if v is None:
            return None
        for n in walk(v):
            if n.type == "call_expression" and text(n.child_by_field_name("function")) == "import":
                args = n.child_by_field_name("arguments")
                spec = string_value(args.named_children[0]) if args and args.named_children else None
                if spec:
                    return self.proj.resolve(spec, mod.path)
            if n.type in ("jsx_self_closing_element", "jsx_opening_element"):
                nm = text(n.child_by_field_name("name"))
                return self._import_file(mod, nm)
            if n.type == "identifier":
                return self._import_file(mod, text(n))
        return None

    def _import_file(self, mod: JsModule, name: str) -> Path | None:
        if name in mod.imports:
            return mod.imports[name][0]
        return None

    # ---------- 화면 ----------
    def screen_key_for(self, file: Path, route: dict | None) -> str:
        sid = (route or {}).get("screenId") or (route or {}).get("name") or file.stem
        return scr_key(self.ctx.config.sys_code, re.sub(r"[^\w-]", "_", str(sid)))

    def screen(self, file: Path, route: dict | None, kind: str = "page") -> str | None:
        ctx, cfg, m = self.ctx, self.ctx.config, self.ctx.model
        if file is None or not file.is_file():
            return None
        skey = self.screen_key_for(file, route)
        if skey in m.screens:
            return skey
        sid = skey.rsplit(":", 1)[-1]
        name = (route or {}).get("title") or (route or {}).get("name") or file.stem
        s = {"key": skey, "screenId": sid, "name": str(name), "kind": kind, "uiTech": self.tech,
             "sourceFile": cfg.rel(file), "captures": []}
        if route and route.get("path"):
            s["url"] = route["path"]
            ctx.screen_by_url[route["path"]] = skey
        m.screens[skey] = s
        mod = self.proj.module(file)
        items, dialogs, lifecycle = self.elements(file, mod)
        self.skey, self.mod = skey, mod
        # 함수 코드 단위 + 본문 스캔
        if mod is not None:
            ctrl_map: dict[str, str] = {}
            for tag, ctrl in dialogs:
                dfile = self._import_file(mod, tag)
                if dfile is not None:
                    dkey = self.dialog_screens.get(dfile) or self.screen(dfile, None, "popup")
                    if dkey:
                        self.dialog_screens[dfile] = dkey
                        m.screens[dkey]["kind"] = "popup"
                        if ctrl:
                            ctrl_map[ctrl] = dkey
            self.skey, self.mod, self._dialog_ctrl = skey, mod, ctrl_map
            self.scan_module(mod)
            for hook, fnode in lifecycle(mod):
                key = mod.fn_key(hook)
                self._register(mod, hook, fnode, key)
                self._scan(mod, fnode, key, {"confirm": None})
                m.add_edge(skey, key, "event", event="onload", confidence=0.95, evidence=f"{mod.rel}:{mod.line(fnode)}")
                ctx.screen_onload.setdefault(skey, []).append(key)
        # 요소
        seen: dict[str, int] = {}
        for seq, it in enumerate(items, 1):
            eid = self._element_id(it, seq)
            if eid in seen:
                seen[eid] += 1
                eid = f"{eid}_{seen[eid]}"
            else:
                seen[eid] = 1
            ekey = el_key(skey, eid)
            entry = {"key": ekey, "screenKey": skey, "elementId": eid, "label": it["label"] or eid,
                     "type": it["type"] or "button", "matched": "static-only", "_seq": seq, "_tag": it["tag"].lower(),
                     "_name": it.get("name") or ""}
            if it.get("name"):
                entry["name"] = it["name"]
            if it.get("binding"):
                entry["binding"] = it["binding"]
            m.elements[ekey] = entry
            ev_loc = f"{cfg.rel(file)}:{it['line']}"
            for ev, expr in it["events"].items():
                for fkey in self._handler_targets(mod, expr, it["line"]):
                    m.add_edge(ekey, fkey, "event", event=ev, confidence=0.99, evidence=ev_loc)
            if it.get("to"):
                self.pending_nav.append((ekey, it["to"], 0.95, ev_loc, False))
        return skey

    def _element_id(self, it: dict, seq: int) -> str:
        a = it.get("attrs", {})
        for k in ("id", "ref", "data-testid", "name"):
            if a.get(k):
                return a[k]
        if it.get("model"):
            return "model:" + it["model"]
        return f"{it['tag'].lower()}{seq}"

    def _register(self, mod: JsModule, name: str, node: Node, key: str) -> None:
        snippet = None
        if self.ctx.config.include_snippets:
            snippet = "\n".join(mod.src.splitlines()[node.start_point[0]: node.end_point[0] + 1])
        self.ctx.model.add_code_unit(key, mod.lang, mod.rel, name, mod.line(node), node.end_point[0] + 1 + mod.offset, snippet)

    def scan_module(self, mod: JsModule, seen: set | None = None) -> None:
        for name, node in mod.functions.items():
            key = mod.fn_key(name)
            if key in self.ctx.model.code_units:
                continue
            self._register(mod, name, node, key)
            self._scan(mod, node, key, {"confirm": None})

    def _ensure_fn(self, mod: JsModule, name: str) -> str:
        key = mod.fn_key(name)
        if key not in self.ctx.model.code_units:
            node = mod.functions[name]
            self._register(mod, name, node, key)
            saved = (self.skey, self.mod, getattr(self, "_dialog_ctrl", {}))
            self._dialog_ctrl = {}
            self._scan(mod, node, key, {"confirm": None})
            self.skey, self.mod, self._dialog_ctrl = saved
        return key

    # ---------- 핸들러 식 ----------
    def _handler_targets(self, mod: JsModule | None, expr: str, line: int) -> list[str]:
        if mod is None or not expr:
            return []
        expr = expr.strip()
        if re.fullmatch(r"[\w$.]+", expr):
            nm = expr[len("this."):] if expr.startswith("this.") else expr
            hit = self.proj.resolve_name(mod, nm) if re.fullmatch(r"[\w$]+", nm) else None
            return [self._ensure_fn(*hit)] if hit else []
        tree = parse(mod.grammar, f"({expr})" if not expr.startswith("(") else expr)
        calls = [n for n in walk(tree.root_node) if n.type == "call_expression"]
        assigns = [n for n in walk(tree.root_node) if n.type == "assignment_expression"]
        targets: list[str] = []
        if len(calls) == 1 and not assigns:
            hit = self.proj.resolve_callee(mod, calls[0].child_by_field_name("function"))
            if hit:
                return [self._ensure_fn(*hit)]
        if not calls and not assigns:
            return []
        # 인라인 식(여러 호출·대입) → 익명 코드 단위
        key = mod.fn_key(f"inline@L{line}")
        m = self.ctx.model
        m.add_code_unit(key, mod.lang, mod.rel, f"인라인 핸들러 {expr[:40]}", line, line, expr if self.ctx.config.include_snippets else None)
        fake = JsModule(mod.path, mod.rel, mod.lang, mod.grammar, f"({expr})", line - 1)
        fake.functions, fake.imports, fake.consts, fake.clients = mod.functions, mod.imports, mod.consts, mod.clients
        fake.exports, fake.default_export = mod.exports, mod.default_export
        self._scan(fake, tree.root_node, key, {"confirm": None})
        targets.append(key)
        return targets

    # ---------- 함수 본문 ----------
    def _scan(self, mod: JsModule, node: Node, fkey: str, state: dict) -> None:
        for child in node.children:
            if (child.start_byte, child.end_byte) in mod.fn_spans:
                continue          # 중첩 함수(React 컴포넌트 안 핸들러 등)는 자기 코드 단위로 따로 스캔
            if child.type == "call_expression":
                self._call(mod, child, fkey, state)
            elif child.type == "assignment_expression":
                self._assign(mod, child, fkey)
            self._scan(mod, child, fkey, state)

    def _assign(self, mod: JsModule, a: Node, fkey: str) -> None:
        left = re.sub(r"^this\.|\.value$", "", text(a.child_by_field_name("left")))
        right = text(a.child_by_field_name("right"))
        ctrl = getattr(self, "_dialog_ctrl", {})
        if left in ctrl and right == "true":
            self.ctx.model.add_edge(fkey, ctrl[left], "navigates", confidence=0.9,
                                    evidence=f"{mod.rel}:{mod.line(a)} (대화상자 열기)")

    def _call(self, mod: JsModule, call: Node, fkey: str, state: dict) -> None:
        m = self.ctx.model
        callee = call.child_by_field_name("function")
        args_n = call.child_by_field_name("arguments")
        args = args_n.named_children if args_n else []
        ev = f"{mod.rel}:{mod.line(call)}"
        ct = text(callee)
        cond = f'confirm("{state["confirm"]}")' if state.get("confirm") else None
        # 확인창
        if re.search(r"(^|\.)(\$?confirm)$", ct) and args:
            state["confirm"] = string_value(args[0]) or text(args[0])[:60]
            return
        # 대화상자 열기: setDetailOpen(true)
        ctrl = getattr(self, "_dialog_ctrl", {})
        if callee.type == "identifier" and ct in ctrl and args and text(args[0]) == "true":
            m.add_edge(fkey, ctrl[ct], "navigates", confidence=0.9, evidence=f"{ev} (대화상자 열기)")
            return
        # 화면 이동
        if re.search(r"(router|\$router|navigate|history)\.(push|replace)$", ct) or ct in ("navigate", "navigateTo"):
            if args:
                self._nav_target(mod, args[0], fkey, ev)
            return
        # HTTP 요청
        if callee.type == "member_expression":
            obj = callee.child_by_field_name("object")
            prop = text(callee.child_by_field_name("property"))
            if prop in HTTP_METHODS:
                base = self.proj.client_base(mod, obj)
                if base is not None:
                    url, conf = self.proj.url(mod, args[0] if args else None)
                    self._request(fkey, prop.upper(), url, conf, base, cond, ev)
                    return
            if prop == "request" and self.proj.client_base(mod, obj) is not None and args and args[0].type == "object":
                self._request_obj(mod, args[0], fkey, self.proj.client_base(mod, obj) or "", cond, ev)
                return
        if callee.type == "identifier" and (ct in ("fetch",) or (ct in mod.imports and mod.imports[ct][1] == "default" and ct == "axios")):
            if ct == "fetch":
                url, conf = self.proj.url(mod, args[0] if args else None)
                method = "GET"
                if len(args) > 1 and args[1].type == "object":
                    for p in args[1].named_children:
                        if p.type == "pair" and _pkey(p.child_by_field_name("key")) == "method":
                            method = (string_value(p.child_by_field_name("value")) or "GET").upper()
                self._request(fkey, method, url, conf, self.proj.base_url or "", cond, ev)
            elif args and args[0].type == "object":
                self._request_obj(mod, args[0], fkey, "", cond, ev)
            return
        # 함수 호출
        hit = self.proj.resolve_callee(mod, callee)
        if hit:
            tkey = self._ensure_fn(*hit)
            if tkey != fkey:
                m.add_edge(fkey, tkey, "calls", confidence=0.95, condition=cond, evidence=ev)
        # 콜백 인자로 넘긴 함수
        for a in args:
            if a.type == "identifier":
                h2 = self.proj.resolve_callee(mod, a)
                if h2:
                    k2 = self._ensure_fn(*h2)
                    if k2 != fkey:
                        m.add_edge(fkey, k2, "calls", confidence=0.85, evidence=ev)

    def _request_obj(self, mod: JsModule, obj: Node, fkey: str, base: str, cond, ev) -> None:
        url, conf, method = None, 0, "GET"
        for p in obj.named_children:
            if p.type != "pair":
                continue
            k = _pkey(p.child_by_field_name("key"))
            if k == "url":
                url, conf = self.proj.url(mod, p.child_by_field_name("value"))
            elif k == "method":
                method = (string_value(p.child_by_field_name("value")) or "GET").upper()
        self._request(fkey, method, url, conf, base, cond, ev)

    def _request(self, fkey: str, method: str, url: str | None, conf: float, base: str, cond, ev) -> None:
        m = self.ctx.model
        if not url:
            m.fail("parse", fkey, f"요청 URL을 해석하지 못함 ({ev})")
            return
        if not re.match(r"https?://", url):
            prefix = base or self.proj.base_url or ""
            if prefix and not url.startswith(prefix):
                url = prefix.rstrip("/") + "/" + url.lstrip("/")
        path = self.ctx.normalize_url(re.sub(r"^https?://[^/]+", "", url))
        akey = api_key(method, path)
        m.add_api(akey, method, path)
        m.add_edge(fkey, akey, "request", confidence=conf, condition=cond, evidence=ev)

    def _nav_target(self, mod: JsModule, arg: Node, fkey: str, ev: str) -> None:
        if arg.type == "object":
            for p in arg.named_children:
                if p.type == "pair":
                    k = _pkey(p.child_by_field_name("key"))
                    v = p.child_by_field_name("value")
                    if k == "path":
                        u, c = self.proj.url(mod, v)
                        if u:
                            self.pending_nav.append((fkey, u, c, ev, False))
                    elif k == "name" and string_value(v):
                        path = self.route_by_name.get(string_value(v))
                        if path:
                            self.pending_nav.append((fkey, path, 0.95, ev, False))
            return
        u, c = self.proj.url(mod, arg)
        if u:
            self.pending_nav.append((fkey, u, c, ev, False))

    def _resolve_navigation(self) -> None:
        m = self.ctx.model
        for fkey, url, conf, ev, popup in self.pending_nav:
            target = self._screen_for_path(url)
            if not target:
                m.fail("parse", fkey, f"이동 대상 화면을 찾지 못함: {url} ({ev})")
                continue
            m.add_edge(fkey, target, "navigates", confidence=conf, evidence=ev)

    def _screen_for_path(self, url: str) -> str | None:
        path = url.split("?", 1)[0]
        if path in self.ctx.screen_by_url:
            return self.ctx.screen_by_url[path]
        segs = [s for s in path.strip("/").split("/")]
        for p, skey in self.ctx.screen_by_url.items():
            ps = [s for s in p.strip("/").split("/")]
            if len(ps) == len(segs) and all(a == b or a.startswith(":") or a.startswith("{") or b.startswith("{")
                                             for a, b in zip(ps, segs)):
                return skey
        return None


# ---------------------------------------------------------------- Vue
class VueParser(_SpaBase):
    name = "vue"
    tech = "vue"
    source_key = "vue"

    def detect(self, root: Path) -> float:
        return 1.0 if any(root.rglob("*.vue")) else 0.0

    def elements(self, file: Path, mod: JsModule | None):
        raw = file.read_text(encoding="utf-8")
        if file.suffix != ".vue":
            return [], [], lambda _m: []
        sfc = parse_sfc(raw)
        h = _VueTemplate()
        h.feed(sfc.template)
        comp_names = {n.lower(): n for n in (mod.imports if mod else {})}
        items, dialogs = [], []
        for it in h.items:
            tag = it["tag"]
            a = it["attrs"]
            orig = comp_names.get(tag.lower()) or comp_names.get(tag.replace("-", "").lower()) or tag
            if DIALOG_RE.search(orig) and orig in (mod.imports if mod else {}):
                ctrl = a.get("v-model") or a.get(":visible") or a.get(":model-value") or a.get(":modelValue") \
                    or a.get("v-if") or a.get("v-show") or a.get(":open") or a.get("v-model:visible")
                dialogs.append((orig, re.sub(r"[!\s]", "", ctrl or "") or None))
                continue
            if it["type"] is None and not it["events"]:
                continue
            if tag in ("form",):
                continue          # 폼은 컨테이너 — submit 은 보통 조회 버튼과 같은 핸들러
            if tag in ("tr", "td") and it.get("grid") is not None:
                for ev, expr in it["events"].items():   # 행 이벤트(더블클릭 등)는 그리드 요소의 이벤트로
                    it["grid"].setdefault("row_events", {})[ev] = expr
                continue
            if it["type"] is None and orig[:1].isupper():
                continue          # 사용자 컴포넌트에 이벤트만 걸린 경우는 R2+ (하위 컴포넌트 펼치기)
            typ = it["type"] or ("button" if it["events"] else "other")
            if typ == "input" and a.get("type") in ("button", "submit"):
                typ = "button"
            model = a.get("v-model") or a.get("v-model:value") or a.get(":value")
            label = self._label(it, h.labels, a)
            item = {"tag": tag, "type": typ, "attrs": a, "events": dict(it["events"]), "line": it["line"] + sfc.template_offset,
                    "label": label, "model": model, "name": a.get("name")}
            if typ == "grid":
                cols = [c for c in it["cols"] if c]
                data = a.get(":data") or a.get(":items") or a.get(":rows") or ""
                item["binding"] = (f"{data}: " if data else "cols: ") + ", ".join(cols) if cols or data else None
            elif model:
                item["binding"] = model
            if tag in ("router-link", "RouterLink") and a.get("to"):
                item["to"] = a["to"]
            item["_raw"] = it
            items.append(item)
        for item in items:                       # 행(tr) 이벤트를 그리드 요소에 합친다
            for ev, expr in (item["_raw"].get("row_events") or {}).items():
                item["events"].setdefault(ev, expr)

        def lifecycle(m2: JsModule):
            out = []
            for n in walk(m2.tree.root_node):
                if n.type == "call_expression" and text(n.child_by_field_name("function")) in ("onMounted", "onBeforeMount"):
                    args = n.child_by_field_name("arguments")
                    if args and args.named_children and args.named_children[0].type in FN_TYPES:
                        out.append((text(n.child_by_field_name("function")), args.named_children[0]))
            for hook in ("created", "mounted"):
                if hook in m2.functions and m2.functions[hook].type == "method_definition":
                    out.append((hook, m2.functions[hook]))
            return out
        return items, dialogs, lifecycle

    @staticmethod
    def _label(it: dict, labels: dict, a: dict) -> str:
        for cand in (re.sub(r"\s+", " ", it.get("text") or "").strip(), a.get("label"), labels.get(a.get("id", ""), ""),
                     it.get("form_label"), a.get("placeholder"), a.get("title"), a.get("aria-label")):
            if cand and re.search(r"[0-9A-Za-z가-힣]", cand) and "{{" not in cand:
                return cand.strip()
        heads = [x for x in it.get("heads", []) if x]
        if heads:
            return "목록(" + ", ".join(heads[:3]) + ("…" if len(heads) > 3 else "") + ")"
        return ""


# ---------------------------------------------------------------- React
JSX_EVENTS = {"onClick": "click", "onDoubleClick": "dblclick", "onChange": "change", "onSubmit": "submit",
              "onKeyUp": "keyup", "onKeyDown": "keydown", "onRowClick": "click", "onRowDoubleClick": "dblclick",
              "onCellDoubleClick": "dblclick", "onBlur": "blur", "onSelect": "change"}


class ReactParser(_SpaBase):
    name = "react"
    tech = "react"
    source_key = "react"

    def detect(self, root: Path) -> float:
        return 1.0 if any(root.rglob("*.jsx")) or any(root.rglob("*.tsx")) else 0.0

    def elements(self, file: Path, mod: JsModule | None):
        items, dialogs = [], []
        if mod is None:
            return items, dialogs, lambda _m: []
        setters: dict[str, str] = {}
        for n in walk(mod.tree.root_node):
            # const [open, setOpen] = useState(false)
            if n.type == "variable_declarator" and n.child_by_field_name("name") is not None \
                    and n.child_by_field_name("name").type == "array_pattern":
                ids = [text(x) for x in n.child_by_field_name("name").named_children]
                if len(ids) == 2:
                    setters[ids[0]] = ids[1]
        seq = 0
        for n in walk(mod.tree.root_node):
            if n.type not in ("jsx_self_closing_element", "jsx_opening_element"):
                continue
            tag = text(n.child_by_field_name("name"))
            attrs: dict[str, Node | None] = {}
            for a in n.named_children:
                if a.type == "jsx_attribute" and a.named_children:
                    attrs[text(a.named_children[0])] = a.named_children[1] if len(a.named_children) > 1 else None
            if DIALOG_RE.search(tag) and tag in mod.imports:
                ctrl = attrs.get("open") or attrs.get("visible") or attrs.get("show") or attrs.get("isOpen")
                cname = re.sub(r"[{}!\s]", "", text(ctrl)) if ctrl is not None else ""
                dialogs.append((tag, setters.get(cname) or cname or None))
                continue
            typ = TAG_TYPES.get(tag)
            evs = {JSX_EVENTS[k]: v for k, v in attrs.items() if k in JSX_EVENTS}
            if typ is None and not evs:
                continue
            if typ is None and tag[:1].isupper():
                continue
            seq += 1
            sv = lambda k: (string_value(attrs[k]) if attrs.get(k) is not None else None)  # noqa: E731
            label = ""
            if n.type == "jsx_opening_element" and n.parent is not None:
                label = re.sub(r"\s+", " ", " ".join(text(c) for c in n.parent.named_children if c.type == "jsx_text")).strip()
            label = label or sv("label") or sv("placeholder") or sv("title") or sv("aria-label") or ""
            item = {"tag": tag, "type": typ or "button", "attrs": {k: (sv(k) or "") for k in ("id", "name", "data-testid")},
                    "events": {}, "line": mod.line(n), "label": label, "name": sv("name")}
            if typ == "grid":
                cols = re.findall(r"field:\s*['\"](\w+)['\"]", mod.src) if tag == "DataGrid" else \
                    [re.sub(r"\s+", " ", text(c)).strip() for c in walk(n.parent or n) if c.type == "jsx_text" and text(c).strip()][:8]
                item["binding"] = "cols: " + ", ".join(cols) if cols else None
            if tag in ("Link", "NavLink") and sv("to"):
                item["to"] = sv("to")
            for ev, vnode in evs.items():
                item["events"][ev] = text(vnode)[1:-1] if vnode is not None and vnode.type == "jsx_expression" else text(vnode)
            items.append(item)

        def lifecycle(m2: JsModule):
            out = []
            for n in walk(m2.tree.root_node):
                if n.type == "call_expression" and text(n.child_by_field_name("function")) in ("useEffect", "useLayoutEffect"):
                    args = n.child_by_field_name("arguments")
                    a = args.named_children if args else []
                    if len(a) >= 2 and a[0].type in FN_TYPES and text(a[1]).replace(" ", "") == "[]":
                        out.append(("useEffect", a[0]))
            return out
        return items, dialogs, lifecycle


# ---------------------------------------------------------------- 라우터 → 메뉴
def menu_rows_from_router(ctx: Context) -> list[dict]:
    """SPA 라우트(meta.title·menuGroup·menuOrder) → 메뉴 행 (menu 파서가 사용)."""
    rows: list[dict] = []
    groups: dict[str, str] = {}
    for i, r in enumerate(ctx.spa_routes, 1):
        if not r.get("title") or str(r.get("hideInMenu", "")).lower() == "true":
            continue
        g = r.get("menuGroup")
        parent = ""
        if g:
            if g not in groups:
                groups[g] = f"G{len(groups) + 1:02d}"
                rows.append({"MENU_ID": groups[g], "UP_MENU_ID": "", "MENU_NM": g, "MENU_URL": "", "SORT_ORD": str(len(groups))})
            parent = groups[g]
        sid = r.get("screenId") or r.get("name") or f"R{i:03d}"
        order = re.sub(r"\D", "", str(r.get("menuOrder", ""))) or str(i)
        rows.append({"MENU_ID": str(sid), "UP_MENU_ID": parent, "MENU_NM": str(r["title"]), "MENU_URL": r["path"], "SORT_ORD": order})
    return rows
