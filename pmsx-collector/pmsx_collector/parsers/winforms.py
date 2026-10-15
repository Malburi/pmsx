"""WinForms 화면 파서 — docs/13 §9.

*.Designer.cs 의 InitializeComponent() 를 해석한다.
  this.btnSave = new System.Windows.Forms.Button();              → 컨트롤(필드명 = UIA AutomationId)
  this.btnSave.Location = new Point(716, 452); .Size / .Text / .Name / HeaderText / DataPropertyName / Width
  this.grpSearch.Controls.Add(this.txtEmpNm);                     → 부모(GroupBox·Panel·TabPage) 좌표 누적
  this.btnSave.Click += new System.EventHandler(this.btnSave_Click);   → event 엣지 (+= this.f / += f / 람다)
  this.menuStrip1.Items.AddRange(…) / mnuHr.DropDownItems.AddRange(…) → 메뉴 트리 + 메뉴 항목 요소
  this.dgvEmp.Columns.AddRange(…)                                 → 그리드 바인딩·와이어프레임 열
  this.Text / this.ClientSize / this.FormBorderStyle / this.Load += … → 화면 이름·크기·팝업 여부·onload
핸들러 이후 흐름(서비스 → DAO → SQL/SP/EF)과 화면 이동(new XxxForm().Show/ShowDialog)은 dotnet.py 가 분석한다.
런타임 없이도 디자이너 좌표로 와이어프레임 캡처를 만들고, 데스크톱 캡처(desktop_capture.py)를 켜면 실제 화면으로 대체된다.
"""
from __future__ import annotations

import re
from pathlib import Path

from tree_sitter import Node

from ..keys import el_key, menu_key, scr_key
from . import Context
from .dotnet import CsClass, CsIndex, get_index, register_methods, simple_type, _iter, _literal
from .ts import text
from .ui_common import link_menus, refine_popup_types

CONTROL_TYPES = {
    "Button": "button", "ToolStripButton": "button", "LinkLabel": "link", "TextBox": "input", "MaskedTextBox": "input",
    "RichTextBox": "input", "NumericUpDown": "input", "ComboBox": "combo", "ListBox": "combo", "CheckedListBox": "combo",
    "DateTimePicker": "date", "MonthCalendar": "date", "CheckBox": "check", "RadioButton": "radio",
    "DataGridView": "grid", "ListView": "grid", "TabControl": "tab", "TreeView": "tree", "PictureBox": "other",
    "WebBrowser": "other", "ToolStripMenuItem": "link",
    # DevExpress 등 흔한 서드파티
    "SimpleButton": "button", "TextEdit": "input", "LookUpEdit": "combo", "ComboBoxEdit": "combo", "DateEdit": "date",
    "GridControl": "grid", "XtraTabControl": "tab", "CheckEdit": "check", "MemoEdit": "input",
}
CONTAINERS = {"GroupBox", "Panel", "TabPage", "SplitContainer", "FlowLayoutPanel", "TableLayoutPanel", "SplitterPanel",
              "GroupControl", "PanelControl", "XtraTabPage", "UserControl"}
LABELS = {"Label", "LabelControl"}
WIRE_KIND = {"button": "button", "link": "static", "input": "edit", "combo": "combo", "date": "calendar", "check": "check",
             "radio": "radio", "grid": "grid", "tree": "other", "other": "other"}
EVENTS = {"Click": "click", "DoubleClick": "dblclick", "CellClick": "click", "CellDoubleClick": "dblclick",
          "CellContentClick": "click", "SelectedIndexChanged": "change", "SelectedValueChanged": "change",
          "TextChanged": "change", "ValueChanged": "change", "CheckedChanged": "change", "EditValueChanged": "change",
          "KeyDown": "keydown", "KeyUp": "keyup", "KeyPress": "keypress", "Enter": "focus", "Leave": "blur",
          "AfterSelect": "change", "NodeMouseDoubleClick": "dblclick", "Load": "onload", "Shown": "onload",
          "FormClosing": "close", "FormClosed": "close", "ItemClicked": "click"}
FORM_PROPS = {"Text", "ClientSize", "FormBorderStyle", "Name", "StartPosition", "AutoScaleDimensions", "AutoScaleMode",
              "MainMenuStrip", "CancelButton", "AcceptButton", "MaximizeBox", "MinimizeBox", "Size"}
POPUP_BORDERS = {"FixedDialog", "FixedToolWindow", "SizableToolWindow"}


def _point(n: Node | None) -> tuple[float, float] | None:
    """new System.Drawing.Point(12, 34) / new Size(1, 2) → (12, 34)"""
    mm = re.search(r"\(\s*(-?[\d.]+)F?\s*,\s*(-?[\d.]+)F?\s*\)", text(n) if n is not None else "")
    return (float(mm.group(1)), float(mm.group(2))) if mm else None


def _member(path: str) -> list[str]:
    return re.sub(r"^this\.", "", path).split(".")


class Designer:
    """InitializeComponent 해석 결과."""

    def __init__(self, cls: CsClass):
        self.cls = cls
        self.types: dict[str, str] = {}                 # 컨트롤 → 타입(단순)
        self.props: dict[str, dict] = {}                # 컨트롤 → {Location, Size, Text, …}
        self.parent: dict[str, str] = {}                # 컨트롤 → 부모 컨트롤 ("" = 폼)
        self.order: list[str] = []                      # 생성 순서
        self.items: dict[str, list[str]] = {}           # MenuStrip/ToolStrip/메뉴 → 하위 항목
        self.columns: dict[str, list[str]] = {}         # 그리드 → 열 컨트롤
        self.events: dict[str, list[tuple[str, str, int]]] = {}   # 컨트롤("" = 폼) → [(이벤트, 핸들러, 줄)]
        self.form: dict = {}
        self.lambdas: dict[tuple[str, str], Node] = {}
        d = cls.designer
        body = d["node"].child_by_field_name("body")
        for st in (body.named_children if body is not None else []):
            if st.type == "expression_statement" and st.named_children:
                self._stmt(st.named_children[0])

    def _stmt(self, e: Node) -> None:
        if e.type == "assignment_expression":
            left, right = e.child_by_field_name("left"), e.child_by_field_name("right")
            op = text(e.children[1]) if len(e.children) > 2 else "="
            path = _member(text(left))
            if op == "=" and len(path) == 1 and path[0] in FORM_PROPS:
                self.form[path[0]] = right
                return
            if op == "=" and len(path) == 1 and right.type == "object_creation_expression":
                name = path[0]
                if name not in self.types:
                    self.types[name] = simple_type(text(right.child_by_field_name("type")))
                    self.order.append(name)
                return
            if op == "+=" and len(path) >= 1:
                ctrl, ev = ("", path[0]) if len(path) == 1 else (path[0], path[-1])
                handler = None
                if right.type == "object_creation_expression":
                    al = right.child_by_field_name("arguments")
                    handler = _member(text(al.named_children[0]))[-1] if al is not None and al.named_children else None
                elif right.type in ("member_access_expression", "identifier"):
                    handler = _member(text(right))[-1]
                elif right.type in ("lambda_expression", "anonymous_method_expression"):
                    handler = f"lambda@L{right.start_point[0] + 1}"
                    self.lambdas[(ctrl, ev)] = right
                if handler:
                    self.events.setdefault(ctrl, []).append((ev, handler, e.start_point[0] + 1))
                return
            if op == "=" and len(path) >= 2:
                ctrl, prop = path[0], ".".join(path[1:])
                self.props.setdefault(ctrl, {})[prop] = right
            return
        if e.type == "invocation_expression":
            fn = text(e.child_by_field_name("function"))
            al = e.child_by_field_name("arguments")
            args = [a.named_children[-1] for a in (al.named_children if al is not None else []) if a.named_children]
            path = _member(fn)
            if path[-1] == "Add" and len(path) >= 2 and path[-2] == "Controls" and args:
                owner = path[0] if len(path) == 3 else ""
                child = _member(text(args[0]))[-1]
                self.parent[child] = owner
            elif path[-1] == "AddRange" and len(path) >= 3 and args:
                coll, owner = path[-2], path[0]
                names = [_member(text(x))[-1] for x in _iter(args[0]) if x.type == "member_access_expression"
                         and text(x).startswith("this.") and x.parent is not None and x.parent.type != "member_access_expression"]
                if coll in ("Items", "DropDownItems") and names:
                    self.items.setdefault(owner, []).extend(names)
                elif coll == "Columns":
                    self.columns.setdefault(owner, []).extend(names)
                elif coll == "Controls":
                    for nm in names:
                        self.parent[nm] = owner

    # ---------- 조회 ----------
    def prop(self, ctrl: str, name: str) -> Node | None:
        return self.props.get(ctrl, {}).get(name)

    def str_prop(self, ctrl: str, name: str) -> str | None:
        n = self.prop(ctrl, name) if ctrl else self.form.get(name)
        if n is not None and n.type in ("string_literal", "verbatim_string_literal"):
            return _literal(n)
        return None

    def num_prop(self, ctrl: str, name: str) -> float | None:
        n = self.prop(ctrl, name)
        try:
            return float(text(n)) if n is not None else None
        except ValueError:
            return None

    def abs_box(self, ctrl: str, depth: int = 0) -> tuple[float, float, float, float] | None:
        loc, size = _point(self.prop(ctrl, "Location")), _point(self.prop(ctrl, "Size"))
        if loc is None or size is None:
            return None
        x, y = loc
        p = self.parent.get(ctrl, "")
        if p and depth < 10:
            pb = self.abs_box(p, depth + 1)
            if pb is None:
                return None
            x, y = x + pb[0], y + pb[1]
        return x, y, size[0], size[1]

    def visible_tab(self, ctrl: str) -> bool:
        """TabControl 의 첫 페이지가 아닌 TabPage 안이면 False (와이어프레임에 안 그림)."""
        p = self.parent.get(ctrl, "")
        while p:
            if self.types.get(p) in ("TabPage", "XtraTabPage"):
                tc = self.parent.get(p, "")
                pages = [c for c in self.order if self.parent.get(c) == tc and self.types.get(c) in ("TabPage", "XtraTabPage")]
                if pages and pages[0] != p:
                    return False
            p = self.parent.get(p, "")
        return True


class WinFormsParser:
    name = "winforms"
    layer = "ui"

    def detect(self, root: Path) -> float:
        return 1.0 if any(root.rglob("*.Designer.cs")) else 0.0

    def parse(self, ctx: Context) -> None:
        self.ctx = ctx
        idx = get_index(ctx)
        register_methods(ctx, idx)
        self.idx = idx
        forms = [c for c in idx.classes.values() if c.designer and idx.is_form(c)]
        if not forms:
            ctx.model.fail("parse", "source.winforms", "WinForms 폼(InitializeComponent 가 있는 Form 파생 클래스)을 찾지 못함")
            return
        self.class_screen: dict[str, str] = {}
        for c in forms:
            self.class_screen[c.name] = scr_key(ctx.config.sys_code, c.name)
        menus: list[tuple[CsClass, Designer]] = []
        for c in sorted(forms, key=lambda c: c.name):
            d = Designer(c)
            self._form(c, d)
            if any(d.types.get(k) in ("MenuStrip", "MainMenu") for k in d.items):
                menus.append((c, d))
        resolve_class_navigation(ctx, idx, self.class_screen)
        for c, d in menus:
            self._menus(c, d)
        link_menus(ctx)
        refine_popup_types(ctx)

    # ---------- 폼 ----------
    def _form(self, c: CsClass, d: Designer) -> None:
        ctx, cfg, m = self.ctx, self.ctx.config, self.ctx.model
        skey = self.class_screen[c.name]
        code_file = next((f for f in c.files if not f.name.endswith(".Designer.cs")), c.files[0])
        title = d.str_prop("", "Text") or c.name
        border = text(d.form.get("FormBorderStyle")) if d.form.get("FormBorderStyle") is not None else ""
        popup = any(b in border for b in POPUP_BORDERS) or bool(re.search(r"(Popup|Dialog|Pop|P\d{2})$", c.name))
        m.screens[skey] = {"key": skey, "screenId": c.name, "name": title, "kind": "popup" if popup else "page",
                           "uiTech": "winforms", "sourceFile": cfg.rel(code_file), "url": f"winforms:{c.full}", "captures": []}
        ctx.screen_by_url[f"winforms:{c.full}"] = skey
        cw, ch = _point(d.form.get("ClientSize")) or (800.0, 600.0)
        dfile = cfg.rel(d.cls.designer["file"])

        labels: list[tuple[str, tuple]] = []
        for ctrl in d.order:
            if d.types.get(ctrl) in LABELS:
                b = d.abs_box(ctrl)
                if b and d.str_prop(ctrl, "Text"):
                    labels.append((d.str_prop(ctrl, "Text"), b))

        wire: list[dict] = []
        seq = 0
        menu_items = {i for k in d.items for i in d.items[k]}
        for ctrl in d.order:
            typ = d.types[ctrl]
            box = d.abs_box(ctrl)
            visible = d.visible_tab(ctrl)
            if typ in CONTAINERS:
                if box and visible and typ not in ("TabPage", "XtraTabPage"):
                    wire.append({"kind": "div", "box": list(box)})
                    if typ in ("GroupBox", "GroupControl") and d.str_prop(ctrl, "Text"):
                        wire.append({"kind": "static", "box": [box[0] + 8, box[1] - 2, box[2] - 16, 16], "text": d.str_prop(ctrl, "Text")})
                continue
            if typ in LABELS:
                if box and visible:
                    wire.append({"kind": "static", "box": list(box), "text": d.str_prop(ctrl, "Text") or ""})
                continue
            etype = CONTROL_TYPES.get(typ)
            if etype is None or ctrl in menu_items and typ != "ToolStripButton" and not d.events.get(ctrl):
                continue
            if ctrl in menu_items:
                etype = "link" if typ == "ToolStripMenuItem" else etype
            seq += 1
            ekey = el_key(skey, ctrl)
            label = self._label(d, ctrl, etype, box, labels)
            entry = {"key": ekey, "screenKey": skey, "elementId": ctrl, "label": label, "type": etype,
                     "matched": "static-only", "_seq": seq, "_tag": typ}
            if box:
                entry["box"] = {"x": box[0], "y": box[1], "w": box[2], "h": box[3], "captureIndex": 0}
                entry["_sbox"] = box
            if typ in ("DataGridView", "ListView") and d.columns.get(ctrl):
                cols = [d.str_prop(col, "DataPropertyName") or d.str_prop(col, "HeaderText") or col for col in d.columns[ctrl]]
                entry["binding"] = "cols: " + ", ".join(cols)
            m.elements[ekey] = entry
            if box and visible:
                if etype == "grid":
                    cols = [(d.str_prop(col, "HeaderText") or col, d.num_prop(col, "Width") or 100.0) for col in d.columns.get(ctrl, [])]
                    wire.append({"kind": "grid", "box": list(box), "cols": cols})
                elif etype == "tab":
                    pages = [p for p in d.order if d.parent.get(p) == ctrl and d.types.get(p) in ("TabPage", "XtraTabPage")]
                    wire.append({"kind": "tab", "box": list(box), "tabs": [d.str_prop(p, "Text") or p for p in pages]})
                else:
                    wire.append({"kind": WIRE_KIND.get(etype, "other"), "box": list(box),
                                 "text": (d.str_prop(ctrl, "Text") or "") if etype in ("button", "check", "radio", "link") else ""})
            self._events(c, d, ctrl, ekey, dfile)
        # 폼 이벤트 (Load 등)
        for ev, handler, line in d.events.get("", []):
            meth = self.idx.find_method(c, handler)
            if meth:
                m.add_edge(skey, self.idx.key(meth), "event", event=EVENTS.get(ev, ev.lower()), confidence=0.99,
                           evidence=f"{dfile}:{line}")
                if EVENTS.get(ev) == "onload":
                    ctx.screen_onload.setdefault(skey, []).append(self.idx.key(meth))
        if cfg.get("winforms.wireframe", True):
            ctx.wireframes[skey] = {"width": cw, "height": ch, "title": title, "items": wire}

    @staticmethod
    def _label(d: Designer, ctrl: str, etype: str, box, labels) -> str:
        own = d.str_prop(ctrl, "Text")
        if etype in ("button", "check", "radio", "link") and own and re.search(r"[0-9A-Za-z가-힣]", own):
            return own
        if box:
            x, y, w, h = box
            best, dist = None, 1e9
            for t, (lx, ly, lw, lh) in labels:
                if etype in ("grid", "tab", "tree"):
                    if ly + lh <= y + 2 and y - (ly + lh) < 40 and lx < x + w and lx + lw > x and y - (ly + lh) < dist:
                        best, dist = t, y - (ly + lh)
                elif abs((ly + lh / 2) - (y + h / 2)) <= 10 and lx + lw <= x + 8 and x - (lx + lw) < 160 and x - (lx + lw) < dist:
                    best, dist = t, x - (lx + lw)
            if best:
                return best
        p = d.parent.get(ctrl, "")
        if etype in ("grid", "tree") and p and d.str_prop(p, "Text"):
            return d.str_prop(p, "Text")
        if own and etype != "input":
            return own
        return ctrl

    def _events(self, c: CsClass, d: Designer, ctrl: str, ekey: str, dfile: str) -> None:
        m = self.ctx.model
        for ev, handler, line in d.events.get(ctrl, []):
            meth = self.idx.find_method(c, handler)
            if meth is not None:
                m.add_edge(ekey, self.idx.key(meth), "event", event=EVENTS.get(ev, ev.lower()), confidence=0.99,
                           evidence=f"{dfile}:{line}")
            elif handler.startswith("lambda@"):
                m.fail("parse", ekey, f"람다 이벤트 핸들러({ev})는 본문 분석을 하지 않습니다 ({dfile}:{line})")
            else:
                m.fail("parse", ekey, f"이벤트 핸들러 '{handler}' 정의를 찾지 못함 ({ev}, {dfile}:{line})")

    # ---------- 메뉴 ----------
    def _menus(self, c: CsClass, d: Designer) -> None:
        cfg, m = self.ctx.config, self.ctx.model
        strips = [k for k in d.items if d.types.get(k) in ("MenuStrip", "MainMenu")]
        menu_paths: list[dict] = []

        def walk(owner: str, parent_key: str | None, path: list[str]):
            for order, item in enumerate(d.items.get(owner, []), 1):
                label = d.str_prop(item, "Text") or item
                mkey = menu_key(cfg.sys_code, item)
                entry = {"key": mkey, "parentKey": parent_key, "name": label.replace("&", ""), "order": order, "screenKey": None}
                handler = next((h for ev, h, _ in d.events.get(item, []) if ev == "Click"), None)
                if handler:
                    meth = self.idx.find_method(c, handler)
                    target = self._nav_target(self.idx.key(meth)) if meth else None
                    if target:
                        entry["screenKey"] = target
                        entry["url"] = m.screens[target].get("url")
                    elif not d.items.get(item):
                        m.fail("menu", mkey, f"메뉴 '{label}' 클릭 시 여는 화면을 찾지 못함 (핸들러 {handler})")
                m.menus[mkey] = entry
                p = path + [label.replace("&", "")]
                if d.items.get(item):
                    walk(item, mkey, p)
                else:
                    menu_paths.append({"menuKey": mkey, "path": p, "screenKey": entry["screenKey"],
                                       "ownerScreen": self.class_screen[c.name]})
        for s in strips:
            walk(s, None, [])
        self.ctx.desktop_menus = getattr(self.ctx, "desktop_menus", []) + menu_paths  # type: ignore[attr-defined]

    def _nav_target(self, fkey: str, depth: int = 0) -> str | None:
        """메뉴 핸들러 → (calls 따라) 처음 만나는 navigates 대상 화면."""
        m = self.ctx.model
        for e in m.edges_from(fkey, "navigates"):
            return e["to"]
        if depth < 3:
            for e in m.edges_from(fkey, "calls"):
                t = self._nav_target(e["to"], depth + 1)
                if t:
                    return t
        return None


def resolve_class_navigation(ctx: Context, idx: CsIndex, class_screen: dict[str, str]) -> None:
    """dotnet.py 가 모은 'new XxxForm()' 대기 목록 → navigates 엣지 (ShowDialog 면 팝업)."""
    m = ctx.model
    rest = []
    for item in idx.pending_nav:
        fkey, kind, target, conf, ev, popup = item
        if kind != "class":
            rest.append(item)
            continue
        skey = class_screen.get(target)
        if not skey:
            continue
        m.add_edge(fkey, skey, "navigates", confidence=conf, evidence=ev)
        if popup:
            m.screens[skey]["kind"] = "popup"
    idx.pending_nav = rest
