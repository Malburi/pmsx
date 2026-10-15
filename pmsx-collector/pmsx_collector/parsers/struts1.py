"""Struts 1 파서 (+ 자체 프레임워크 디스패치 규칙) — URL 매핑(Route), 서비스 메서드 코드, 호출 그래프.

Struts 1 기본
  struts-config.xml 의 <action path type parameter> + <forward name path>
  · 일반 Action           : `/x/Foo.do` → Action.execute
  · DispatchAction 계열   : parameter="method" 이면 `/x/Foo.do?method=list` → Action.list(mapping, form, req, res)
  · forward(JSP)          : 그 URL 이 보여 주는 화면 (Route kind=screen, ctx.view_actions)

자체 프레임워크 규칙 (설정 struts.workerActions — 예: coperframe)
  <action type="coperframe.common.struts.WorkerAction" parameter="method" command="SmsService">
    → 빈 설정(appconf)의 <bean id="SmsService" class="…SmsService"/>
    → `?method=edit` 은 서비스의 `doEdit(...)` (methodPrefix: do, 첫 글자 대문자)
    → forward name 이 method 값과 같으면 그 JSP 를 화면으로

서비스 본문
  · 쿼리 호출   : dataAccesser.query/update/call/batch("ID", …) — ID 는 문자열 또는 지역 변수(String queryId1 = "X_S01")
                  `"ID,1"` 처럼 뒤에 붙는 옵션은 제거. 설정 struts.queryCalls 로 메서드명·수신 객체명을 바꿀 수 있다.
  · 서비스 호출 : subCall("CodeService", "codeList", p) / service(...) / callService(...) → 빈 → do 접두 메서드
  · 그 밖의 호출: 같은 클래스 메서드, 타입이 확인되는 필드의 메서드 (SpringParser 와 같은 규칙)

설정 예 (collector.yaml)
  struts:
    configs: ["web/WEB-INF/config/actconf/struts-*.xml"]     # 소스 루트 기준 glob
    beanConfigs: ["web/WEB-INF/config/appconf/*.xml"]
    extension: .do
    workerActions:
      - type: coperframe.common.struts.WorkerAction
        serviceAttr: command
        methodPrefix: do
    serviceCalls: [{name: subCall, beanArg: 0, methodArg: 1}, {name: service, beanArg: 0, methodArg: 1},
                   {name: callService, beanArg: 0, methodArg: 1}]
    queryCalls: {methods: [query, update, call, batch], receivers: [dataAccesser, da]}
"""
from __future__ import annotations

import glob
import re
import xml.etree.ElementTree as ET
from pathlib import Path

from tree_sitter import Node

from ..keys import fn_key, short_namespace
from ..textio import read_xml_text
from . import Context, Route
from .spring import JClass, SpringParser
from .ts import string_value, text, walk

DEFAULT_SERVICE_CALLS = [{"name": "subCall", "beanArg": 0, "methodArg": 1},
                         {"name": "service", "beanArg": 0, "methodArg": 1},
                         {"name": "callService", "beanArg": 0, "methodArg": 1}]
DEFAULT_QUERY_CALLS = {"methods": ["query", "update", "call", "batch"], "receivers": []}
ACTION_SIG = re.compile(r"\bActionMapping\b")


def _lower_first(s: str) -> str:
    return s[:1].lower() + s[1:]


def _upper_first(s: str) -> str:
    return s[:1].upper() + s[1:]


def _xml(path: Path) -> ET.Element | None:
    raw = read_xml_text(path)
    raw = re.sub(r"<!DOCTYPE[^>]*>", "", raw, count=1)
    try:
        return ET.fromstring(raw)
    except ET.ParseError:
        return None


def _globs(cfg, pats) -> list[Path]:
    out: list[Path] = []
    for pat in pats or []:
        out += [Path(p) for p in sorted(glob.glob(str(cfg.source_root / pat), recursive=True))]
    return out


def local_strings(body: Node) -> dict[str, set[str]]:
    """메서드 본문의 지역 문자열 변수 → 대입된 리터럴(접두) 집합.

    String q = "A_S01";  q = "A_S02";  StringBuffer sb = new StringBuffer("A_S03");  q = q + ",1" (무시)
    """
    out: dict[str, set[str]] = {}

    def lit(v: Node | None) -> str | None:
        if v is None:
            return None
        s = string_value(v)
        if s is not None:
            return s
        if v.type == "binary_expression":
            left = v.child_by_field_name("left")
            while left is not None and left.type == "binary_expression":
                left = left.child_by_field_name("left")
            return string_value(left) if left is not None else None
        if v.type == "object_creation_expression":
            args = v.child_by_field_name("arguments")
            if args is not None and args.named_children:
                return lit(args.named_children[0])
        if v.type == "parenthesized_expression" and v.named_children:
            return lit(v.named_children[0])
        if v.type == "ternary_expression":
            a, b = v.child_by_field_name("consequence"), v.child_by_field_name("alternative")
            return lit(a) or lit(b)
        return None

    for n in walk(body):
        if n.type == "variable_declarator":
            name = text(n.child_by_field_name("name"))
            s = lit(n.child_by_field_name("value"))
            if s is not None:
                out.setdefault(name, set()).add(s)
        elif n.type == "assignment_expression":
            left = n.child_by_field_name("left")
            if left is not None and left.type == "identifier":
                s = lit(n.child_by_field_name("right"))
                if s is not None and s:
                    out.setdefault(text(left), set()).add(s)
    return out


def clean_query_id(s: str) -> str:
    """'A_S01,1' → 'A_S01' (자체 프레임워크의 동적 블록 옵션 접미사 제거)."""
    return re.split(r"[,\s|]", s.strip(), maxsplit=1)[0]


class Struts1Parser:
    name = "struts1"
    layer = "server"

    def detect(self, root: Path) -> float:
        return 1.0 if any(root.rglob("struts*.xml")) else 0.0

    def parse(self, ctx: Context) -> None:
        cfg, m = ctx.config, ctx.model
        st = cfg.get("struts") or {}
        conf_files: list[tuple[Path, str]] = []          # (struts-config, 모듈 접두)
        seen: set[Path] = set()
        for f, prefix in self._from_web_xml(cfg, st):     # web.xml 의 ActionServlet config 목록 (모듈 포함)
            if f.resolve() not in seen:
                seen.add(f.resolve())
                conf_files.append((f, prefix))
        for f in _globs(cfg, st.get("configs") or ([] if conf_files else ["**/WEB-INF/struts-config*.xml"])):
            if f.resolve() not in seen:
                seen.add(f.resolve())
                conf_files.append((f, ""))
        if not conf_files:
            m.fail("parse", "struts.configs", "struts-config XML 을 찾지 못함")
            return
        root = cfg.source_dir("java")
        if not root or not root.is_dir():
            m.fail("parse", "source.java", "Java 소스 경로 없음")
            return
        self.ctx, self.cfg, self.m = ctx, cfg, m
        self.ext = st.get("extension", ".do")
        self.workers = st.get("workerActions") or []
        self.svc_calls = {c["name"]: c for c in (st.get("serviceCalls") or DEFAULT_SERVICE_CALLS)}
        qc = st.get("queryCalls") or DEFAULT_QUERY_CALLS
        self.q_methods = set(qc.get("methods") or DEFAULT_QUERY_CALLS["methods"])
        self.q_receivers = set(qc.get("receivers") or [])

        # 1) 빈 설정 (bean id → class)
        self.beans: dict[str, str] = {}
        for f in _globs(cfg, st.get("beanConfigs") or []):
            doc = _xml(f)
            if doc is None:
                m.fail("parse", cfg.rel(f), "빈 설정 XML 파싱 실패")
                continue
            for b in doc.iter():
                if b.tag.split("}")[-1] == "bean" and b.get("id") and b.get("class"):
                    self.beans[b.get("id")] = b.get("class")
                if b.tag.split("}")[-1] == "bean" and b.get("name") and b.get("class"):
                    self.beans.setdefault(b.get("name"), b.get("class"))

        # 2) Java 클래스 색인 (SpringParser 의 읽기 재사용)
        sp = SpringParser()
        classes: dict[str, JClass] = {}
        for f in sorted(root.rglob("*.java")):
            try:
                for jc in sp._read(f):
                    classes[jc.fqn] = jc
            except Exception as e:  # noqa: BLE001 - 깨진 소스 1개 때문에 전체를 멈추지 않는다
                m.fail("parse", cfg.rel(f), f"Java 파싱 실패: {str(e)[:120]}")
        self.classes = classes
        self.by_simple: dict[str, list[JClass]] = {}
        for c in classes.values():
            self.by_simple.setdefault(c.name, []).append(c)
        self.emitted: set[str] = set()
        self.jsp_root = cfg.source_dir("jsp")

        # 3) action 매핑
        n_actions = 0
        for f, prefix in conf_files:
            doc = _xml(f)
            if doc is None:
                m.fail("parse", cfg.rel(f), "struts-config 파싱 실패")
                continue
            for a in doc.iter("action"):
                n_actions += 1
                self._action(a, prefix, cfg.rel(f))
        # 4) 액션에서 참조되지 않은 서비스 클래스도 코드 단위로 (화면 외 호출·배치 분석용)
        for c in list(classes.values()):
            if c.kind == "class" and c.fqn in self.beans.values():
                self._emit(c)
        m.parsers_used.append(f"struts1({n_actions} actions)")

    @staticmethod
    def _from_web_xml(cfg, st) -> list[tuple[Path, str]]:
        """web.xml 의 ActionServlet init-param: config(기본 모듈) / config/xxx(모듈 /xxx) → 파일 목록."""
        web_xml = cfg.resolve(st["webXml"]) if st.get("webXml") else None
        if web_xml is None:
            webroot = cfg.source_dir("webapp")
            cand = webroot / "WEB-INF" / "web.xml" if webroot else None
            web_xml = cand if cand is not None and cand.is_file() else None
        if web_xml is None or not web_xml.is_file():
            return []
        doc = _xml(web_xml)
        if doc is None:
            return []
        webroot = web_xml.parent.parent
        out: list[tuple[Path, str]] = []
        for sv in doc.iter():
            if sv.tag.split("}")[-1] != "servlet":
                continue
            cls = "".join(x.text or "" for x in sv.iter() if x.tag.split("}")[-1] == "servlet-class").strip()
            if "ActionServlet" not in cls:
                continue
            for ip in (x for x in sv.iter() if x.tag.split("}")[-1] == "init-param"):
                name = "".join(x.text or "" for x in ip.iter() if x.tag.split("}")[-1] == "param-name").strip()
                val = "".join(x.text or "" for x in ip.iter() if x.tag.split("}")[-1] == "param-value")
                if name != "config" and not name.startswith("config/"):
                    continue
                prefix = "" if name == "config" else "/" + name.split("/", 1)[1]
                for part in val.split(","):
                    part = part.strip()
                    if part:
                        f = webroot / part.lstrip("/")
                        if f.is_file():
                            out.append((f, prefix))
        return out

    # ---------------------------------------------------------------- action
    def _view(self, fwd_path: str) -> str | None:
        """'/WEB-INF/jsp/back/x/list.jsp' → 'back/x/list' (source.jsp 기준). 다른 액션(.do)으로 가는 forward 는 None."""
        p = fwd_path.split("?", 1)[0]
        if not p.endswith(".jsp"):
            return None
        if self.jsp_root is not None:
            webroot = self.cfg.source_dir("webapp")
            cand = (webroot / p.lstrip("/")) if webroot else None
            if cand is not None and cand.is_file():
                try:
                    return cand.resolve().relative_to(self.jsp_root.resolve()).with_suffix("").as_posix()
                except ValueError:
                    return None
        return None

    def _action(self, a: ET.Element, prefix: str, where: str):
        cfg, m, ctx = self.cfg, self.m, self.ctx
        path = a.get("path") or ""
        if not path:
            return
        base = ctx.normalize_url(prefix + path + ("" if path.endswith(self.ext) else self.ext))
        param = a.get("parameter")
        forwards = {fw.get("name"): fw.get("path") for fw in a.findall("forward") if fw.get("name") and fw.get("path")}
        fw_ci = {k.lower(): v for k, v in forwards.items()}
        typ = a.get("type") or ""
        rule = next((w for w in self.workers if w.get("type") == typ), None)
        handlers: list[tuple[str, str | None]] = []           # (핸들러 키, method 값)
        if rule is not None:
            bean = a.get(rule.get("serviceAttr", "command"))
            cls_name = self.beans.get(bean or "")
            jc = self.classes.get(cls_name or "")
            if jc is None:
                m.fail("parse", base, f"서비스 빈 '{bean}' 의 클래스 소스를 찾지 못함 ({cls_name or '빈 설정 없음'}) [{where}]")
            else:
                self._emit(jc)
                pre = rule.get("methodPrefix", "do")
                for name in jc.methods:
                    if name.startswith(pre) and len(name) > len(pre) and name[len(pre)].isupper():
                        handlers.append((self._mkey(jc, name), _lower_first(name[len(pre):])))
        else:
            jc = self.classes.get(typ)
            if jc is None:
                if typ and not typ.startswith("org.apache.struts"):
                    m.fail("parse", base, f"Action 클래스 소스를 찾지 못함: {typ} [{where}]")
            else:
                self._emit(jc)
                if param:
                    for name, meth in jc.methods.items():
                        sig = text(meth.node.child_by_field_name("parameters"))
                        if name != "execute" and ACTION_SIG.search(sig):
                            handlers.append((self._mkey(jc, name), name))
                if not handlers and "execute" in jc.methods:
                    handlers.append((self._mkey(jc, "execute"), None))
        for hkey, mval in handlers:
            url = f"{base}?{param}={mval}" if (param and mval) else base
            url = ctx.normalize_url(url)
            ctx.routes.append(Route(url, [], hkey, None, "api"))
            view = None
            if mval and (mval in forwards or mval.lower() in fw_ci):
                view = self._view(forwards.get(mval) or fw_ci[mval.lower()])
            elif len({self._view(v) for v in forwards.values()} - {None}) == 1:
                view = next(v for v in (self._view(x) for x in forwards.values()) if v)   # 화면 forward 가 하나뿐인 액션
            if view:
                ctx.routes.append(Route(url, [], hkey, view, "screen"))
        # 이 액션이 보여 주는 화면들 (상대 URL '?method=save' 해석 기준)
        for fpath in forwards.values():
            v = self._view(fpath)
            if v:
                lst = ctx.view_actions.setdefault(v, [])
                if base not in lst:
                    lst.append(base)

    # ---------------------------------------------------------------- 클래스·메서드
    def _mkey(self, c: JClass, meth: str) -> str:
        return fn_key("java", self.cfg.rel(c.file), f"{c.name}.{meth}")

    def _resolve(self, c: JClass, simple: str) -> str | None:
        if simple in c.imports:
            return c.imports[simple]
        same = f"{c.pkg}.{simple}" if c.pkg else simple
        if same in self.classes:
            return same
        cands = [x.fqn for x in self.by_simple.get(simple, [])]
        return cands[0] if len(cands) == 1 else None

    def _emit(self, c: JClass):
        if c.fqn in self.emitted:
            return
        self.emitted.add(c.fqn)
        cfg, m = self.cfg, self.m
        lines = c.src.splitlines()
        for meth in c.methods.values():
            key = self._mkey(c, meth.name)
            m.add_code_unit(key, "java", cfg.rel(c.file), f"{c.name}.{meth.name}", meth.line_start, meth.line_end,
                            "\n".join(lines[meth.line_start - 1: meth.line_end]) if cfg.include_snippets else None)
        for meth in c.methods.values():
            self._calls(c, meth)

    def _calls(self, c: JClass, meth):
        cfg, m, ctx = self.cfg, self.m, self.ctx
        body = meth.node.child_by_field_name("body")
        if body is None:
            return
        key = self._mkey(c, meth.name)
        locals_ = None
        for n in walk(body):
            if n.type != "method_invocation":
                continue
            name = text(n.child_by_field_name("name"))
            obj = n.child_by_field_name("object")
            args_node = n.child_by_field_name("arguments")
            args = args_node.named_children if args_node is not None else []
            ev = f"{cfg.rel(c.file)}:{n.start_point[0] + 1}"
            cond = SpringParser._condition(n, body)
            oname = text(obj).replace("this.", "") if obj is not None else ""
            # 쿼리 호출
            if name in self.q_methods and args and obj is not None and (not self.q_receivers or oname in self.q_receivers):
                a0 = args[0]
                ids: set[str] = set()
                lit = string_value(a0)
                conf = 0.97
                if lit is not None:
                    ids.add(lit)
                else:
                    target = a0
                    if target.type == "method_invocation" and text(target.child_by_field_name("name")) == "toString":
                        target = target.child_by_field_name("object")
                    if target is not None and target.type == "identifier":
                        if locals_ is None:
                            locals_ = local_strings(body)
                        ids = set(locals_.get(text(target), set()))
                        conf = 0.9 if len(ids) == 1 else 0.8
                if not ids:
                    if self.q_receivers and oname in self.q_receivers:
                        m.warn("parse", key, f"쿼리 ID 를 정적으로 확정하지 못함: {text(a0)[:60]} ({ev})")
                    continue
                for raw in ids:
                    qid = clean_query_id(raw)
                    skey = ctx.sql_by_ref.get(qid)
                    if skey:
                        m.add_edge(key, skey, "executes", confidence=conf, condition=cond, evidence=ev)
                    elif (self.q_receivers and re.fullmatch(r"[A-Za-z][\w.]*", qid)) or re.fullmatch(r"[A-Z][A-Z0-9_]+", qid):
                        m.fail("parse", key, f"쿼리 ID 정의 없음: {qid} ({ev})")
                continue
            # 서비스 호출 subCall("Bean", "method", p)
            sc = self.svc_calls.get(name)
            if sc is not None and len(args) > max(sc.get("beanArg", 0), sc.get("methodArg", 1)):
                bean = string_value(args[sc.get("beanArg", 0)])
                mval = string_value(args[sc.get("methodArg", 1)])
                if bean and mval:
                    jc = self.classes.get(self.beans.get(bean, ""))
                    if jc is None:
                        m.fail("parse", key, f"서비스 빈 '{bean}' 클래스를 찾지 못함 ({ev})")
                        continue
                    self._emit(jc)
                    pre = (self.workers[0].get("methodPrefix", "do") if self.workers else "do")
                    target = pre + _upper_first(mval) if (pre + _upper_first(mval)) in jc.methods else mval
                    if target in jc.methods:
                        m.add_edge(key, self._mkey(jc, target), "calls", confidence=0.95, condition=cond, evidence=ev)
                    else:
                        m.fail("parse", key, f"{bean}.{mval} 메서드를 찾지 못함 ({ev})")
                    continue
            # 같은 클래스 메서드
            if obj is None or oname == "this" or text(obj) == "this":
                if name in c.methods and name != meth.name:
                    m.add_edge(key, self._mkey(c, name), "calls", confidence=0.95, condition=cond, evidence=ev)
                continue
            # 타입이 확인되는 필드
            ftype = c.fields.get(oname)
            if not ftype:
                # new X().method() / X.staticMethod()
                if obj.type == "identifier" and oname[:1].isupper():
                    ftype = oname
                elif obj.type == "object_creation_expression":
                    ftype = text(obj.child_by_field_name("type"))
                else:
                    continue
            fqn = self._resolve(c, re.sub(r"<.*>", "", ftype))
            target = self.classes.get(fqn) if fqn else None
            if target is None or name not in target.methods:
                continue
            self._emit(target)
            m.add_edge(key, self._mkey(target, name), "calls", confidence=0.9, condition=cond, evidence=ev)


def short(fqn: str) -> str:
    return short_namespace(fqn)
