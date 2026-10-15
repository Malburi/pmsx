"""ASP.NET 화면 파서 — WebForms(.aspx + code-behind) · MVC(Razor .cshtml + Controller). docs/13 §9.

두 파서 모두 JSP 파서(HTML 요소 + jQuery/스크립트 분석)를 재사용하고, 서버 쪽 흐름은 dotnet.py 색인을 쓴다.

WebForms
  · <%@ Page Title Inherits="Ns.Page" CodeBehind %> → 화면 이름·코드비하인드 클래스
  · 서버 컨트롤 → 렌더링되는 HTML 로 바꿔(줄 번호 유지) JSP 파서에 넘긴다
      asp:Button → <input type=submit>, asp:TextBox → <input>/<textarea>, asp:DropDownList → <select>,
      asp:GridView(BoundField) → <table><th data-col>…, asp:LinkButton/HyperLink → <a>, asp:Label AssociatedControlID → <label for>
    <%= txtTitle.ClientID %> 는 txtTitle 로 바꿔 jQuery 선택자 해석이 되게 한다
  · OnClick / OnSelectedIndexChanged / OnRowCommand … → 코드비하인드 메서드(event 엣지, 포스트백)
    OnClientClick="return confirm('…')" → 그 이벤트 엣지의 조건
  · Page_Load → 화면 onload, Response.Redirect(…) → 화면 이동, [WebMethod] → POST /Page.aspx/Method
MVC
  · Views/{Controller}/{Action}.cshtml → 화면 (URL 은 컨트롤러 액션 Route 의 View 매핑, 화면 ID = Controller.Action)
  · Razor 전처리: @Url.Action("A","C") → /C/A, @Url.Content("~/x") → /x, @Html.TextBoxFor(m => m.X, new { id = "y" }) → <input id=y name=X>,
    @Html.DropDownListFor → <select>, @Html.ActionLink → <a href>, @{ … } · @model · @using → 공백, @section X { … } → 안쪽만
  · 스크립트의 $.ajax('/C/A') → Route(api) 로 컨트롤러 액션 연결, RedirectToAction → 화면 이동
"""
from __future__ import annotations

import re
from html.parser import HTMLParser
from pathlib import Path

from ..keys import scr_key
from . import Context
from .dotnet import EXCLUDE_DIRS, get_index, mvc_routes, register_methods, resolve_url_navigation, webmethod_routes
from .jsp_jquery import JspJqueryParser, preprocess_jsp
from .ui_common import link_menus, link_server, refine_popup_types

SERVER_EVENTS = {"onclick": "click", "oncommand": "click", "onselectedindexchanged": "change", "ontextchanged": "change",
                 "oncheckedchanged": "change", "onrowcommand": "click", "onrowediting": "click", "onrowdeleting": "click",
                 "onrowupdating": "click", "onpageindexchanging": "click", "onsorting": "click", "onserverclick": "click",
                 "onitemcommand": "click", "onselectionchanged": "change", "onserverchange": "change"}
ASP_HTML = {   # asp:태그 → (html 태그, type, self-closing)
    "button": ("input", "submit", True), "imagebutton": ("input", "image", True), "linkbutton": ("a", "", False),
    "hyperlink": ("a", "", False), "textbox": ("input", "text", True), "dropdownlist": ("select", "", False),
    "listbox": ("select", "", False), "checkbox": ("input", "checkbox", True), "radiobutton": ("input", "radio", True),
    "checkboxlist": ("table", "", False), "radiobuttonlist": ("table", "", False), "gridview": ("table", "", False),
    "datagrid": ("table", "", False), "listview": ("table", "", False), "repeater": ("table", "", False),
    "fileupload": ("input", "file", True), "calendar": ("input", "date", True), "label": ("span", "", False),
}


def _blank(s: str) -> str:
    return re.sub(r"[^\n]", " ", s)


def _attr_map(attr_text: str) -> dict[str, str]:
    return {k.lower(): v for k, v in re.findall(r"([\w:.-]+)\s*=\s*\"([^\"]*)\"", attr_text)}


def _html_attrs(d: dict[str, str]) -> str:
    return " ".join(f'{k}="{v.replace(chr(34), "&quot;")}"' for k, v in d.items() if v is not None)


def aspx_to_html(src: str) -> str:
    """서버 컨트롤을 렌더링 HTML 로 (줄 수 유지)."""
    src = re.sub(r"<%=\s*(\w+)\.ClientID\s*%>", r"\1", src)
    src = re.sub(r"<%=\s*(\w+)\.UniqueID\s*%>", r"\1", src)
    src = re.sub(r"<%=\s*ResolveUrl\(\s*\"~?([^\"]+)\"\s*\)\s*%>", r"\1", src)
    src = preprocess_jsp(src)

    def bound(m: re.Match) -> str:
        a = _attr_map(m.group(1))
        out = f'<th data-col="{a.get("datafield", "")}">{a.get("headertext", "")}</th>' if a.get("datafield") else \
            f'<th>{a.get("headertext", a.get("text", ""))}</th>'
        return out + "\n" * m.group(0).count("\n")

    src = re.sub(r"<asp:(?:BoundField|TemplateField|HyperLinkField|CheckBoxField)\b([^>]*?)/?>", bound, src, flags=re.I | re.S)

    def open_tag(m: re.Match) -> str:
        tag, attr_text, selfclose = m.group(1).lower(), m.group(2), m.group(3)
        nls = "\n" * m.group(0).count("\n")
        a = _attr_map(attr_text)
        html_tag, typ, void = ASP_HTML.get(tag, ("div", "", False))
        h: dict[str, str] = {}
        if a.get("id"):
            h["id"] = a["id"]
            h["name"] = a["id"]
        if typ:
            h["type"] = typ
        if tag == "textbox":
            mode = a.get("textmode", "").lower()
            if mode == "multiline":
                html_tag, void = "textarea", False
            elif mode in ("date", "password", "number", "email"):
                h["type"] = mode
        if tag == "label" and a.get("associatedcontrolid"):
            html_tag, h = "label", {"for": a["associatedcontrolid"]}
        if a.get("onclientclick"):
            h["onclick"] = a["onclientclick"]
        if a.get("cssclass"):
            h["class"] = a["cssclass"]
        if a.get("tooltip"):
            h["title"] = a["tooltip"]
        if html_tag == "input" and a.get("text"):
            h["value"] = a["text"]
        inner = a.get("text", "") if html_tag in ("a", "label", "span") else ""
        if void:
            return f"<{html_tag} {_html_attrs(h)}>{nls}"
        if selfclose:
            return f"<{html_tag} {_html_attrs(h)}>{inner}</{html_tag}>{nls}"
        return f"<{html_tag} {_html_attrs(h)}>{inner}{nls}"

    src = re.sub(r"<asp:(\w+)\b([^>]*?)(/?)>", open_tag, src, flags=re.S | re.I)

    def close_tag(m: re.Match) -> str:
        tag = m.group(1).lower()
        html_tag, _, void = ASP_HTML.get(tag, ("div", "", False))
        return "" if void else f"</{html_tag}>"

    src = re.sub(r"</asp:(\w+)\s*>", close_tag, src, flags=re.I)
    src = re.sub(r"</?(Columns|ItemTemplate|HeaderTemplate|EditItemTemplate|ContentTemplate|Triggers|Items|asp:ListItem)\b[^>]*>",
                 lambda mm: _blank(mm.group(0)), src, flags=re.I)
    return src


class _ServerControls(HTMLParser):
    """원본 .aspx 에서 runat=server 컨트롤의 서버 이벤트 속성 수집."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.controls: list[dict] = []

    def handle_starttag(self, tag, attrs):
        a = {k: (v or "") for k, v in attrs}
        if a.get("runat", "").lower() == "server" and a.get("id"):
            self.controls.append({"tag": tag, "id": a["id"], "attrs": a, "line": self.getpos()[0]})

    handle_startendtag = handle_starttag


class AspNetWebFormsParser(JspJqueryParser):
    name = "aspnet-webforms"
    layer = "ui"
    ui_tech = "aspnet-webforms"
    inline_lang = "js"

    def detect(self, root: Path) -> float:
        return 1.0 if any(root.rglob("*.aspx")) else 0.0

    def preprocess(self, raw: str, path: Path) -> str:
        return aspx_to_html(raw)

    def parse(self, ctx: Context) -> None:
        cfg, m = ctx.config, ctx.model
        self.ctx = ctx
        root = cfg.source_dir("webforms") or cfg.source_dir("aspnet") or cfg.source_root   # 웹 루트(가상 디렉터리 기준)
        self.webapp = root
        self.idx = get_index(ctx)
        register_methods(ctx, self.idx)
        self.fapis = cfg.get("frontApis", {}) or {}
        self.fnavs = cfg.get("frontNavigations", {}) or {}
        self.pending_nav = []
        self.common_units, self.common_scope, self.fn_obj = {}, {}, {}
        pages = [f for f in sorted(root.rglob("*.aspx")) if not set(p.name for p in f.parents) & EXCLUDE_DIRS]
        if not pages:
            m.fail("parse", "source.aspnet", "WebForms 페이지(.aspx)를 찾지 못함")
            return
        page_of_class: dict[str, str] = {}
        for f in pages:
            url = "/" + f.relative_to(root).as_posix()
            ctx.screen_by_url[url] = scr_key(cfg.sys_code, self.screen_id(f))
        for f in pages:
            url = "/" + f.relative_to(root).as_posix()
            self._screen(f, url)
            full = self._server_side(f, url)
            if full:
                page_of_class[full] = url
        webmethod_routes(ctx, self.idx, page_of_class)
        resolve_url_navigation(ctx, self.idx, fail=False)
        self._resolve_navigation()
        link_menus(ctx)
        link_server(ctx)
        refine_popup_types(ctx)

    def _server_side(self, f: Path, url: str) -> str | None:
        """Page 지시자·서버 컨트롤 이벤트 → 코드비하인드 메서드."""
        ctx, cfg, m, idx = self.ctx, self.ctx.config, self.ctx.model, self.idx
        skey = scr_key(cfg.sys_code, self.screen_id(f))
        raw = f.read_text(encoding="utf-8-sig")
        page = re.search(r"<%@\s*Page\b(.*?)%>", raw, re.S | re.I)
        pa = _attr_map(page.group(1)) if page else {}
        if pa.get("title") and m.screens[skey]["name"] == self.screen_id(f):
            m.screens[skey]["name"] = pa["title"]
        m.screens[skey]["url"] = url
        inherits = pa.get("inherits", "")
        cls = idx.classes.get(inherits) or next(iter(idx.by_name.get(inherits.rsplit(".", 1)[-1], [])), None)
        if cls is None:
            m.fail("parse", skey, f"코드비하인드 클래스 '{inherits or '(Inherits 없음)'}' 를 찾지 못함")
            return None
        sc = _ServerControls()
        sc.feed(re.sub(r"<%.*?%>", lambda mm: _blank(mm.group(0)), raw, flags=re.S))
        for c in sc.controls:
            ekey = self.el_by_id.get(c["id"])
            if not ekey:
                continue
            confirm = re.search(r"confirm\(\s*['\"]([^'\"]+)['\"]", c["attrs"].get("onclientclick", ""))
            for attr, ev in SERVER_EVENTS.items():
                handler = c["attrs"].get(attr)
                if not handler:
                    continue
                meth = idx.find_method(cls, handler)
                if meth is None:
                    m.fail("parse", ekey, f"서버 이벤트 핸들러 '{handler}' 를 코드비하인드에서 찾지 못함 ({attr})")
                    continue
                m.add_edge(ekey, idx.key(meth), "event", event=ev, confidence=0.99,
                           condition=f'confirm("{confirm.group(1)}")' if confirm else None,
                           evidence=f"{cfg.rel(f)}:{c['line']} (포스트백)")
        for name in ("Page_Load", "OnLoad", "Page_Init"):
            meth = cls.methods.get(name, [None])[0]
            if meth is not None:
                m.add_edge(skey, idx.key(meth), "event", event="onload", confidence=0.99, evidence=cfg.rel(meth.file))
                ctx.screen_onload.setdefault(skey, []).append(idx.key(meth))
                break
        return cls.full


# ---------------------------------------------------------------- MVC
def razor_to_html(src: str, controller: str) -> tuple[str, str | None]:
    """Razor → HTML (줄 수 유지). 반환: (HTML, ViewBag.Title)."""
    title = None
    tm = re.search(r"ViewBag\.Title\s*=\s*\"([^\"]*)\"", src) or re.search(r"ViewData\[\"Title\"\]\s*=\s*\"([^\"]*)\"", src)
    if tm:
        title = tm.group(1)

    def args_of(s: str) -> list[str]:
        return re.findall(r"\"([^\"]*)\"", s)

    def url_action(m: re.Match) -> str:
        a = args_of(m.group(1))
        if not a:
            return m.group(0)
        return f"/{a[1] if len(a) > 1 else controller}/{a[0]}"

    src = re.sub(r"@Url\.Action\(([^)]*)\)", url_action, src)
    src = re.sub(r"@Url\.Content\(\s*\"~?([^\"]+)\"\s*\)", r"\1", src)
    src = re.sub(r"@Url\.RouteUrl\([^)]*\)", "/", src)

    def html_helper(m: re.Match) -> str:
        helper, inner = m.group(1), m.group(2)
        prop = re.search(r"=>\s*\w+\.([\w.]+)", inner)
        name = prop.group(1) if prop else (args_of(inner)[0] if args_of(inner) else "")
        idm = re.search(r"\bid\s*=\s*\"([^\"]+)\"", inner)
        eid = idm.group(1) if idm else name.replace(".", "_")
        nls = "\n" * m.group(0).count("\n")
        if helper.startswith(("TextBox", "Editor", "Password")):
            typ = "password" if helper.startswith("Password") else "text"
            return f'<input type="{typ}" id="{eid}" name="{name}">{nls}'
        if helper.startswith("TextArea"):
            return f'<textarea id="{eid}" name="{name}"></textarea>{nls}'
        if helper.startswith(("DropDownList", "ListBox", "EnumDropDownList")):
            return f'<select id="{eid}" name="{name}"></select>{nls}'
        if helper.startswith("CheckBox"):
            return f'<input type="checkbox" id="{eid}" name="{name}">{nls}'
        if helper.startswith("RadioButton"):
            return f'<input type="radio" id="{eid}" name="{name}">{nls}'
        if helper.startswith("Hidden"):
            return f'<input type="hidden" id="{eid}" name="{name}">{nls}'
        if helper == "ActionLink":
            a = args_of(inner)
            if len(a) >= 2:
                href = f"/{a[2] if len(a) > 2 else controller}/{a[1]}"
                return f'<a id="{idm.group(1) if idm else ""}" href="{href}">{a[0]}</a>{nls}'
        if helper.startswith(("Label", "DisplayName")):
            return f'<label for="{eid}">{(args_of(inner) or [""])[-1]}</label>{nls}'
        return _blank(m.group(0))

    src = re.sub(r"@Html\.(\w+)\(((?:[^()]|\([^()]*\))*)\)", html_helper, src, flags=re.S)
    # @{ … } 코드 블록 (중첩 괄호 1단계)
    src = re.sub(r"@\{(?:[^{}]|\{[^{}]*\})*\}", lambda mm: _blank(mm.group(0)), src, flags=re.S)
    # @section X { … } → 안쪽만
    out, i = [], 0
    for sm in re.finditer(r"@section\s+\w+\s*\{", src):
        if sm.start() < i:
            continue
        depth, j = 1, sm.end()
        while j < len(src) and depth:
            depth += {"{": 1, "}": -1}.get(src[j], 0)
            j += 1
        out.append(src[i:sm.start()] + _blank(src[sm.start():sm.end()]) + src[sm.end():j - 1] + " ")
        i = j
    src = "".join(out) + src[i:]
    src = re.sub(r"^\s*@(model|using|inherits|inject|addTagHelper|page|layout)\b.*$", lambda mm: _blank(mm.group(0)), src, flags=re.M)
    src = re.sub(r"@(RenderBody|RenderSection|Html\.Raw|Html\.AntiForgeryToken|Html\.Partial|Html\.RenderPartial)\([^)]*\)",
                 lambda mm: _blank(mm.group(0)), src)
    src = re.sub(r"@\(([^)]*)\)", lambda mm: "_" * len(mm.group(0)), src)
    src = re.sub(r"@[A-Za-z_][\w.]*(\([^)]*\))?", lambda mm: "_" * len(mm.group(0)), src)
    return src, title


class AspNetMvcParser(JspJqueryParser):
    name = "aspnet-mvc"
    layer = "ui"
    ui_tech = "aspnet-mvc"
    inline_lang = "js"

    def detect(self, root: Path) -> float:
        return 1.0 if any(root.rglob("*.cshtml")) else 0.0

    def screen_id(self, path: Path) -> str:
        return f"{path.parent.name}.{path.stem}"

    def preprocess(self, raw: str, path: Path) -> str:
        html, title = razor_to_html(raw, path.parent.name)
        self._title = title
        return html

    def parse(self, ctx: Context) -> None:
        cfg, m = ctx.config, ctx.model
        self.ctx = ctx
        root = cfg.source_dir("mvc") or cfg.source_dir("aspnet") or cfg.source_root
        views = cfg.resolve(cfg.get("aspnet.views")) if cfg.get("aspnet.views") else None
        views = views or next((p for p in root.rglob("Views") if p.is_dir() and not set(x.name for x in p.parents) & EXCLUDE_DIRS), None)
        if views is None:
            m.fail("parse", "source.aspnet", "MVC Views 폴더를 찾지 못함")
            return
        self.webapp = cfg.resolve(cfg.get("aspnet.webRoot")) if cfg.get("aspnet.webRoot") else views.parent
        self.idx = get_index(ctx)
        mvc_routes(ctx, self.idx)                # serverTech 에 dotnet 이 없어도 액션 URL 은 필요
        register_methods(ctx, self.idx)
        self.fapis = cfg.get("frontApis", {}) or {}
        self.fnavs = cfg.get("frontNavigations", {}) or {}
        self.pending_nav = []
        self.common_units, self.common_scope, self.fn_obj = {}, {}, {}
        view_url = {}
        for r in ctx.routes:
            if r.kind == "screen" and r.view:
                view_url.setdefault(r.view, r.path)
        alias = {r.handler_key: r.path for r in ctx.routes if r.kind == "screen" and not r.view}
        files = [f for f in sorted(views.rglob("*.cshtml")) if not f.name.startswith("_") and f.parent.name != "Shared"]
        for f in files:
            view = f.relative_to(views).with_suffix("").as_posix()
            url = view_url.get(view)
            if url:
                ctx.screen_by_url[url] = scr_key(cfg.sys_code, self.screen_id(f))
        for f in files:
            view = f.relative_to(views).with_suffix("").as_posix()
            url = view_url.get(view)
            self._title = None
            self._screen(f, url)
            skey = scr_key(cfg.sys_code, self.screen_id(f))
            if self._title:
                m.screens[skey]["name"] = self._title
            if url is None:
                m.fail("parse", skey, f"뷰 '{view}' 를 반환하는 컨트롤러 액션을 찾지 못함")
            # /Ctrl/Index 의 짧은 URL(/Ctrl) 도 같은 화면
            for r in ctx.routes:
                if r.kind == "screen" and r.view == view and r.handler_key in alias:
                    ctx.screen_by_url[alias[r.handler_key]] = skey
        resolve_url_navigation(ctx, self.idx, fail=False)
        self._resolve_navigation()
        link_menus(ctx)
        link_server(ctx)
        refine_popup_types(ctx)
