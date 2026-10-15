"""JSP + jQuery 화면 파서.

화면(JSP) → UI 요소, 스크립트(외부 js·인라인) → 함수 단위 코드,
이벤트 바인딩(onclick 속성, $(선택자).on/click…, 위임 $(document).on('click', '#id', fn)) → event 엣지,
함수는 선언문뿐 아니라 함수 표현식(var f = function / const f = () =>)·객체 메서드(obj.fn)도 인식,
선택자는 #id 외에 .class·[attr=v]·tag[attr] 를 정적 요소 속성으로 매칭,
공통 래퍼(gfn_ajax 등, 설정 frontApis) / $.ajax → request 엣지,
팝업·화면 이동(설정 frontNavigations, window.open, location.href) → navigates 엣지.
"""
from __future__ import annotations

import copy
import re
from collections import Counter
from dataclasses import dataclass
from html.parser import HTMLParser
from pathlib import Path

from tree_sitter import Node

from ..keys import api_key, el_key, fn_key, scr_key
from ..textio import read_text_auto
from . import Context
from .ts import parse, string_value, text
from .ui_common import link_menus, link_server, refine_popup_types, screen_for_url

BIND_METHODS = {"on", "bind", "delegate", "live", "one", "click", "dblclick", "change", "keydown", "keyup",
                "keypress", "blur", "focus", "submit", "select", "input", "mousedown", "mouseup"}
JQ = {"$", "jQuery"}
FN_TYPES = ("function_expression", "arrow_function", "function", "generator_function")
STRINGISH = ("string", "template_string", "binary_expression", "identifier", "parenthesized_expression")
INLINE_EVENTS = {"onclick": "click", "ondblclick": "dblclick", "onchange": "change", "onkeydown": "keydown",
                 "onkeyup": "keyup", "onblur": "blur", "onfocus": "focus"}


# ---------------------------------------------------------------- HTML
def _blank(m: re.Match) -> str:
    return re.sub(r"[^\n]", " ", m.group(0))


def preprocess_jsp(src: str) -> str:
    """줄 번호를 유지한 채 JSP 주석·지시자를 공백으로 지운다."""
    src = re.sub(r"<%--.*?--%>", _blank, src, flags=re.S)
    return re.sub(r"<%[@!=]?.*?%>", _blank, src, flags=re.S)


def resolve_attr(v: str | None) -> str | None:
    if v is None:
        return None
    return re.sub(r"<c:url\s+value=['\"]([^'\"]+)['\"]\s*/>", r"\1", v)


class _Html(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.title = ""
        self.caption = ""
        self.body_class = ""
        self.body_onload = ""
        self.forms: list[dict] = []
        self.scripts: list[str] = []                  # 외부 src
        self.inline: list[tuple[int, str]] = []       # (시작 줄, 코드)
        self.elements: list[dict] = []
        self.labels: dict[str, str] = {}
        self._in: str | None = None
        self._buf: list[str] = []
        self._line = 0
        self._label_for: str | None = None
        self._btn: dict | None = None
        self._table: dict | None = None
        self._form: str | None = None

    def handle_starttag(self, tag, attrs):
        a = {k: resolve_attr(v) for k, v in attrs}
        line = self.getpos()[0]
        if tag == "title":
            self._in, self._buf = "title", []
        elif tag == "body":
            self.body_class = a.get("class") or ""
            self.body_onload = a.get("onload") or ""
        elif tag == "caption" and not self.caption:
            self._in, self._buf = "caption", []
        elif tag == "script":
            if a.get("src"):
                self.scripts.append(a["src"])
            else:
                self._in, self._buf, self._line = "script", [], line
        elif tag == "label" and a.get("for"):
            self._label_for, self._buf = a["for"], []
        elif tag == "form":
            self._form = a.get("id") or a.get("name")
            self.forms.append({"name": a.get("name"), "id": a.get("id"), "action": (a.get("action") or "").strip()})
        elif tag in ("button", "input", "select", "textarea", "a", "table") or (tag in CLICKABLE_TAGS and a.get("onclick")):
            if tag == "input" and (a.get("type") or "").lower() == "hidden":
                return
            href = (a.get("href") or "").strip()
            js_href = href.lower().startswith("javascript:") and len(href) > len("javascript:") + 2
            if tag == "a" and not (a.get("id") or a.get("onclick") or js_href):
                return
            if js_href and not a.get("onclick"):
                a["onclick"] = href[len("javascript:"):]
            if tag == "table" and not a.get("id"):
                return
            el = {"tag": tag, "type": (a.get("type") or "").lower(), "id": a.get("id") or "", "name": a.get("name") or "",
                  "title": a.get("title") or a.get("alt") or "", "placeholder": a.get("placeholder") or "", "value": a.get("value") or "",
                  "line": line, "form": self._form, "events": {k: v for k, v in a.items() if k in INLINE_EVENTS and v},
                  "text": "", "cols": [], "cls": (a.get("class") or "").split(),
                  "attrs": {k: (v or "") for k, v in a.items()}}
            self.elements.append(el)
            if tag in ("button", "a"):
                self._btn, self._buf = el, []
            if tag == "table":
                self._table = el
        elif tag == "th" and self._table is not None and a.get("data-col"):
            self._table["cols"].append(a["data-col"])

    def handle_endtag(self, tag):
        if tag == "title" and self._in == "title":
            self.title, self._in = "".join(self._buf).strip(), None
        elif tag == "caption" and self._in == "caption":
            self.caption, self._in = re.sub(r"\s+", " ", "".join(self._buf)).strip(), None
        elif tag == "script" and self._in == "script":
            self.inline.append((self._line, "".join(self._buf)))
            self._in = None
        elif tag == "label" and self._label_for:
            self.labels[self._label_for] = "".join(self._buf).strip()
            self._label_for = None
        elif tag in ("button", "a") and self._btn is not None:
            self._btn["text"] = re.sub(r"\s+", " ", "".join(self._buf)).strip()
            self._btn = None
        elif tag == "table":
            self._table = None
        elif tag == "form":
            self._form = None

    def handle_data(self, data):
        if self._in == "script" and not self._buf:
            self._line = self.getpos()[0]
        if self._in or self._label_for or self._btn is not None:
            self._buf.append(data)


CLICKABLE_TAGS = {"img", "span", "div", "li", "td", "tr", "label", "em", "strong", "dd", "dt", "p"}


def static_type(tag: str, typ: str) -> str:
    if tag in CLICKABLE_TAGS:
        return "button"
    if tag == "button" or (tag == "input" and typ in ("button", "submit", "reset", "image")):
        return "button"
    if tag == "select":
        return "combo"
    if tag == "table":
        return "grid"
    if tag == "a":
        return "link"
    if tag == "input":
        return {"date": "date", "checkbox": "check", "radio": "radio", "file": "file"}.get(typ, "input")
    return "input"


def _has_word(s: str) -> bool:
    return bool(re.search(r"[0-9A-Za-z가-힣]", s or ""))


def element_label(el: dict, labels: dict[str, str]) -> str:
    for cand in (el["text"], el["value"] if el["tag"] == "input" else "", labels.get(el["id"], ""), el["title"], el["placeholder"]):
        if _has_word(cand):
            return cand.strip()
    return el["id"] or el["name"] or el["tag"]


# ---------------------------------------------------------------- 선택자
_COND_RE = re.compile(r"#([\w-]+)|\.([\w-]+)|\[\s*([\w-]+)\s*(?:([~|^$*]?=)\s*[\"']?([^\"'\]]*)[\"']?)?\s*\]|:[\w-]+(?:\([^)]*\))?")
_TAG_RE = re.compile(r"^([a-zA-Z][\w-]*)")


def _last_compound(sel: str) -> str:
    """'#frm .btn-area button[data-act=x]' → 'button[data-act=x]' (대괄호 안 공백·기호는 무시)."""
    depth, cut = 0, 0
    for i, ch in enumerate(sel):
        if ch in "[(":
            depth += 1
        elif ch in "])":
            depth -= 1
        elif depth == 0 and (ch.isspace() or ch in ">+~"):
            cut = i + 1
    return sel[cut:].strip()


def match_selector(sel: str, els: list[dict]) -> list[dict]:
    """jQuery 선택자(쉼표 목록 가능)의 마지막 compound 를 정적 요소의 tag·id·class·속성으로 매칭."""
    out: list[dict] = []
    for part in sel.split(","):
        comp = _last_compound(part.strip())
        if not comp:
            continue
        tm = _TAG_RE.match(comp)
        tag = tm.group(1).lower() if tm else None
        rest = comp[tm.end():] if tm else comp
        conds = [c for c in _COND_RE.finditer(rest)]
        if "".join(c.group(0) for c in conds) != rest:
            continue                     # 해석 못 하는 문법이 섞이면 매칭하지 않음
        for el in els:
            if tag and el["tag"] != tag:
                continue
            ok = True
            for c in conds:
                id_, cls, attr, op, val = c.groups()
                if id_ is not None:
                    ok = el["id"] == id_
                elif cls is not None:
                    ok = cls in el.get("cls", [])
                elif attr is not None:
                    v = el.get("attrs", {}).get(attr)
                    ok = v is not None and (
                        not op or (op == "=" and v == val) or (op == "^=" and v.startswith(val))
                        or (op == "$=" and v.endswith(val)) or (op == "*=" and val in v)
                        or (op == "~=" and val in v.split()) or (op == "|=" and (v == val or v.startswith(val + "-"))))
                if not ok:
                    break
            if ok and el not in out:
                out.append(el)
    return out


# ---------------------------------------------------------------- JS
@dataclass
class Fn:
    key: str
    name: str
    node: Node            # function_declaration / function_expression / arrow_function / method_definition
    file: str
    lang: str
    line: int
    common: bool


@dataclass
class JsUnit:
    file: str             # 웹 경로 (/js/hr/HR0101.js)
    lang: str             # js | jsp(인라인)
    src: str
    offset: int           # 인라인 스크립트 시작 줄 - 1
    common: bool
    tree: object = None
    functions: dict[str, Fn] | None = None
    consts: dict[str, Node] | None = None     # 최상위 문자열 상수 (var SAVE_PATH = "?method=save")

    def load(self):
        if self.tree is None:
            self.tree = parse("js", self.src)
            self.functions = {}
            self.consts = {}
            for n in self.tree.root_node.named_children:
                if n.type == "function_declaration":
                    self._add(text(n.child_by_field_name("name")), n)
                elif n.type in ("lexical_declaration", "variable_declaration"):
                    # var fn_x = function(){} / const fn_y = () => {} / var Page = { search: function(){}, save(){} }
                    for d in n.named_children:
                        if d.type == "variable_declarator":
                            self._value(text(d.child_by_field_name("name")), d.child_by_field_name("value"))
                            v = d.child_by_field_name("value")
                            if v is not None and v.type in STRINGISH:
                                self.consts[text(d.child_by_field_name("name"))] = v
                elif n.type == "expression_statement" and n.named_children and n.named_children[0].type == "assignment_expression":
                    a = n.named_children[0]
                    left = text(a.child_by_field_name("left"))
                    left = left[len("window."):] if left.startswith("window.") else left
                    if re.fullmatch(r"[\w$]+(?:\.[\w$]+)?", left):
                        self._value(left, a.child_by_field_name("right"))
        return self

    def _add(self, name: str, node: Node):
        self.functions[name] = Fn(fn_key(self.lang, self.file, name), name, node, self.file, self.lang,
                                  node.start_point[0] + 1 + self.offset, self.common)

    def _value(self, name: str, val: Node | None):
        if val is None or not name:
            return
        if val.type in FN_TYPES:
            self._add(name, val)
        elif val.type == "object":
            for p in val.named_children:
                if p.type == "pair":
                    v = p.child_by_field_name("value")
                    if v is not None and v.type in FN_TYPES:
                        self._add(f"{name}.{text(p.child_by_field_name('key')).strip(chr(39) + chr(34))}", v)
                elif p.type == "method_definition":
                    self._add(f"{name}.{text(p.child_by_field_name('name'))}", p)

    def line(self, node: Node) -> int:
        return node.start_point[0] + 1 + self.offset


def url_arg(node: Node | None) -> tuple[str | None, float]:
    """URL 인자 → (url, 신뢰도). 리터럴 0.95 / 'a' + b 접두 0.85 / `a${b}` 0.8."""
    if node is None:
        return None, 0
    v = string_value(node)
    if v is not None and "${" not in v:
        return v, 0.95
    if node.type == "binary_expression":
        left = node.child_by_field_name("left")
        while left is not None and left.type == "binary_expression":
            left = left.child_by_field_name("left")
        lv = string_value(left)
        if lv:
            # "/api/x/" + id → "/api/x/{id}" (경로 변수 자리) — 서버의 /api/x/{id} 매핑과 맞춘다
            right = node.child_by_field_name("right")
            if lv.endswith("/") and node.child_by_field_name("left") == left and right is not None                     and right.type in ("identifier", "member_expression"):
                return lv + "{" + text(right).rsplit(".", 1)[-1] + "}", 0.85
            return lv, 0.85
    if node.type == "template_string" and v:
        return v.split("${", 1)[0], 0.8
    return None, 0


def is_ready_call(call: Node) -> Node | None:
    """$(document).ready(fn) / $(fn) / jQuery(function(){}) → 함수 노드."""
    callee = call.child_by_field_name("function")
    args = call.child_by_field_name("arguments")
    fargs = [a for a in (args.named_children if args else []) if a.type in ("function_expression", "arrow_function", "function")]
    if not fargs:
        return None
    if callee.type == "identifier" and text(callee) in JQ:
        return fargs[0]
    if callee.type == "member_expression" and text(callee.child_by_field_name("property")) == "ready":
        return fargs[0]
    return None


def binding_of(call: Node) -> tuple[str | None, str | None, list[str], Node | None] | None:
    """이벤트 바인딩 해석 → (바깥 선택자, 위임 선택자, 이벤트들, 핸들러). 바인딩이 아니면 None.

    $('#id').on('click', h)                 → ('#id', None, ['click'], h)
    $(document).on('click', '#btn', h)       → (None, '#btn', ['click'], h)
    $('#grd').on('dblclick', 'tbody tr', h)  → ('#grd', 'tbody tr', ['dblclick'], h)
    $('#wrap').delegate('.btn', 'click', h)  → ('#wrap', '.btn', ['click'], h)
    """
    callee = call.child_by_field_name("function")
    if callee is None or callee.type != "member_expression":
        return None
    prop = text(callee.child_by_field_name("property"))
    obj = callee.child_by_field_name("object")
    if prop not in BIND_METHODS or obj is None or obj.type != "call_expression":
        return None
    oc = obj.child_by_field_name("function")
    oargs = obj.child_by_field_name("arguments")
    if oc is None or text(oc) not in JQ or not oargs or not oargs.named_children:
        return None
    outer = string_value(oargs.named_children[0])      # $(document)/$(window)/$(this) 는 None
    args = call.child_by_field_name("arguments").named_children
    inner = None
    if prop == "delegate":
        if len(args) < 3:
            return None
        inner, evs, handler = string_value(args[0]), (string_value(args[1]) or "").split(), args[-1]
    elif prop in ("on", "bind", "live", "one"):
        if not args:
            return None
        evs = (string_value(args[0]) or "").split()
        handler = args[-1] if len(args) > 1 else None
        if prop in ("on", "one") and len(args) >= 3 and string_value(args[1]) is not None:
            inner = string_value(args[1])
    else:
        if not args:
            return None          # $('#x').click() = 트리거, 바인딩 아님
        evs, handler = [prop], args[-1]
    if outer is None and inner is None:
        return None
    return outer, inner, [e.split(".")[0] for e in evs], handler


# ---------------------------------------------------------------- 파서
class JspJqueryParser:
    name = "jsp-jquery"
    layer = "ui"
    ui_tech = "jsp-jquery"
    inline_lang = "jsp"               # 화면 파일 안 <script> 코드 단위의 lang

    # ---------- 하위 파서(ASP.NET 등)가 바꾸는 지점 ----------
    def preprocess(self, raw: str, path: Path) -> str:
        return preprocess_jsp(raw)

    def screen_id(self, path: Path) -> str:
        return path.stem

    def detect(self, root: Path) -> float:
        return 1.0 if any(root.rglob("*.jsp")) else 0.0

    def parse(self, ctx: Context) -> None:
        cfg, m = ctx.config, ctx.model
        self.ctx = ctx
        self.webapp = cfg.source_dir("webapp")
        jsp_root = cfg.source_dir("jsp")
        if not jsp_root or not jsp_root.is_dir():
            m.fail("parse", "source.jsp", "JSP 경로 없음")
            return
        self.jsp_root = jsp_root
        self.fapis: dict = cfg.get("frontApis", {}) or {}
        self.fnavs: dict = cfg.get("frontNavigations", {}) or {}
        self.pending_nav: list[tuple[str, str, float, str, bool]] = []
        self.common_units: dict[str, JsUnit] = {}
        self.common_scope: dict[str, Fn] = {}
        self.fn_obj: dict[str, str] = {}
        view_url: dict[str, str] = {}
        for r in ctx.routes:
            if r.kind == "screen" and r.view:
                view_url.setdefault(r.view, r.path)
        jsps = sorted(jsp_root.rglob("*.jsp"))
        skip = [re.compile(x) for x in (cfg.get("source.skipJsp") or [])]
        jsps = [j for j in jsps if not any(rx.search(j.relative_to(jsp_root).as_posix()) for rx in skip)]
        stems = Counter(j.stem for j in jsps)
        self._dup_stems = {k for k, n in stems.items() if n > 1}

        for jsp in jsps:
            view = jsp.relative_to(jsp_root).with_suffix("").as_posix()
            self._view = view
            self._screen(jsp, view_url.get(view))

        self._resolve_navigation()
        self._resolve_opener_calls()
        self._drop_unrouted_relative()
        link_menus(ctx)                 # 연결 못 한 메뉴는 파이프라인 마지막(finalize_ui)에서 기록
        link_server(ctx)
        refine_popup_types(ctx)

    def _resolve_opener_calls(self):
        """opener.onPopupSave(str) → 이 팝업을 연 화면의 onPopupSave 함수."""
        pend = getattr(self, "pending_opener", [])
        if not pend:
            return
        m = self.ctx.model
        fn_screens = getattr(self, "fn_screens", {})
        scope = getattr(self, "screen_scope", {})
        openers: dict[str, set[str]] = {}
        for e in list(m.edges.values()):
            if e["kind"] == "navigates" and e["to"] in m.screens:
                for s in fn_screens.get(e["from"], ()):
                    openers.setdefault(e["to"], set()).add(s)
        for fkey, name, ev in pend:
            for popup in fn_screens.get(fkey, ()):
                for o in openers.get(popup, ()):
                    k = scope.get(o, {}).get(name)
                    if k and k != fkey:
                        m.add_edge(fkey, k, "calls", confidence=0.8, evidence=f"{ev} (부모 창 {o.split(':', 2)[-1]})")

    def _drop_unrouted_relative(self):
        """공유 JS 의 상대 URL('?method=x')을 화면마다 다른 Action 기준으로 해석하면, 실제로 그 화면에서 쓰지 않는
        조합이 생긴다. 서버 매핑이 없는 '상대 URL 해석 결과'는 오탐으로 보고 제거(경고로 집계)한다."""
        rel = getattr(self, "_rel_apis", set()) - getattr(self, "_abs_apis", set())
        if not rel or not self.ctx.routes:
            return
        m = self.ctx.model
        drop = {a for a in rel if a in m.apis and not self.ctx.route_for(m.apis[a]["path"])}
        if not drop:
            return
        for k in [k for k, e in m.edges.items() if e["to"] in drop or e["from"] in drop]:
            del m.edges[k]
        for a in drop:
            m.apis.pop(a, None)
        m.warn("parse", "relative-url", f"공유 스크립트의 상대 URL 해석 중 서버 매핑이 없는 조합 {len(drop)}건 제외 (화면별 Action 기준 오탐)")

    # ---------- 화면 ----------
    def web_path(self, p: Path) -> str:
        try:
            return "/" + p.resolve().relative_to(self.webapp.resolve()).as_posix()
        except (ValueError, AttributeError):
            return "/" + p.name

    def _jsp_vars(self, path: Path) -> dict[str, str]:
        """JSP 표현식 치환값 (<%= JS_PATH %> → /html/script/js/). source.jspVars + 경로별 source.jspVarsByPath."""
        cfg = self.ctx.config
        out = {"CONTEXT_PATH": "", "request.getContextPath()": "", "ctx": ""}
        out.update({k: str(v) for k, v in (cfg.get("source.jspVars") or {}).items()})
        root = getattr(self, "jsp_root", None)
        rel = ""
        if root is not None:
            try:
                rel = path.resolve().relative_to(root.resolve()).as_posix()
            except ValueError:
                rel = ""
        for prefix, vals in sorted((cfg.get("source.jspVarsByPath") or {}).items(), key=lambda kv: len(kv[0])):
            if rel.startswith(prefix):
                out.update({k: str(v) for k, v in vals.items()})
        return out

    def _substitute(self, raw: str, path: Path) -> str:
        cfg = self.ctx.config
        if not (cfg.get("source.jspVars") or cfg.get("source.jspVarsByPath")):
            return raw
        vars_ = self._jsp_vars(path)

        def sub(mm: re.Match) -> str:
            k = mm.group(1).strip()
            return vars_[k] if k in vars_ else mm.group(0)
        raw = re.sub(r"<%=\s*([\w.()]+)\s*%>", sub, raw)
        return re.sub(r"\$\{\s*pageContext\.request\.contextPath\s*\}", "", raw)

    def script_file(self, path: str) -> Path | None:
        """스크립트 URL 경로 → 파일. source.scriptRoots(URL 접두 → 폴더)가 있으면 먼저, 없으면 webapp 기준."""
        cfg = self.ctx.config
        for prefix, folder in (cfg.get("source.scriptRoots") or {}).items():
            if path.startswith(prefix):
                f = cfg.resolve(folder) / path[len(prefix):].lstrip("/")
                if f.is_file():
                    return f
        f = (self.webapp / path.lstrip("/")) if self.webapp else None
        return f if f is not None and f.is_file() else None

    def _include_paths(self, raw: str, path: Path) -> list[Path]:
        out: list[Path] = []
        for mm in re.finditer(r"<%@\s*include\s+file\s*=\s*[\"']([^\"']+)[\"']|<jsp:include\s+page\s*=\s*[\"']([^\"'<%]+)[\"']", raw):
            ref = (mm.group(1) or mm.group(2) or "").strip()
            if not ref:
                continue
            f = (self.webapp / ref.lstrip("/")) if ref.startswith("/") and self.webapp else (path.parent / ref)
            if f.is_file():
                out.append(f.resolve())
        return out

    def _include_html(self, f: Path) -> "_Html":
        cache = self.__dict__.setdefault("_inc_cache", {})
        if f not in cache:
            h = _Html()
            h.feed(self.preprocess(self._substitute(read_text_auto(f), f), f))
            cache[f] = h
        return cache[f]

    def screen_id(self, path: Path) -> str:
        if path.stem in getattr(self, "_dup_stems", ()) and getattr(self, "jsp_root", None) is not None:
            try:
                return path.resolve().relative_to(self.jsp_root.resolve()).with_suffix("").as_posix().replace("/", ".")
            except ValueError:
                pass
        return path.stem

    def _screen(self, jsp: Path, url: str | None):
        ctx, cfg, m = self.ctx, self.ctx.config, self.ctx.model
        sid = self.screen_id(jsp)
        skey = scr_key(cfg.sys_code, sid)
        raw = self._substitute(read_text_auto(jsp), jsp)
        h = _Html()
        h.feed(self.preprocess(raw, jsp))
        # 정적 include (<%@ include file %>, <jsp:include page>) — 요소·스크립트를 화면에 합친다
        includes: list[Path] = []
        if cfg.get("source.jspIncludes"):
            todo = self._include_paths(raw, jsp)
            while todo and len(includes) < 30:
                f = todo.pop(0)
                if f in includes:
                    continue
                includes.append(f)
                todo += [x for x in self._include_paths(self._substitute(read_text_auto(f), f), f) if x not in includes]
        popup_rx = cfg.get("source.popupPattern") or r"P\d{2}$"
        screen = {"key": skey, "screenId": sid, "name": h.title or h.caption or sid,
                  "kind": "popup" if ("popup" in h.body_class.split() or re.search(popup_rx, sid)) else "page",
                  "uiTech": self.ui_tech, "sourceFile": cfg.rel(jsp), "captures": []}
        if url:
            screen["url"] = url
            ctx.screen_by_url[url] = skey
        m.screens[skey] = screen
        view = getattr(self, "_view", None)
        bases = ctx.view_actions.get(view, []) if view else []
        if not bases and view and ctx.view_actions:
            # 어느 Action 도 forward 하지 않는 JSP: 같은 폴더의 화면들을 보여 주는 Action 이 하나뿐이면 그것으로 추정
            by_dir = self.__dict__.get("_dir_actions")
            if by_dir is None:
                by_dir = {}
                for v, bs in ctx.view_actions.items():
                    by_dir.setdefault(v.rsplit("/", 1)[0], set()).update(bs)
                self._dir_actions = by_dir
            cand = by_dir.get(view.rsplit("/", 1)[0], set())
            if len(cand) == 1:
                bases = list(cand)
        self.screen_base = bases[0] if bases else (url if url and ".do" in url else None)

        inc_inline: list[tuple[str, int, str]] = []
        all_elements = list(h.elements)
        for f in includes:
            hi = self._include_html(f)
            inc_web = self.web_path(f)
            for el in copy.deepcopy(hi.elements):
                el["_src"] = cfg.rel(f)
                all_elements.append(el)
            for k, v in hi.labels.items():
                h.labels.setdefault(k, v)
            h.scripts += [x for x in hi.scripts if x not in h.scripts]
            inc_inline += [(inc_web, line, code) for line, code in hi.inline]
            if not h.body_onload and hi.body_onload:
                h.body_onload = hi.body_onload
            h.forms += hi.forms

        # 요소 (정적)
        self.el_by_id: dict[str, str] = {}
        self.static_meta: list[dict] = []          # 선택자 매칭용 (정적 요소 + 키)
        used: set[str] = set()
        for seq, el in enumerate(all_elements, 1):
            eid = el["id"] or (f"name:{el['name']}" if el["name"] else f"{el['tag']}{seq}")
            if eid in used:
                eid = f"{eid}.{seq}"
            used.add(eid)
            ekey = el_key(skey, eid)
            entry = {"key": ekey, "screenKey": skey, "elementId": eid, "label": element_label(el, h.labels),
                     "type": static_type(el["tag"], el["type"]), "matched": "static-only",
                     "_seq": seq, "_tag": el["tag"], "_name": el["name"]}
            if el["name"]:
                entry["name"] = el["name"]
            if el["cols"]:
                entry["binding"] = "cols: " + ", ".join(el["cols"])
            elif el["name"]:
                entry["binding"] = f"{el['form']}.{el['name']}" if el["form"] else el["name"]
            m.elements[ekey] = entry
            el["_key"] = ekey
            self.static_meta.append(el)
            if el["id"] and el["id"] not in self.el_by_id:
                self.el_by_id[el["id"]] = ekey

        # 스크립트 수집
        skip_rx = [re.compile(x) for x in (cfg.get("source.skipScripts") or [])]
        units: list[JsUnit] = []
        for src in h.scripts:
            path = ctx.normalize_url(src)
            if "/vendor/" in path or re.search(r"jquery[-.\w]*?(\.min)?\.js$", path) or any(rx.search(path) for rx in skip_rx):
                continue                     # 외부 라이브러리는 분석 대상 아님 (파일이 없어도 실패로 보지 않음)
            f = self.script_file(path)
            if f is None:
                m.fail("parse", skey, f"스크립트 파일 없음: {src}")
                continue
            if any(path.startswith(c) for c in cfg.common_scripts):
                self._common(path, f)
                continue
            units.append(self._unit(path, f))
        jsp_web = self.web_path(jsp)
        for line, code in h.inline:
            if code.strip():
                units.append(JsUnit(jsp_web, self.inline_lang, code, line - 1, False).load())
        inc_units: list[JsUnit] = []
        for web, line, code in inc_inline:
            if code.strip():
                inc_units.append(self._inc_unit(web, line, code))

        scope = dict(self.common_scope)
        consts: dict[str, Node] = dict(getattr(self, "common_consts", {}))
        for u in inc_units + units:
            scope.update(u.functions)
            consts.update(u.consts or {})
        self.scope, self.skey, self.consts = scope, skey, consts
        self.form_action = next((f["action"] for f in h.forms if f["action"] and not f["action"].lower().startswith("javascript")), None)
        own = {n: fn.key for u in inc_units + units for n, fn in u.functions.items()}
        self.__dict__.setdefault("screen_scope", {})[skey] = own
        fs = self.__dict__.setdefault("fn_screens", {})
        for k in own.values():
            fs.setdefault(k, set()).add(skey)

        for u in units:
            for fn in u.functions.values():
                self._register_fn(u, fn)
                self._scan(u, fn.node, fn.key)
            self._toplevel(u)
        for u in inc_units:
            if not getattr(u, "_scanned", False):
                u._scanned = True
                for fn in u.functions.values():
                    self._register_fn(u, fn)
                    self._scan(u, fn.node, fn.key)
            self._toplevel(u)

        # 인라인 이벤트 속성 (onclick="fn_search()")
        for el in all_elements:
            ekey = el.get("_key")
            for attr, code in el["events"].items():
                if not ekey:
                    continue
                t = parse("js", code)
                linked = False
                for n in self._calls_in(t.root_node):
                    fn = self._resolve(n.child_by_field_name("function"), None)
                    if fn:
                        linked = True
                        m.add_edge(ekey, fn.key, "event", event=INLINE_EVENTS[attr], confidence=0.99,
                                   evidence=f"{el.get('_src') or cfg.rel(jsp)}:{el['line']}")
                if not linked and re.search(r"location|\.action\s*=|\.submit\(|window\.open|\$\.(ajax|post|get)", code):
                    # 함수 없이 속성에 직접 쓴 처리 (onclick="location.href='/x.do?method=list'") → 인라인 코드 단위
                    src_file = self.web_path(jsp) if not el.get("_src") else "/" + el["_src"]
                    key = fn_key(self.inline_lang, src_file, f"inline@L{el['line']}")
                    u = JsUnit(src_file, self.inline_lang, code, el["line"] - 1, False).load()
                    m.add_code_unit(key, self.inline_lang, src_file, f"{el.get('text') or el['tag']} {attr}",
                                    el["line"], el["line"], code if cfg.include_snippets else None)
                    self._scan(u, t.root_node if u.tree is None else u.tree.root_node, key)
                    m.add_edge(ekey, key, "event", event=INLINE_EVENTS[attr], confidence=0.99,
                               evidence=f"{el.get('_src') or cfg.rel(jsp)}:{el['line']}")
        # <body onload="onInit();">
        if h.body_onload:
            t = parse("js", h.body_onload)
            for n in self._calls_in(t.root_node):
                fn = self._resolve(n.child_by_field_name("function"), None)
                if fn:
                    m.add_edge(skey, fn.key, "event", event="onload", confidence=0.99, evidence=f"{cfg.rel(jsp)}:body")
                    ctx.screen_onload.setdefault(skey, []).append(fn.key)

    def _unit(self, path: str, f: Path) -> JsUnit:
        """화면별 외부 JS — 같은 파일은 한 번만 구문 분석해 재사용(대규모 시스템)."""
        cache = self.__dict__.setdefault("_unit_cache", {})
        u = cache.get(path)
        if u is None:
            u = JsUnit(path, "js", read_text_auto(f), 0, False).load()
            cache[path] = u
        return u

    def _inc_unit(self, web: str, line: int, code: str) -> JsUnit:
        cache = self.__dict__.setdefault("_inc_unit_cache", {})
        k = (web, line)
        if k not in cache:
            cache[k] = JsUnit(web, self.inline_lang, code, line - 1, False).load()
        return cache[k]

    def _common(self, path: str, f: Path):
        if path in self.common_units:
            return
        u = JsUnit(path, "js", read_text_auto(f), 0, True).load()
        self.common_units[path] = u
        self.common_scope.update(u.functions)
        self.__dict__.setdefault("common_consts", {}).update(u.consts or {})
        saved = getattr(self, "scope", None)
        self.scope = dict(self.common_scope)
        for fn in u.functions.values():
            self._register_fn(u, fn)
            self._scan(u, fn.node, fn.key)
        self.scope = saved

    def _register_fn(self, u: JsUnit, fn: Fn):
        cfg = self.ctx.config
        node = fn.node
        if "." in fn.name:
            self.fn_obj[fn.key] = fn.name.rsplit(".", 1)[0]     # this.x → 객체.x 해석용
        snippet = None
        if cfg.include_snippets:
            lines = u.src.splitlines()[node.start_point[0]: node.end_point[0] + 1]
            snippet = "\n".join(lines)
        self.ctx.model.add_code_unit(fn.key, fn.lang, fn.file, fn.name, fn.line, node.end_point[0] + 1 + u.offset,
                                     snippet, fn.common)

    @staticmethod
    def _body(node: Node) -> Node:
        return node.child_by_field_name("body") or node

    @staticmethod
    def _calls_in(node: Node):
        stack = [node]
        while stack:
            n = stack.pop()
            if n.type == "call_expression":
                yield n
            stack.extend(reversed(n.children))

    # ---------- 최상위: ready 블록 / 최상위 바인딩 ----------
    def _toplevel(self, u: JsUnit):
        m = self.ctx.model
        for stmt in u.tree.root_node.named_children:
            if stmt.type != "expression_statement" or not stmt.named_children:
                continue
            call = stmt.named_children[0]
            if call.type != "call_expression":
                continue
            fnode = is_ready_call(call)
            if fnode is not None:
                name = "$(document).ready"
                key = fn_key(u.lang, u.file, name)
                if key in m.code_units:
                    key = fn_key(u.lang, u.file, f"{name}@L{u.line(call)}")
                snippet = "\n".join(u.src.splitlines()[call.start_point[0]: call.end_point[0] + 1]) if self.ctx.config.include_snippets else None
                m.add_code_unit(key, u.lang, u.file, name, u.line(call), call.end_point[0] + 1 + u.offset, snippet)
                m.add_edge(self.skey, key, "event", event="onload", confidence=0.99, evidence=f"{u.file}:{u.line(call)}")
                self.ctx.screen_onload.setdefault(self.skey, []).append(key)
                self._scan(u, fnode, key)
            elif binding_of(call):
                self._binding(u, call, None)

    # ---------- 함수 본문 스캔 ----------
    def _scan(self, u: JsUnit, node: Node, fkey: str, state: dict | None = None):
        state = state if state is not None else {"confirm": None, "vars": {}}
        state.setdefault("vars", {})
        for child in node.children:
            if child.type == "function_declaration":
                continue
            if child.type == "call_expression":
                if binding_of(child):
                    self._binding(u, child, fkey)
                    continue
                self._call(u, child, fkey, state)
            elif child.type == "variable_declarator":
                v = child.child_by_field_name("value")
                if v is not None and v.type in STRINGISH:
                    state["vars"][text(child.child_by_field_name("name"))] = v
            elif child.type == "assignment_expression":
                left = text(child.child_by_field_name("left"))
                right = child.child_by_field_name("right")
                ev = f"{u.file}:{u.line(child)}"
                if left in ("location.href", "window.location.href", "location", "document.location.href",
                            "top.location.href", "parent.location.href", "self.location.href", "document.location"):
                    url, conf = self._url(right, state)
                    if url:
                        self.pending_nav.append((fkey, self._abs(url), conf, ev, False))
                        if self._is_action(url):
                            self._request(fkey, "GET", url, conf, self._cond(state), ev)
                elif re.search(r"(^|\.)action$", left) and right is not None:
                    state["action_set"] = True
                    url, conf = self._url(right, state)       # frm.action = "/x/Foo.do?method=save" (+ submit)
                    if url and self._is_action(url):
                        self._request(fkey, "POST", url, conf, self._cond(state), ev)
                elif right is not None and right.type in STRINGISH and re.fullmatch(r"[\w$]+", left):
                    state["vars"][left] = right
            self._scan(u, child, fkey, state)

    def _abs(self, url: str) -> str:
        """상대 Action URL('?method=view', 'FooAction.do')을 지금 화면의 Action 기준 절대 경로로."""
        base = getattr(self, "screen_base", None)
        u = url.strip()
        if not base or u.startswith("/") or re.match(r"[a-z]+:", u, re.I) or not self._is_action(u):
            return url
        bpath = base.split("?", 1)[0]
        return bpath + u if u.startswith("?") else bpath.rsplit("/", 1)[0] + "/" + u

    def _is_action(self, url: str) -> bool:
        exts = self.ctx.config.get("source.actionExtensions") or [".do"]
        path = url.split("?", 1)[0]
        return url.startswith("?") or any(path.endswith(x) for x in exts)

    @staticmethod
    def _cond(state: dict) -> str | None:
        return f'confirm("{state["confirm"]}")' if state.get("confirm") else None

    def _str_of(self, node: Node | None, state: dict | None, depth: int = 0) -> str | None:
        """문자열로 확정되는 식의 값 (리터럴, 상수·지역 변수, 그 결합). 확정 못 하면 None."""
        if node is None or depth > 6:
            return None
        v = string_value(node)
        if v is not None:
            return None if "${" in v else v
        if node.type == "parenthesized_expression" and node.named_children:
            return self._str_of(node.named_children[0], state, depth + 1)
        if node.type == "identifier":
            name = text(node)
            ref = ((state or {}).get("vars") or {}).get(name)
            if ref is None:
                ref = getattr(self, "consts", {}).get(name)
            return self._str_of(ref, None, depth + 1) if ref is not None and ref is not node else None
        if node.type == "binary_expression" and text(node.child_by_field_name("operator")) == "+":
            a = self._str_of(node.child_by_field_name("left"), state, depth + 1)
            b = self._str_of(node.child_by_field_name("right"), state, depth + 1)
            if a is not None and b is not None:
                return a + b
        return None

    def _url(self, node: Node | None, state: dict | None) -> tuple[str | None, float]:
        """URL 인자 해석 — 상수·지역 변수 결합까지 (var SAVE_PATH = "?method=save"; frm.action = SAVE_PATH + "&cmd=" + c)."""
        if node is None:
            return None, 0
        full = self._str_of(node, state)
        if full is not None:
            return full, 0.95
        # 앞부분만 확정되는 결합: 확정되는 접두까지
        parts: list[Node] = []

        def flat(n: Node):
            if n.type == "binary_expression" and text(n.child_by_field_name("operator")) == "+":
                flat(n.child_by_field_name("left"))
                flat(n.child_by_field_name("right"))
            else:
                parts.append(n)
        flat(node)
        prefix = ""
        for i, p in enumerate(parts):
            v = self._str_of(p, state)
            if v is None:
                # "/api/x/" + id → "/api/x/{id}" (경로 변수 자리) — 서버의 /api/x/{id} 매핑과 맞춘다
                if prefix.endswith("/") and p.type in ("identifier", "member_expression") and i == len(parts) - 1:
                    prefix += "{" + text(p).rsplit(".", 1)[-1] + "}"
                break
            prefix += v
        if prefix:
            return prefix, 0.85
        return url_arg(node)

    def _name(self, node: Node | None, fkey: str | None) -> str:
        """호출/참조 이름 정규화: window.f → f, this.f → 객체.f"""
        name = text(node) if node is not None else ""
        if name.startswith("window."):
            name = name[len("window."):]
        if name.startswith("this.") and fkey and fkey in self.fn_obj:
            name = f"{self.fn_obj[fkey]}.{name[len('this.'):]}"
        return name

    def _resolve(self, node: Node | None, fkey: str | None) -> Fn | None:
        if node is None or node.type not in ("identifier", "member_expression"):
            return None
        return self.scope.get(self._name(node, fkey))

    def _call(self, u: JsUnit, call: Node, fkey: str, state: dict):
        m, cfg = self.ctx.model, self.ctx.config
        callee = call.child_by_field_name("function")
        args = call.child_by_field_name("arguments")
        args = args.named_children if args else []
        ev = f"{u.file}:{u.line(call)}"
        cname = self._name(callee, fkey)
        cond = f'confirm("{state["confirm"]}")' if state.get("confirm") else None
        target = self._resolve(callee, fkey)
        if callee.type == "member_expression" and target is not None and cname not in self.fapis and cname not in self.fnavs:
            # 객체 메서드 호출: HR0105.search() / this.search()
            if target.key != fkey:
                m.add_edge(fkey, target.key, "calls", confidence=0.95, evidence=ev)
        elif callee.type == "identifier" or cname in self.fapis or cname in self.fnavs:
            if cname == "confirm" and args:
                state["confirm"] = string_value(args[0]) or text(args[0])
            elif cname in self.fapis:
                spec = self.fapis[cname]
                url, conf = self._url(args[spec.get("urlArg", 0)] if len(args) > spec.get("urlArg", 0) else None, state)
                if url:
                    self._request(fkey, spec.get("method", "POST"), url, conf, cond, ev)
                else:
                    m.fail("parse", fkey, f"{cname} URL 인자를 해석하지 못함 ({ev})")
            elif cname in self.fnavs:
                spec = self.fnavs[cname]
                url, conf = self._url(args[spec.get("urlArg", 0)] if len(args) > spec.get("urlArg", 0) else None, state)
                if url:
                    self.pending_nav.append((fkey, self._abs(url), conf, ev, True))
                    if self._is_action(url):
                        self._request(fkey, "GET", url, conf, cond, ev)
            elif cname in self.scope and self.scope[cname].key != fkey:
                m.add_edge(fkey, self.scope[cname].key, "calls", confidence=0.95, evidence=ev)
        elif callee.type == "member_expression":
            obj = text(callee.child_by_field_name("object"))
            prop = text(callee.child_by_field_name("property"))
            if obj in JQ and prop in ("ajax", "post", "get", "getJSON"):
                url, conf, method = None, 0, {"post": "POST"}.get(prop, "GET")
                if prop == "ajax" and args and args[0].type == "object":
                    for pair in args[0].named_children:
                        if pair.type != "pair":
                            continue
                        k = text(pair.child_by_field_name("key")).strip("'\"")
                        v = pair.child_by_field_name("value")
                        if k == "url":
                            url, conf = self._url(v, state)
                        elif k in ("type", "method") and string_value(v):
                            method = string_value(v).upper()
                elif args:
                    url, conf = self._url(args[0], state)
                if url:
                    self._request(fkey, method, url, conf, cond, ev)
            elif text(callee) in ("window.open", "self.open", "top.open") and args:
                url, conf = self._url(args[0], state)
                if url:
                    self.pending_nav.append((fkey, self._abs(url), conf, ev, True))
                    if self._is_action(url):
                        self._request(fkey, "GET", url, conf, cond, ev)
            elif prop == "submit" and args:
                url, conf = self._url(args[0], state)     # frm.submit("?method=list") (자체 Forms 래퍼)
                if url and self._is_action(url):
                    self._request(fkey, "POST", url, conf, cond, ev)
            elif prop == "submit" and not args and not state.get("action_set") and getattr(self, "form_action", None):
                if self._is_action(self.form_action):    # <form action="?method=list"> 그대로 제출
                    self._request(fkey, "POST", self.form_action, 0.8, cond, ev)
            elif obj in ("opener", "window.opener", "parent", "window.parent", "top", "self.opener"):
                # 팝업/프레임에서 부모 창 함수 호출 — 화면 이동 관계로 부모 화면을 찾아 연결 (파싱 끝에 해석)
                self.__dict__.setdefault("pending_opener", []).append((fkey, prop, ev))
        # 콜백으로 넘긴 함수 참조: gfn_ajax(url, p, fn_search) / gfn_ajax(url, p, HR0105.search)
        for a in args:
            ref = self._resolve(a, fkey)
            if ref is not None and ref.key != fkey:
                m.add_edge(fkey, ref.key, "calls", confidence=0.9, evidence=ev)

    def _request(self, fkey: str, method: str, url: str, conf: float, cond: str | None, ev: str):
        url = url.strip()
        if url.startswith("?") or (not url.startswith("/") and not re.match(r"[a-z]+:", url, re.I) and self._is_action(url)):
            base = getattr(self, "screen_base", None)
            if not base:
                if self.ctx.config.get("source.keepQueryParams"):
                    self.ctx.model.fail("parse", fkey, f"상대 URL '{url}' 의 기준 Action 을 알 수 없음 ({ev})")
                    return
            else:
                bpath = base.split("?", 1)[0]
                url = bpath + url if url.startswith("?") else bpath.rsplit("/", 1)[0] + "/" + url
                self.__dict__.setdefault("_rel_apis", set()).add(api_key(method, self.ctx.normalize_url(url)))
        else:
            self.__dict__.setdefault("_abs_apis", set()).add(api_key(method, self.ctx.normalize_url(url)))
        path = self.ctx.normalize_url(url)
        akey = api_key(method, path)
        self.ctx.model.add_api(akey, method, path)
        self.ctx.model.add_edge(fkey, akey, "request", confidence=conf, condition=cond, evidence=ev)

    # ---------- 이벤트 바인딩 ----------
    def _elements_for(self, selector: str) -> list[str]:
        idm = re.fullmatch(r"#([\w-]+)", selector.strip())
        if idm and idm.group(1) in self.el_by_id:
            return [self.el_by_id[idm.group(1)]]
        return [el["_key"] for el in match_selector(selector, self.static_meta)]

    def _binding(self, u: JsUnit, call: Node, owner: str | None):
        m = self.ctx.model
        outer, inner, events, handler = binding_of(call)
        ev = f"{u.file}:{u.line(call)}"
        ekeys: list[str] = []
        for sel in (inner, outer):          # 위임 선택자가 화면 요소를 가리키면 그것, 아니면 바깥 요소(그리드 행 위임 등)
            if sel:
                ekeys = self._elements_for(sel)
                if ekeys:
                    break
        if not ekeys:
            sel = inner or outer or ""
            idm = re.fullmatch(r"#([\w-]+)", sel.strip())
            if not idm:
                m.fail("parse", self.skey, f"화면 요소와 매칭되지 않는 선택자 '{sel}' ({ev})")
                return
            eid = idm.group(1)                 # 스크립트로 만들어지는 요소 등
            ekey = el_key(self.skey, eid)
            m.elements.setdefault(ekey, {"key": ekey, "screenKey": self.skey, "elementId": eid, "label": eid,
                                         "type": "other", "matched": "static-only", "_seq": 999})
            self.el_by_id[eid] = ekey
            ekeys = [ekey]
        targets: list[str] = []
        if handler is not None and handler.type in ("identifier", "member_expression"):
            fn = self._resolve(handler, owner)
            if fn:
                targets.append(fn.key)
            else:
                m.fail("parse", ekeys[0], f"핸들러 함수 '{text(handler)}' 정의를 찾지 못함 ({ev})")
        elif handler is not None and (single := self._single_call(handler)) is not None:
            targets.append(single.key)     # function() { fn_excel(); } → fn_excel 로 바로 연결
        elif handler is not None and handler.type in ("function_expression", "arrow_function", "function"):
            ev_name = "/".join(events)
            key = fn_key(u.lang, u.file, f"anon@L{u.line(handler)}")
            snippet = "\n".join(u.src.splitlines()[handler.start_point[0]: handler.end_point[0] + 1]) if self.ctx.config.include_snippets else None
            m.add_code_unit(key, u.lang, u.file, f"{inner or outer}.{ev_name} 익명함수", u.line(handler),
                            handler.end_point[0] + 1 + u.offset, snippet)
            self._scan(u, handler, key)
            targets.append(key)
        conf = 0.99 if len(ekeys) == 1 else 0.9
        for ekey in ekeys:
            for e in events:
                for t in targets:
                    m.add_edge(ekey, t, "event", event=e, confidence=conf, evidence=ev)

    def _single_call(self, handler: Node) -> Fn | None:
        """익명 핸들러 본문이 알려진 함수 호출 한 줄뿐이면 그 함수."""
        if handler.type not in ("function_expression", "arrow_function", "function"):
            return None
        body = self._body(handler)
        stmts = body.named_children if body.type == "statement_block" else [body]
        if len(stmts) != 1:
            return None
        call = stmts[0].named_children[0] if stmts[0].type == "expression_statement" and stmts[0].named_children else stmts[0]
        if call.type != "call_expression":
            return None
        callee = call.child_by_field_name("function")
        args = call.child_by_field_name("arguments")
        if args is None or not args.named_children:
            return self._resolve(callee, None)
        return None

    # ---------- 후처리 ----------
    def _screen_for_url(self, url: str) -> str | None:
        return screen_for_url(self.ctx, url)

    def _resolve_navigation(self):
        m = self.ctx.model
        ignore = [re.compile(x) for x in ["^about:blank$", "^blank$", "^https?://", "^javascript:"]
                  + list(self.ctx.config.get("source.ignoreNavigation") or [])]
        for fkey, url, conf, ev, popup in self.pending_nav:
            if any(rx.search(url.strip()) for rx in ignore):
                continue                 # 빈 창·외부 사이트·다운로드 서블릿 등 화면이 아닌 대상
            target = self._screen_for_url(url)
            if not target:
                if popup:   # location.href 로 로그인·다운로드 등 화면 아닌 곳 이동은 무시
                    m.fail("parse", fkey, f"팝업 대상 화면을 찾지 못함: {url} ({ev})")
                continue
            m.add_edge(fkey, target, "navigates", confidence=conf, evidence=ev)
            if popup:
                m.screens[target]["kind"] = "popup"
