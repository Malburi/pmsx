"""Spring(Java) 파서 — URL 매핑(Route), 메서드 단위 코드, 호출 그래프(calls/executes)."""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

from tree_sitter import Node

from ..keys import fn_key, short_namespace, sql_key
from ..textio import read_text_auto
from . import Context, Route
from .ts import parse, string_value, text, walk

STEREOTYPES = {"Controller", "RestController", "Service", "Repository", "Component"}
# iBatis(EgovAbstractDAO·SqlMapClientTemplate) / MyBatis(SqlSessionTemplate·EgovAbstractMapper) 문자열 호출
SQL_REF_METHODS = {"list", "listWithPaging", "select", "selectByPk", "selectList", "selectOne", "selectMap",
                   "selectCursor", "insert", "update", "delete", "queryForList", "queryForObject", "queryForMap",
                   "queryForPaginatedList", "queryWithRowHandler"}
MAPPING_ANN = {"RequestMapping": None, "GetMapping": "GET", "PostMapping": "POST", "PutMapping": "PUT",
               "DeleteMapping": "DELETE", "PatchMapping": "PATCH"}


@dataclass
class JMethod:
    name: str
    node: Node
    annotations: dict[str, Node]
    return_type: str
    line_start: int
    line_end: int


@dataclass
class JClass:
    name: str
    fqn: str
    kind: str                      # class / interface
    file: Path
    src: str
    annotations: dict[str, Node]
    implements: list[str]
    pkg: str = ""
    imports: dict[str, str] = field(default_factory=dict)  # 단순명 -> FQN
    fields: dict[str, str] = field(default_factory=dict)   # 필드명 -> 타입 단순명
    methods: dict[str, JMethod] = field(default_factory=dict)


def _annotations(mods: Node | None) -> dict[str, Node]:
    out: dict[str, Node] = {}
    if mods is None:
        return out
    for c in mods.children:
        if c.type in ("annotation", "marker_annotation"):
            out[text(c.child_by_field_name("name")).split(".")[-1]] = c
    return out


def _ann_values(ann: Node, keys=("value", "path")) -> list[str]:
    """@X("a") / @X(value="a") / @X({"a","b"}) 의 문자열 값."""
    args = ann.child_by_field_name("arguments")
    if args is None:
        return []
    vals: list[str] = []
    for c in args.named_children:
        target = None
        if c.type == "element_value_pair":
            if text(c.child_by_field_name("key")) in keys:
                target = c.child_by_field_name("value")
        elif c.type in ("string_literal", "element_value_array_initializer"):
            target = c
        if target is None:
            continue
        nodes = target.named_children if target.type == "element_value_array_initializer" else [target]
        vals.extend(v for v in (string_value(n) for n in nodes) if v is not None)
    return vals


def _ann_methods(ann: Node) -> list[str]:
    args = ann.child_by_field_name("arguments")
    if args is None:
        return []
    for c in args.named_children:
        if c.type == "element_value_pair" and text(c.child_by_field_name("key")) == "method":
            return re.findall(r"RequestMethod\.(\w+)", text(c.child_by_field_name("value")))
    return []


def _simple_type(t: str) -> str:
    return re.sub(r"<.*>", "", t).split(".")[-1].strip()


class SpringParser:
    name = "spring"
    layer = "server"

    def detect(self, root: Path) -> float:
        return 1.0 if any("org.springframework" in p.read_text(encoding="utf-8", errors="ignore") for p in root.rglob("*.java")) else 0.0

    def parse(self, ctx: Context) -> None:
        cfg, m = ctx.config, ctx.model
        root = cfg.source_dir("java")
        if not root or not root.is_dir():
            m.fail("parse", "source.java", "Java 소스 경로 없음")
            return
        classes: dict[str, JClass] = {}          # FQN -> 클래스 (같은 단순명이 여러 패키지에 있어도 구분)
        for f in sorted(root.rglob("*.java")):
            for jc in self._read(f):
                classes[jc.fqn] = jc
        self.classes = classes
        by_simple: dict[str, list[JClass]] = {}
        for c in classes.values():
            by_simple.setdefault(c.name, []).append(c)
        jsp_root = cfg.source_dir("jsp")
        mapper_fqns = set(ctx.mapper_namespaces)

        def resolve(c: JClass, simple: str) -> str | None:
            """필드 타입 단순명 → FQN (import → 같은 패키지 → 유일한 단순명 순)."""
            if simple in c.imports:
                return c.imports[simple]
            same = f"{c.pkg}.{simple}" if c.pkg else simple
            if same in classes or same in mapper_fqns:
                return same
            cands = [x.fqn for x in by_simple.get(simple, [])] or [f for f in mapper_fqns if short_namespace(f) == simple]
            return cands[0] if len(cands) == 1 else None

        def impl_of(iface: JClass) -> JClass | None:
            for c in classes.values():
                if c.kind == "class" and iface.name in c.implements and resolve(c, iface.name) == iface.fqn:
                    return c
            return None

        def mkey(c: JClass, meth: str) -> str:
            return fn_key("java", cfg.rel(c.file), f"{c.name}.{meth}")

        for c in classes.values():
            if c.kind != "class" or not (STEREOTYPES & set(c.annotations)):
                continue
            prefix = (_ann_values(c.annotations["RequestMapping"]) or [""])[0] if "RequestMapping" in c.annotations else ""
            is_rest = "RestController" in c.annotations
            for meth in c.methods.values():
                key = mkey(c, meth.name)
                lines = c.src.splitlines()[meth.line_start - 1: meth.line_end]
                m.add_code_unit(key, "java", cfg.rel(c.file), f"{c.name}.{meth.name}", meth.line_start, meth.line_end,
                                "\n".join(lines) if cfg.include_snippets else None)
                # URL 매핑
                for ann_name, fixed in MAPPING_ANN.items():
                    ann = meth.annotations.get(ann_name)
                    if ann is None:
                        continue
                    paths = _ann_values(ann) or [""]
                    methods = [fixed] if fixed else _ann_methods(ann)
                    view = self._view_name(meth)
                    is_screen = (not is_rest and "ResponseBody" not in meth.annotations and view is not None
                                 and jsp_root is not None and (jsp_root / f"{view}.jsp").is_file())
                    for p in paths:
                        path = ctx.normalize_url(prefix.rstrip("/") + "/" + p.lstrip("/") if p else prefix)
                        ctx.routes.append(Route(path, methods, key, view if is_screen else None,
                                                "screen" if is_screen else "api"))
                # 호출 그래프
                self._calls(ctx, c, meth, key, classes, impl_of, mkey, resolve)

    # ---------- 읽기 ----------
    def _read(self, f: Path) -> list[JClass]:
        src = read_text_auto(f)
        tree = parse("java", src)
        pkg = ""
        imports: dict[str, str] = {}
        out: list[JClass] = []
        for n in tree.root_node.children:
            if n.type == "package_declaration":
                pkg = text(n).replace("package", "").replace(";", "").strip()
            if n.type == "import_declaration":
                imp = re.sub(r"^import\s+(static\s+)?|;$", "", text(n).strip()).strip()
                if not imp.endswith("*"):
                    imports[imp.rsplit(".", 1)[-1]] = imp
            if n.type not in ("class_declaration", "interface_declaration"):
                continue
            name = text(n.child_by_field_name("name"))
            mods = next((c for c in n.children if c.type == "modifiers"), None)
            impls: list[str] = []
            for c in n.children:
                if c.type in ("super_interfaces", "extends_interfaces"):
                    impls += [_simple_type(text(t)) for t in walk(c) if t.type == "type_identifier"]
            jc = JClass(name, f"{pkg}.{name}" if pkg else name, "class" if n.type == "class_declaration" else "interface",
                        f, src, _annotations(mods), impls, pkg, imports)
            body = n.child_by_field_name("body")
            for member in body.named_children if body else []:
                if member.type == "field_declaration":
                    ftype = _simple_type(text(member.child_by_field_name("type")))
                    for d in member.named_children:
                        if d.type == "variable_declarator":
                            jc.fields[text(d.child_by_field_name("name"))] = ftype
                elif member.type == "method_declaration":
                    mmods = next((c for c in member.children if c.type == "modifiers"), None)
                    mname = text(member.child_by_field_name("name"))
                    jc.methods[mname] = JMethod(mname, member, _annotations(mmods),
                                                text(member.child_by_field_name("type")),
                                                member.start_point[0] + 1, member.end_point[0] + 1)
            out.append(jc)
        return out

    @staticmethod
    def _view_name(meth: JMethod) -> str | None:
        """return "hr/emp/HR0101"; 형태면 뷰 이름. ModelAndView("jsonView") 는 API."""
        if _simple_type(meth.return_type) != "String":
            return None
        for n in walk(meth.node):
            if n.type == "return_statement":
                v = string_value(n.named_children[0]) if n.named_children else None
                if v is not None:
                    return v
        return None

    # ---------- 호출 ----------
    def _calls(self, ctx, c: JClass, meth: JMethod, key: str, classes, impl_of, mkey, resolve):
        cfg, m = ctx.config, ctx.model
        body = meth.node.child_by_field_name("body")
        if body is None:
            return
        for n in walk(body):
            if n.type != "method_invocation":
                continue
            obj = n.child_by_field_name("object")
            name = text(n.child_by_field_name("name"))
            ev = f"{cfg.rel(c.file)}:{n.start_point[0] + 1}"
            cond = self._condition(n, body)
            # iBatis/MyBatis 문자열 호출: list("edu.selectEduList", vo), getSqlMapClientTemplate().queryForList(...)
            if name in SQL_REF_METHODS:
                args = n.child_by_field_name("arguments")
                ref = string_value(args.named_children[0]) if args is not None and args.named_children else None
                if ref and "." in ref:
                    skey = ctx.sql_by_ref.get(ref)
                    if skey:
                        m.add_edge(key, skey, "executes", confidence=0.97, condition=cond, evidence=ev)
                    else:
                        m.fail("parse", key, f"SQL 문장 ID를 찾지 못함: {ref} ({ev})")
                    continue
            if obj is None:
                if name in c.methods and name != meth.name:
                    m.add_edge(key, mkey(c, name), "calls", confidence=0.95, condition=cond, evidence=ev)
                continue
            oname = text(obj).replace("this.", "")
            ftype = c.fields.get(oname)
            if not ftype:
                continue
            fqn = resolve(c, ftype)
            target = classes.get(fqn) if fqn else None
            if (fqn and fqn in ctx.mapper_namespaces) or (target and target.kind == "interface" and "Mapper" in target.annotations):
                ns_name = ctx.mapper_namespaces.get(fqn) or ftype
                skey = sql_key(ns_name, name)
                if skey in m.sql:
                    m.add_edge(key, skey, "executes", confidence=0.98, condition=cond, evidence=ev)
                else:
                    m.fail("parse", key, f"Mapper 문장 없음: {ns_name}.{name}")
                continue
            if target is None:
                continue
            impl = impl_of(target) if target.kind == "interface" else target
            if impl and name in impl.methods:
                m.add_edge(key, mkey(impl, name), "calls", confidence=0.97 if impl is target else 0.95,
                           condition=cond, evidence=ev)

    @staticmethod
    def _condition(n: Node, stop: Node) -> str | None:
        """호출을 감싸는 가장 가까운 if 조건 (분기 표시용)."""
        cur = n.parent
        while cur is not None and cur != stop:
            if cur.type == "if_statement":
                cond = text(cur.child_by_field_name("condition")).strip()
                if cond.startswith("(") and cond.endswith(")"):
                    cond = cond[1:-1]
                return re.sub(r"\s+", " ", cond).strip()[:120]
            cur = cur.parent
        return None
