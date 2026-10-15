""".NET(C#) 서버·데이터 접근 파서 — docs/13 §9 (dotnet-data).

C# 소스 전체를 tree-sitter 로 색인(CsIndex)하고, 모든 메서드를 코드 단위로 등록한 뒤 본문을 훑어
  · 호출 그래프(calls): 필드·속성·지역변수·매개변수 타입 → 클래스(인터페이스면 구현체) → 메서드
  · 데이터 접근(executes → accesses):
      ADO.NET   new SqlCommand("…", conn) / cmd.CommandText = … / CommandType.StoredProcedure
      Dapper    conn.Query<T>("…") / Execute("…", commandType: CommandType.StoredProcedure)
      EF        _db.Depts.Add(…) / .Where(…) / SaveChanges() — DbSet<T> → [Table("X")] 또는 엔터티명 → 테이블
    SQL 문자열은 문자열 리터럴·@"verbatim"·$"보간"·+ 연결·지역변수 누적(+=)·StringBuilder.Append·const 를 따라 조립한다.
  · 화면 이동(대기 목록, UI 파서가 해석): new XxxForm().Show()/ShowDialog(), Response.Redirect, RedirectToAction
  · 확인창 조건: MessageBox.Show(…, MessageBoxButtons.YesNo|OKCancel) → confirm("…")
  · ASP.NET MVC 컨트롤러 액션 → URL 매핑(Route): [Route]/[HttpGet("…")] 템플릿 또는 /{컨트롤러}/{액션} 관례
  · ASP.NET WebForms [WebMethod] → /{페이지}.aspx/{메서드}
WinForms(winforms.py)·ASP.NET(aspnet.py) 화면 파서는 같은 색인(get_index)을 공유한다.
"""
from __future__ import annotations

import fnmatch
import re
from dataclasses import dataclass, field
from pathlib import Path

from tree_sitter import Node

from ..keys import fn_key, sp_key, tbl_key
from . import Context, Route
from .ddl import dialect_of
from .sql_analyzer import analyze
from .ts import parse, text
from .ui_common import screen_for_url

EXCLUDE_DIRS = {"bin", "obj", ".vs", "packages", "node_modules", "TestResults", ".git"}
COMMAND_TYPES = re.compile(r"(Sql|Oracle|OleDb|Odbc|MySql|Npgsql|Db|SQLite|Tibero)?Command$")
DAPPER_METHODS = {"Query", "QueryAsync", "QueryFirst", "QueryFirstAsync", "QueryFirstOrDefault", "QueryFirstOrDefaultAsync",
                  "QuerySingle", "QuerySingleAsync", "QuerySingleOrDefault", "QuerySingleOrDefaultAsync", "QueryMultiple",
                  "QueryMultipleAsync", "Execute", "ExecuteAsync", "ExecuteScalar", "ExecuteScalarAsync", "ExecuteReader",
                  "ExecuteReaderAsync"}
EF_CREATE = {"Add", "AddRange", "AddAsync", "AddRangeAsync"}
EF_DELETE = {"Remove", "RemoveRange"}
EF_UPDATE = {"Update", "UpdateRange", "Attach", "AttachRange"}
FORM_BASES = {"Form", "XtraForm", "RibbonForm", "MetroForm", "KryptonForm"}
CONTROLLER_BASES = {"Controller", "ControllerBase", "ApiController", "AsyncController"}
HTTP_ATTRS = {"HttpGet": "GET", "HttpPost": "POST", "HttpPut": "PUT", "HttpDelete": "DELETE", "HttpPatch": "PATCH"}
SKIP_METHODS = {"InitializeComponent", "Dispose"}
CONFIRM_BUTTONS = re.compile(r"MessageBoxButtons\.(YesNo|OKCancel|YesNoCancel|RetryCancel)|MsgBoxStyle\.(YesNo|OkCancel)")


def _unescape(s: str) -> str:
    return re.sub(r"\\(.)", lambda m: {"n": "\n", "t": "\t", "r": "\r", "0": ""}.get(m.group(1), m.group(1)), s)


def simple_type(t: str) -> str:
    """System.Windows.Forms.Button → Button, IList<Emp> → IList, Emp? → Emp, Emp[] → Emp."""
    t = re.sub(r"<.*>", "", t or "").strip().rstrip("?").replace("[]", "")
    return t.rsplit(".", 1)[-1]


def generic_arg(t: str) -> str | None:
    mm = re.search(r"<\s*([\w.]+)\s*>", t or "")
    return mm.group(1).rsplit(".", 1)[-1] if mm else None


@dataclass
class CsMethod:
    name: str
    cls: "CsClass"
    node: Node
    file: Path
    src: str
    params: dict[str, str]                 # 이름 → 타입
    attrs: dict[str, list[str]]            # 특성 이름 → 문자열 인자
    returns: str
    public: bool
    key: str = ""

    @property
    def line(self) -> int:
        return self.node.start_point[0] + 1

    @property
    def end(self) -> int:
        return self.node.end_point[0] + 1


@dataclass
class CsClass:
    name: str
    ns: str
    kind: str                              # class / interface / struct / record
    bases: list[str] = field(default_factory=list)
    attrs: dict[str, list[str]] = field(default_factory=dict)
    files: list[Path] = field(default_factory=list)
    fields: dict[str, str] = field(default_factory=dict)        # 필드·속성 → 선언 타입
    field_init: dict[str, str] = field(default_factory=dict)    # 필드 → 초기화 new T(...) 의 T (인터페이스 타입 필드의 실제 구현)
    consts: dict[str, str] = field(default_factory=dict)        # const/readonly 문자열
    methods: dict[str, list[CsMethod]] = field(default_factory=dict)
    designer: dict | None = None           # WinForms InitializeComponent 노드·파일 (winforms.py 가 해석)

    @property
    def full(self) -> str:
        return f"{self.ns}.{self.name}" if self.ns else self.name


def _attrs(node: Node) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    for al in node.children:
        if al.type != "attribute_list":
            continue
        for a in al.named_children:
            if a.type != "attribute":
                continue
            name = text(a.child_by_field_name("name")).rsplit(".", 1)[-1]
            name = re.sub(r"Attribute$", "", name)
            vals = []
            for arg in (n for c in a.named_children if c.type == "attribute_argument_list" for n in c.named_children):
                for lit in _iter(arg):
                    if lit.type in ("string_literal", "verbatim_string_literal"):
                        vals.append(_literal(lit))
                        break
            out[name] = vals
    return out


def _iter(node: Node):
    stack = [node]
    while stack:
        n = stack.pop()
        yield n
        stack.extend(reversed(n.children))


def _literal(n: Node) -> str:
    t = text(n)
    if n.type == "verbatim_string_literal":
        return t[2:-1].replace('""', '"')
    if n.type == "raw_string_literal":
        return t.strip('"')
    if n.type == "string_literal":
        return _unescape(t[1:-1])
    if n.type == "character_literal":
        return _unescape(t[1:-1])
    return t


def _decl_name(d: Node) -> str:
    return text(d.child_by_field_name("name") or (d.named_children[0] if d.named_children else None))


def _decl_value(d: Node) -> Node | None:
    """variable_declarator 의 초기값 (이름 다음 첫 named child, '=' 는 익명 노드)."""
    kids = d.named_children
    return kids[1] if len(kids) > 1 else None


class CsIndex:
    """C# 소스 색인 — 클래스·필드·메서드·특성. partial 클래스는 전체 이름으로 합친다."""

    def __init__(self, ctx: Context, roots: list[Path], exclude: list[str]):
        self.ctx = ctx
        self.classes: dict[str, CsClass] = {}            # 전체 이름 → 클래스
        self.by_name: dict[str, list[CsClass]] = {}      # 단순 이름 → 클래스들
        self.impls: dict[str, list[CsClass]] = {}        # 인터페이스/기반 단순 이름 → 구현 클래스
        self.files: list[Path] = []
        self.vb_files: list[Path] = []
        self.pending_nav: list[tuple[str, str, str, float, str, bool]] = []   # (fkey, 'class'|'url'|'action', 대상, conf, 근거, popup)
        self.scanned = False
        seen: set[Path] = set()
        for root in roots:
            if not root or not root.is_dir():
                continue
            for f in sorted(root.rglob("*")):
                if f in seen or not f.is_file() or set(p.name for p in f.parents) & EXCLUDE_DIRS:
                    continue
                rel = ctx.config.rel(f)
                if any(fnmatch.fnmatch(rel, pat) or fnmatch.fnmatch(f.name, pat) for pat in exclude):
                    continue
                if f.suffix.lower() == ".cs":
                    seen.add(f)
                    self.files.append(f)
                    self._file(f)
                elif f.suffix.lower() == ".vb":
                    self.vb_files.append(f)
        for c in self.classes.values():
            self.by_name.setdefault(c.name, []).append(c)
            for b in c.bases:
                self.impls.setdefault(simple_type(b), []).append(c)

    # ---------- 색인 ----------
    def _file(self, f: Path) -> None:
        src = f.read_text(encoding="utf-8-sig")
        tree = parse("cs", src)
        file_ns = ""
        for n in tree.root_node.named_children:
            if n.type == "file_scoped_namespace_declaration":
                file_ns = text(n.child_by_field_name("name"))
        self._walk(tree.root_node, file_ns, f, src)

    def _walk(self, node: Node, ns: str, f: Path, src: str) -> None:
        for n in node.named_children:
            if n.type == "namespace_declaration":
                inner = text(n.child_by_field_name("name"))
                body = n.child_by_field_name("body")
                if body is not None:
                    self._walk(body, f"{ns}.{inner}" if ns else inner, f, src)
            elif n.type in ("class_declaration", "interface_declaration", "struct_declaration", "record_declaration"):
                self._class(n, ns, f, src)
            elif n.type == "declaration_list":
                self._walk(n, ns, f, src)

    def _class(self, n: Node, ns: str, f: Path, src: str) -> None:
        name = text(n.child_by_field_name("name"))
        full = f"{ns}.{name}" if ns else name
        c = self.classes.get(full)
        if c is None:
            c = self.classes[full] = CsClass(name, ns, n.type.split("_")[0])
        if f not in c.files:
            c.files.append(f)
        c.attrs.update(_attrs(n))
        for bl in (x for x in n.children if x.type == "base_list"):
            c.bases += [text(b) for b in bl.named_children if text(b) not in c.bases]
        body = n.child_by_field_name("body")
        if body is None:
            return
        for m in body.named_children:
            if m.type == "field_declaration":
                vd = next((x for x in m.named_children if x.type == "variable_declaration"), None)
                if vd is None:
                    continue
                typ = text(vd.child_by_field_name("type"))
                mods = {text(x) for x in m.children if x.type == "modifier"}
                for d in (x for x in vd.named_children if x.type == "variable_declarator"):
                    nm, val = _decl_name(d), _decl_value(d)
                    c.fields[nm] = typ
                    if val is not None and val.type == "object_creation_expression":
                        c.field_init[nm] = text(val.child_by_field_name("type"))
                    if val is not None and ("const" in mods or "readonly" in mods or "static" in mods):
                        s = self.eval_str(val, {}, c)
                        if s is not None:
                            c.consts[nm] = s
            elif m.type == "property_declaration":
                c.fields[text(m.child_by_field_name("name"))] = text(m.child_by_field_name("type"))
                val = m.child_by_field_name("value")
                if val is not None and val.type == "object_creation_expression":
                    c.field_init[text(m.child_by_field_name("name"))] = text(val.child_by_field_name("type"))
            elif m.type in ("method_declaration", "constructor_declaration"):
                mname = text(m.child_by_field_name("name"))
                if m.type == "constructor_declaration":
                    mname = ".ctor"
                params: dict[str, str] = {}
                pl = m.child_by_field_name("parameters")
                for p in (pl.named_children if pl is not None else []):
                    if p.type == "parameter":
                        params[text(p.child_by_field_name("name"))] = text(p.child_by_field_name("type"))
                mods = {text(x) for x in m.children if x.type == "modifier"}
                meth = CsMethod(mname, c, m, f, src, params, _attrs(m), text(m.child_by_field_name("returns")),
                                "public" in mods)
                if mname == "InitializeComponent":
                    c.designer = {"node": m, "file": f, "src": src}
                c.methods.setdefault(mname, []).append(meth)
                if m.type == "constructor_declaration":
                    self._ctor_assignments(c, meth)
            elif m.type in ("class_declaration", "interface_declaration", "struct_declaration", "record_declaration"):
                self._class(m, ns, f, src)          # 중첩 클래스는 같은 네임스페이스의 독립 클래스로 본다

    @staticmethod
    def _ctor_assignments(c: CsClass, meth: CsMethod) -> None:
        """생성자 주입: _x = x; (매개변수 타입을 필드 실제 타입으로)"""
        body = meth.node.child_by_field_name("body")
        for n in _iter(body) if body is not None else []:
            if n.type == "assignment_expression":
                left = re.sub(r"^this\.", "", text(n.child_by_field_name("left")))
                right = text(n.child_by_field_name("right"))
                if left in c.fields and right in meth.params:
                    c.field_init.setdefault(left, meth.params[right])

    # ---------- 조회 ----------
    def resolve(self, typ: str | None) -> list[CsClass]:
        """타입 이름 → 클래스들. 인터페이스·추상 기반이면 구현 클래스."""
        if not typ:
            return []
        st = simple_type(typ)
        out = []
        for c in self.by_name.get(st, []):
            if c.kind == "interface" or not any(c.methods.values()):
                out += [i for i in self.impls.get(st, []) if i.kind != "interface"]
            else:
                out.append(c)
        return out or [c for c in self.by_name.get(st, [])]

    def find_method(self, c: CsClass, name: str, depth: int = 0) -> CsMethod | None:
        if name in c.methods:
            return c.methods[name][0]
        if depth > 5:
            return None
        for b in c.bases:
            for bc in self.by_name.get(simple_type(b), []):
                m = self.find_method(bc, name, depth + 1)
                if m:
                    return m
        return None

    def is_form(self, c: CsClass, depth: int = 0) -> bool:
        if depth > 6:
            return False
        for b in c.bases:
            sb = simple_type(b)
            if sb in FORM_BASES or any(self.is_form(bc, depth + 1) for bc in self.by_name.get(sb, []) if bc is not c):
                return True
        return False

    def is_controller(self, c: CsClass) -> bool:
        return c.name.endswith("Controller") and any(simple_type(b) in CONTROLLER_BASES for b in c.bases)

    def db_sets(self) -> dict[str, str]:
        """DbContext 파생 클래스의 DbSet<T> 속성 이름 → 엔터티 타입."""
        out = {}
        for c in self.classes.values():
            if any(simple_type(b).endswith("DbContext") for b in c.bases):
                for p, t in c.fields.items():
                    if simple_type(t) in ("DbSet", "IDbSet") and generic_arg(t):
                        out[p] = generic_arg(t)
        return out

    def key(self, m: CsMethod) -> str:
        if not m.key:
            name = m.cls.name if m.name == ".ctor" else f"{m.cls.name}.{m.name}"
            m.key = fn_key("cs", self.ctx.config.rel(m.file), name)
        return m.key

    # ---------- 문자열 평가 ----------
    def eval_str(self, n: Node | None, strs: dict[str, str], c: CsClass | None) -> str | None:
        """SQL 문자열 조립 — 해석 못 하는 조각은 '?' (전부 못 하면 None)."""
        if n is None:
            return None
        t = n.type
        if t in ("string_literal", "verbatim_string_literal", "raw_string_literal"):
            return _literal(n)
        if t == "interpolated_string_expression":
            out = []
            for ch in n.named_children:
                if ch.type in ("string_content", "interpolated_verbatim_string_text", "interpolated_string_text", "escape_sequence"):
                    out.append(_unescape(text(ch)) if ch.type == "escape_sequence" else text(ch))
                elif ch.type == "interpolation":
                    out.append("?")
            return "".join(out)
        if t == "parenthesized_expression" and n.named_children:
            return self.eval_str(n.named_children[0], strs, c)
        if t == "binary_expression" and text(n.children[1]) == "+":
            a = self.eval_str(n.child_by_field_name("left"), strs, c)
            b = self.eval_str(n.child_by_field_name("right"), strs, c)
            if a is None and b is None:
                return None
            return (a if a is not None else "?") + (b if b is not None else "?")
        if t == "identifier":
            nm = text(n)
            if nm in strs:
                return strs[nm]
            if c is not None and nm in c.consts:
                return c.consts[nm]
            return None
        if t == "member_access_expression":
            obj = text(n.child_by_field_name("expression"))
            nm = text(n.child_by_field_name("name"))
            if obj == "this" and c is not None:
                return c.consts.get(nm)
            for oc in self.by_name.get(obj, []):
                if nm in oc.consts:
                    return oc.consts[nm]
            return None
        if t == "invocation_expression":
            fn = n.child_by_field_name("function")
            ft = text(fn)
            args = _args(n)
            if ft.endswith(".ToString") and fn.type == "member_access_expression":
                return strs.get(text(fn.child_by_field_name("expression")))
            if ft in ("string.Format", "String.Format") and args:
                s = self.eval_str(args[0], strs, c)
                return re.sub(r"\{\d+(:[^}]*)?\}", "?", s) if s is not None else None
            if ft in ("string.Concat", "String.Concat"):
                parts = [self.eval_str(a, strs, c) for a in args]
                return "".join(p if p is not None else "?" for p in parts) if any(p is not None for p in parts) else None
        if t == "conditional_expression":
            return self.eval_str(n.child_by_field_name("consequence"), strs, c)
        return None


def _args(inv: Node) -> list[Node]:
    al = inv.child_by_field_name("arguments")
    out = []
    for a in (al.named_children if al is not None else []):
        if a.type == "argument":
            out.append(a.named_children[-1] if a.named_children else a)      # 이름 인자(name: expr)는 마지막 자식이 값
    return out


def _named_args(inv: Node) -> dict[str, str]:
    al = inv.child_by_field_name("arguments")
    out = {}
    for a in (al.named_children if al is not None else []):
        mm = re.match(r"\s*(\w+)\s*:\s*(.+)", text(a), re.S)
        if a.type == "argument" and mm and not text(a).lstrip().startswith(("@\"", "\"", "$\"")):
            out[mm.group(1)] = mm.group(2).strip()
    return out


def get_index(ctx: Context) -> CsIndex:
    idx = getattr(ctx, "_cs_index", None)
    if idx is None:
        cfg = ctx.config
        roots = [cfg.source_dir(k) for k in ("dotnet", "winforms", "aspnet", "webforms", "mvc") if cfg.get(f"source.{k}")]
        roots = [r for r in roots if r] or [cfg.source_root]
        idx = CsIndex(ctx, roots, list(cfg.get("dotnet.exclude", []) or []))
        ctx._cs_index = idx  # type: ignore[attr-defined]
        if idx.vb_files:
            ctx.model.fail("parse", "dotnet", f"VB.NET 소스 {len(idx.vb_files)}개는 아직 분석하지 않습니다 (C#만 지원)")
    return idx


# ---------------------------------------------------------------- 메서드 본문 스캔
class MethodScanner:
    """메서드 1개 본문 → calls / executes / accesses / 화면 이동 대기 / 확인창 조건."""

    def __init__(self, ctx: Context, idx: CsIndex, meth: CsMethod):
        self.ctx, self.idx, self.m = ctx, idx, meth
        self.model = ctx.model
        self.fkey = idx.key(meth)
        self.locals: dict[str, str] = dict(meth.params)    # 지역변수·매개변수 → 타입
        self.strs: dict[str, str] = {}                     # 문자열 지역변수 / StringBuilder 누적
        self.cmd_vars: dict[str, dict] = {}                # SqlCommand 변수 → {"sql":…, "sp":bool, "node":…}
        self.form_vars: dict[str, str] = {}                # 폼 변수 → 폼 클래스
        self.ef_vars: dict[str, str] = {}                  # 엔터티 지역변수 → DbSet 속성
        self.sql_seq = 0
        self.confirm: str | None = None
        self.sqls: list[tuple[str, bool, Node]] = []       # (텍스트, SP 여부, 근거 노드)
        self.ef: dict[str, set[str]] = {}                  # DbSet 속성 → CRUD
        self.ef_nodes: dict[str, Node] = {}
        self.savechanges = False
        self.db_sets = idx.db_sets()

    def ev(self, n: Node) -> str:
        return f"{self.ctx.config.rel(self.m.file)}:{n.start_point[0] + 1}"

    def run(self) -> None:
        body = self.m.node.child_by_field_name("body")
        if body is None:
            return
        for n in _iter(body):
            t = n.type
            if t == "variable_declaration":          # 지역 변수·using (var x = …)·for 초기화 모두
                self._local_decl(n)
            elif t == "assignment_expression":
                self._assign(n)
            elif t == "invocation_expression":
                self._invoke(n)
            elif t == "object_creation_expression":
                self._creation(n)
            elif t == "member_access_expression":
                self._ef_access(n)
        for var, info in self.cmd_vars.items():
            if info.get("sql") is not None:
                self.sqls.append((info["sql"], info.get("sp", False), info["node"]))
        self._emit_data()

    # ---------- 선언·대입 ----------
    def _local_decl(self, vd: Node) -> None:
        typ = text(vd.child_by_field_name("type"))
        for d in (x for x in vd.named_children if x.type == "variable_declarator"):
            nm, val = _decl_name(d), _decl_value(d)
            vt = typ
            if val is not None and val.type == "object_creation_expression":
                vt = text(val.child_by_field_name("type"))
            if vt and vt != "var":
                self.locals[nm] = vt
            if val is None:
                continue
            if simple_type(vt) == "StringBuilder":
                args = _args(val) if val.type == "object_creation_expression" else []
                self.strs[nm] = (self.idx.eval_str(args[0], self.strs, self.m.cls) or "") if args else ""
                continue
            s = self.idx.eval_str(val, self.strs, self.m.cls)
            if s is not None:
                self.strs[nm] = s
            if val.type == "object_creation_expression":
                ctype = simple_type(text(val.child_by_field_name("type")))
                if COMMAND_TYPES.search(ctype):
                    self._command_creation(val, nm)
                elif any(self.idx.is_form(c) for c in self.idx.by_name.get(ctype, [])):
                    self.form_vars[nm] = ctype
            # var dept = _db.Depts.Find(id)  → 엔터티 변수
            for sub in _iter(val):
                if sub.type == "member_access_expression" and text(sub.child_by_field_name("name")) in self.db_sets:
                    self.ef_vars[nm] = text(sub.child_by_field_name("name"))
                    break

    def _assign(self, n: Node) -> None:
        left = n.child_by_field_name("left")
        right = n.child_by_field_name("right")
        op = text(n.children[1]) if len(n.children) > 2 else "="
        lt = text(left)
        if left.type == "identifier":
            s = self.idx.eval_str(right, self.strs, self.m.cls)
            if op == "+=" and lt in self.strs:
                self.strs[lt] += s if s is not None else " ?"
            elif op == "=" and s is not None:
                self.strs[lt] = s
            return
        if left.type == "member_access_expression":
            obj = re.sub(r"^this\.", "", text(left.child_by_field_name("expression")))
            prop = text(left.child_by_field_name("name"))
            if prop == "CommandText":
                s = self.idx.eval_str(right, self.strs, self.m.cls)
                info = self.cmd_vars.setdefault(obj, {"node": n})
                info["sql"], info["node"] = s, n
            elif prop == "CommandType" and "StoredProcedure" in text(right):
                self.cmd_vars.setdefault(obj, {"node": n})["sp"] = True
            elif obj in self.ef_vars:          # dept.DeptNm = …  → 이후 SaveChanges 면 UPDATE
                self.ef.setdefault(self.ef_vars[obj], set()).add("u?")

    def _command_creation(self, val: Node, var: str | None) -> None:
        args = _args(val)
        sql = self.idx.eval_str(args[0], self.strs, self.m.cls) if args else None
        sp = False
        init = val.child_by_field_name("initializer")
        if init is not None:
            for a in init.named_children:
                if a.type == "assignment_expression":
                    k = text(a.child_by_field_name("left"))
                    if k == "CommandText":
                        sql = self.idx.eval_str(a.child_by_field_name("right"), self.strs, self.m.cls)
                    elif k == "CommandType" and "StoredProcedure" in text(a.child_by_field_name("right")):
                        sp = True
        key = var or f"#cmd{val.start_point[0]}"
        info = self.cmd_vars.setdefault(key, {"node": val})
        if sql is not None:
            info["sql"] = sql
        info["sp"] = info.get("sp", False) or sp
        info["node"] = val

    def _creation(self, n: Node) -> None:
        ctype = simple_type(text(n.child_by_field_name("type")))
        forms = [c for c in self.idx.by_name.get(ctype, []) if self.idx.is_form(c)]
        if forms:
            parent = n.parent
            # new F().ShowDialog() / var f = new F(); f.ShowDialog() / using var f = new F()
            popup = False
            if parent is not None and parent.type == "member_access_expression" and text(parent.child_by_field_name("name")) == "ShowDialog":
                popup = True
            var = None
            p = n.parent
            while p is not None and p.type not in ("variable_declarator", "block"):
                p = p.parent
            if p is not None and p.type == "variable_declarator":
                var = _decl_name(p)
            body_text = text(self.m.node)
            if var and re.search(rf"\b{re.escape(var)}\s*\.\s*ShowDialog\s*\(", body_text):
                popup = True
            self.idx.pending_nav.append((self.fkey, "class", ctype, 0.95, self.ev(n), popup))
            return
        if COMMAND_TYPES.search(ctype) and n.parent is not None and n.parent.type != "variable_declarator":
            var = None
            if n.parent.type == "assignment_expression":
                var = re.sub(r"^this\.", "", text(n.parent.child_by_field_name("left")))
            self._command_creation(n, var)

    # ---------- 호출 ----------
    def _invoke(self, n: Node) -> None:
        fn = n.child_by_field_name("function")
        ft = text(fn)
        args = _args(n)
        name = text(fn.child_by_field_name("name")) if fn.type == "member_access_expression" else ft
        name = re.sub(r"<.*>$", "", name)
        recv = fn.child_by_field_name("expression") if fn.type == "member_access_expression" else None
        recv_t = re.sub(r"^this\.", "", text(recv)) if recv is not None else None

        # 확인창
        if name == "Show" and recv_t in ("MessageBox", "XtraMessageBox", "MetroMessageBox") or ft in ("MsgBox", "Interaction.MsgBox"):
            if CONFIRM_BUTTONS.search(text(n)) and args:
                self.confirm = self.idx.eval_str(args[0], self.strs, self.m.cls) or text(args[0])
            return
        # StringBuilder 누적
        if recv_t in self.strs and name in ("Append", "AppendLine", "AppendFormat") and args:
            s = self.idx.eval_str(args[0], self.strs, self.m.cls)
            if name == "AppendFormat" and s is not None:
                s = re.sub(r"\{\d+(:[^}]*)?\}", "?", s)
            self.strs[recv_t] += (s if s is not None else "?") + ("\n" if name == "AppendLine" else "")
            return
        # 화면 이동 (WebForms / MVC)
        if ft in ("Response.Redirect", "Server.Transfer", "Server.Execute", "HttpContext.Current.Response.Redirect") and args:
            url = self.idx.eval_str(args[0], self.strs, self.m.cls)
            if url:
                self.idx.pending_nav.append((self.fkey, "url", url, 0.9, self.ev(n), False))
            return
        if name in ("RedirectToAction", "RedirectToActionPermanent") and args:
            action = self.idx.eval_str(args[0], self.strs, self.m.cls)
            ctrl = self.idx.eval_str(args[1], self.strs, self.m.cls) if len(args) > 1 else None
            if action:
                ctrl = ctrl or re.sub(r"Controller$", "", self.m.cls.name)
                self.idx.pending_nav.append((self.fkey, "url", f"/{ctrl}/{action}", 0.9, self.ev(n), False))
            return
        if "RegisterStartupScript" in name or "RegisterClientScriptBlock" in name:
            for a in args:
                s = self.idx.eval_str(a, self.strs, self.m.cls) or ""
                for url in re.findall(r"window\.open\(\s*['\"]([^'\"]+)['\"]", s):
                    self.idx.pending_nav.append((self.fkey, "url", url, 0.85, self.ev(n), True))
            return
        if name == "SaveChanges" or name == "SaveChangesAsync":
            self.savechanges = True
            return
        # Dapper
        if name in DAPPER_METHODS and args and recv is not None and not self._known_method(recv_t, name):
            s = self.idx.eval_str(args[0], self.strs, self.m.cls)
            if s is not None and s.strip():
                named = _named_args(n)
                sp = "StoredProcedure" in named.get("commandType", "") or any("StoredProcedure" in text(a) for a in args[1:])
                self.sqls.append((s, sp, n))
                return
        # 화면 폼 메서드: f.Show()/ShowDialog() 는 _creation 에서 처리
        targets = self._targets(recv_t, name, recv)
        for t in targets:
            if t is self.m:
                continue
            k = self.idx.key(t)
            if k != self.fkey:
                self.model.add_edge(self.fkey, k, "calls", confidence=0.95 if len(targets) == 1 else 0.85,
                                    condition=self.condition(n), evidence=self.ev(n))

    def _known_method(self, recv_t: str | None, name: str) -> bool:
        return any(self.idx.find_method(c, name) for c in self._recv_classes(recv_t))

    def _recv_classes(self, recv_t: str | None) -> list[CsClass]:
        cls = self.m.cls
        if recv_t is None or recv_t in ("this", "base"):
            return [cls]
        if recv_t in self.locals:
            return self.idx.resolve(self.locals[recv_t])
        if recv_t in cls.fields:
            return self.idx.resolve(cls.field_init.get(recv_t) or cls.fields[recv_t])
        # partial 클래스의 다른 파일 필드는 같은 CsClass 에 합쳐져 있음. 정적 호출: ClassName.Method
        if re.fullmatch(r"[A-Za-z_]\w*", recv_t or ""):
            return [c for c in self.idx.by_name.get(recv_t, [])]
        return []

    def _targets(self, recv_t: str | None, name: str, recv: Node | None) -> list[CsMethod]:
        if recv is not None and recv.type not in ("identifier", "this_expression", "this", "base_expression", "member_access_expression"):
            return []
        if recv is not None and recv.type == "member_access_expression" and not text(recv).startswith("this."):
            return []
        out = []
        for c in self._recv_classes(recv_t):
            m = self.idx.find_method(c, name)
            if m is not None and m not in out:
                out.append(m)
        return out

    # ---------- EF ----------
    def _ef_access(self, n: Node) -> None:
        prop = text(n.child_by_field_name("name"))
        if prop not in self.db_sets:
            return
        if n.parent is not None and n.parent.type == "member_access_expression" and n.parent.child_by_field_name("expression") == n:
            meth = text(n.parent.child_by_field_name("name"))
        else:
            meth = ""
        crud = "C" if meth in EF_CREATE else "D" if meth in EF_DELETE else "U" if meth in EF_UPDATE else "R"
        self.ef.setdefault(prop, set()).add(crud)
        self.ef_nodes.setdefault(prop, n)

    # ---------- 조건 ----------
    def condition(self, n: Node) -> str | None:
        p, child = n.parent, n
        stop = self.m.node
        while p is not None and p != stop:      # tree-sitter Node 는 매번 새 객체 → == 로 비교
            if p.type == "if_statement" and child != p.child_by_field_name("condition"):
                cond = text(p.child_by_field_name("condition"))
                mm = re.search(r"MessageBox\.Show\(\s*\$?@?\"((?:[^\"\\]|\\.)*)\"", cond)
                if mm:
                    return f'confirm("{mm.group(1)}")'
                if not re.search(r"\bUseMock\b|\bIsMock\b", cond):
                    neg = child == p.child_by_field_name("alternative")
                    return ("!(" + cond + ")" if neg else cond)[:200]
            child, p = p, p.parent
        return f'confirm("{self.confirm}")' if self.confirm else None

    # ---------- 데이터 접근 기록 ----------
    def _emit_data(self) -> None:
        cfg, m = self.ctx.config, self.model
        seen: set[tuple[str, bool]] = set()
        for sql, sp, node in self.sqls:
            sql_c = sql.strip()
            if (sql_c, sp) in seen or not sql_c:
                continue
            seen.add((sql_c, sp))
            is_sp = sp or bool(re.fullmatch(r"\[?[\w$#]+\]?(\.\[?[\w$#]+\]?)*", sql_c)) and not re.match(r"(?i)(select|insert|update|delete|merge|with)\b", sql_c)
            if is_sp:
                self._sp(sql_c, node, conf=0.95 if sp else 0.75)
            else:
                self._sql(sql_c, node)
        for prop, ops in self.ef.items():
            crud = {o for o in ops if o in "CRUD"}
            if "u?" in ops and self.savechanges:
                crud.add("U")
            if not crud:
                continue
            ent = self.db_sets[prop]
            table = self._ef_table(ent)
            node = self.ef_nodes.get(prop, self.m.node)
            key = self._sql_key()
            kind = "insert" if crud == {"C"} else "delete" if crud == {"D"} else "update" if "U" in crud else "select"
            ops_txt = "".join(c for c in "CRUD" if c in crud)
            m.sql[key] = {"key": key, "kind": kind, "namespace": self.m.cls.full, "file": cfg.rel(self.m.file),
                          "line": node.start_point[0] + 1}
            if cfg.include_snippets:
                m.sql[key]["text"] = f"-- EntityFramework: {prop} (DbSet<{ent}>) → {table} [{ops_txt}]\n{text(node.parent or node)[:300]}"
            m.add_edge(self.fkey, key, "executes", confidence=0.85, condition=self.condition(node), evidence=self.ev(node))
            tkey = tbl_key(cfg.schema, table)
            if tkey not in m.tables:
                m.add_table(tkey, cfg.schema, table, comment="DDL 미확인")
            m.add_edge(key, tkey, "accesses", confidence=0.8, crud=ops_txt, evidence=f"EF DbSet<{ent}>")

    def _ef_table(self, ent: str) -> str:
        mapping = self.ctx.config.get("dotnet.efTables", {}) or {}
        if ent in mapping:
            return mapping[ent].upper()
        for c in self.idx.by_name.get(ent, []):
            if c.attrs.get("Table"):
                return c.attrs["Table"][0].rsplit(".", 1)[-1].upper()
        for cand in (ent.upper(), f"TB_{ent.upper()}", re.sub(r"(?<!^)(?=[A-Z])", "_", ent).upper()):
            if cand in self.ctx.table_cols:
                return cand
        return ent.upper()

    def _sql_key(self) -> str:
        self.sql_seq += 1
        base = f"sql:{self.m.cls.name}.{self.m.name}"
        return base if self.sql_seq == 1 else f"{base}_{self.sql_seq}"

    def _sql(self, sql: str, node: Node) -> None:
        cfg, m, ctx = self.ctx.config, self.model, self.ctx
        key = self._sql_key()
        kind_m = re.match(r"(?i)\s*(select|insert|update|delete|merge|with)", sql)
        kind = (kind_m.group(1).lower() if kind_m else "other").replace("with", "select")
        line = node.start_point[0] + 1
        entry = {"key": key, "kind": kind, "namespace": self.m.cls.full, "file": cfg.rel(self.m.file), "line": line}
        if cfg.include_snippets:
            entry["text"] = re.sub(r"[ \t]+\n", "\n", sql).strip()
        m.sql[key] = entry
        m.add_edge(self.fkey, key, "executes", confidence=0.95, condition=self.condition(node), evidence=self.ev(node))
        info = analyze(sql.replace("?", "NULL"), ctx.table_cols, dialect_of(ctx))
        for proc in info.procedures:
            self._sp(proc, node, 0.9, from_key=key)
        for t, v in info.tables.items():
            tkey = tbl_key(cfg.schema, t)
            if tkey not in m.tables:
                m.add_table(tkey, cfg.schema, t, comment="DDL 미확인")
                m.fail("parse", key, f"DDL에 없는 테이블 참조: {t}")
            crud = "".join(c for c in "CRUD" if c in v["crud"])
            m.add_edge(key, tkey, "accesses", confidence=0.95 if info.parsed else 0.7, crud=crud or "R",
                       columns=sorted(v["columns"]) or None, evidence=self.ev(node))
        if not info.parsed:
            m.fail("parse", key, "SQL 구문 분석 실패 → 정규식으로 테이블만 추정")
        for a, b in info.joins:
            ctx.join_evidence.append((key, a, b))

    def _sp(self, name: str, node: Node, conf: float, from_key: str | None = None) -> None:
        cfg, m = self.ctx.config, self.model
        short = re.sub(r"[\[\]]", "", name).split(".")[-1]
        key = sp_key(cfg.schema, short)
        if key not in m.sql:
            m.sql[key] = {"key": key, "kind": "procedure", "file": "(DB 미수집)"}
            m.warn("sql", key, f"정의를 찾지 못한 저장 프로시저 호출: {name} ({self.ev(node)})")
        m.add_edge(from_key or self.fkey, key, "calls" if from_key else "executes", confidence=conf,
                   condition=None if from_key else self.condition(node), evidence=self.ev(node))


# ---------------------------------------------------------------- 파서
def register_methods(ctx: Context, idx: CsIndex) -> None:
    """모든 메서드를 코드 단위로 등록하고 본문을 스캔한다 (한 번만)."""
    if idx.scanned:
        return
    idx.scanned = True
    cfg, m = ctx.config, ctx.model
    for c in sorted(idx.classes.values(), key=lambda c: c.full):
        if c.kind == "interface":
            continue
        for name, overloads in c.methods.items():
            if name in SKIP_METHODS:
                continue
            meth = overloads[0]
            if name == ".ctor" and not any(x.type == "invocation_expression" for x in _iter(meth.node)):
                continue
            key = idx.key(meth)
            snippet = None
            if cfg.include_snippets:
                snippet = "\n".join(meth.src.splitlines()[meth.node.start_point[0]: meth.node.end_point[0] + 1])
            m.add_code_unit(key, "cs", cfg.rel(meth.file), meth.name if name != ".ctor" else c.name,
                            meth.line, meth.end, snippet)
            m.code_units[key]["name"] = f"{c.name}.{meth.name}" if name != ".ctor" else f"{c.name}()"
            MethodScanner(ctx, idx, meth).run()


def mvc_routes(ctx: Context, idx: CsIndex) -> None:
    """ASP.NET MVC / Web API 컨트롤러 액션 → Route."""
    if getattr(idx, "routes_done", False):
        return
    idx.routes_done = True  # type: ignore[attr-defined]
    for c in idx.classes.values():
        if not idx.is_controller(c):
            continue
        ctrl = re.sub(r"Controller$", "", c.name)
        prefix = (c.attrs.get("Route") or c.attrs.get("RoutePrefix") or [""])[0] if (c.attrs.get("Route") or c.attrs.get("RoutePrefix")) else ""
        for name, overloads in c.methods.items():
            for meth in overloads:
                if not meth.public or name == ".ctor" or "NonAction" in meth.attrs:
                    continue
                methods = [v for a, v in HTTP_ATTRS.items() if a in meth.attrs]
                tmpl = None
                for a in list(HTTP_ATTRS) + ["Route", "ActionName"]:
                    if meth.attrs.get(a):
                        tmpl = meth.attrs[a][0]
                        break
                if tmpl and "ActionName" in meth.attrs and not meth.attrs.get("Route"):
                    tmpl = f"{ctrl}/{tmpl}"
                if tmpl is not None:
                    path = tmpl if tmpl.startswith("/") or tmpl.startswith("~") else (f"{prefix}/{tmpl}" if prefix else tmpl)
                elif prefix:
                    path = prefix
                else:
                    path = f"{ctrl}/{name}"
                path = "/" + path.lstrip("~/").replace("[controller]", ctrl).replace("[action]", name).strip("/")
                body = text(meth.node)
                view = None
                vm = re.search(r"\b(?:Partial)?View\(\s*(?:\"([^\"]+)\")?", body)
                returns_view = bool(vm) and not re.search(r"\b(Json|Ok|Content|File|BadRequest|NotFound)\(", body)
                if returns_view:
                    vname = vm.group(1) if vm and vm.group(1) else name
                    view = vname.lstrip("~/").replace(".cshtml", "") if "/" in vname else f"{ctrl}/{vname}"
                    view = re.sub(r"^Views/", "", view)
                key = idx.key(meth)
                ctx.routes.append(Route(path, methods, key, view, "screen" if returns_view else "api"))
                if returns_view and name == "Index":
                    ctx.routes.append(Route(f"/{ctrl}" if not prefix else "/" + prefix.strip("/"), methods, key, None, "screen"))


def webmethod_routes(ctx: Context, idx: CsIndex, page_of_class: dict[str, str]) -> None:
    """WebForms 코드비하인드의 [WebMethod] 정적 메서드 → POST /Page.aspx/Method."""
    for full, page in page_of_class.items():
        c = idx.classes.get(full)
        if c is None:
            continue
        for name, overloads in c.methods.items():
            meth = overloads[0]
            if "WebMethod" in meth.attrs:
                ctx.routes.append(Route(f"{page}/{name}", ["POST"], idx.key(meth), None, "api"))


class DotnetParser:
    name = "dotnet-data"
    layer = "server"

    def detect(self, root: Path) -> float:
        return 1.0 if any(root.rglob("*.cs")) else 0.0

    def parse(self, ctx: Context) -> None:
        idx = get_index(ctx)
        if not idx.files:
            ctx.model.fail("parse", "source.dotnet", "C# 소스(.cs)를 찾지 못함")
            return
        mvc_routes(ctx, idx)
        register_methods(ctx, idx)


def resolve_url_navigation(ctx: Context, idx: CsIndex, fail: bool) -> None:
    """dotnet.py 가 모은 URL 이동(Response.Redirect·RedirectToAction·window.open) → navigates."""
    m = ctx.model
    rest = []
    for item in idx.pending_nav:
        fkey, kind, target, conf, ev, popup = item
        if kind != "url":
            rest.append(item)
            continue
        url = re.sub(r"^~", "", target.split("?", 1)[0].split("#", 1)[0])
        skey = ctx.screen_by_url.get(url) or ctx.screen_by_url.get(ctx.normalize_url(url)) or screen_for_url(ctx, url)
        if not skey:
            if fail:
                m.fail("parse", fkey, f"이동 대상 화면을 찾지 못함: {target} ({ev})")
            else:
                rest.append(item)
            continue
        m.add_edge(fkey, skey, "navigates", confidence=conf, evidence=ev)
        if popup:
            m.screens[skey]["kind"] = "popup"
    idx.pending_nav = rest


def finalize_dotnet(ctx: Context) -> None:
    """모든 화면 파서가 끝난 뒤: 남은 화면 이동 대기 목록을 해석하고, 못 한 것은 실패로 기록."""
    idx = getattr(ctx, "_cs_index", None)
    if idx is None:
        return
    resolve_url_navigation(ctx, idx, fail=True)
    for fkey, kind, target, conf, ev, popup in idx.pending_nav:
        ctx.model.fail("parse", fkey, f"화면 이동 대상 폼 '{target}' 의 화면(디자이너)을 찾지 못함 ({ev})")
    idx.pending_nav = []
