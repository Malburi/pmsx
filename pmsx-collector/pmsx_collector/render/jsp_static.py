"""JSP → 정적 HTML 변환 (서버 없이 화면 그림을 얻는 '정적 렌더링 캡처'의 1단계).

서버(서블릿 컨테이너)가 없으므로 JSP 를 흉내 내 HTML 로 바꾼다.
  · <%@ include file %> / <jsp:include page> → 해당 파일 내용을 그 자리에 인라인 (재귀, 상대·컨텍스트 경로)
  · <%@ page/taglib %>, <%! %>, 스크립틀릿 <% %> 제거 — 조건 분기는 양쪽 모두 보이고 반복문 본문은 1회
  · 표현식 <%= %>·EL ${} → 자리표시값 (경로 상수는 정적 분석과 같은 source.jspVars 치환, 그 외는 빈 값)
  · JSTL(c:if·c:choose·c:forEach·c:out·c:url…), fmt:*, Struts bean:message(MessageResources 실제 문구)·
    html:*(대응 HTML 요소)·logic:*, 그 밖의 커스텀 태그는 본문만 남김
  · 정적 분석이 화면 요소로 잡은 태그마다 data-pmsx="N" 표식을 넣는다 → 렌더 후 표식으로 요소 키·좌표를 정확히 매칭

요소 번호(N)는 JspJqueryParser 와 같은 규칙(화면 파일 요소 → include 파일 요소 순, id → name → 태그+순번)으로
다시 계산해 정적 분석의 요소 키와 1:1 로 맞춘다.
"""
from __future__ import annotations

import html as htmllib
import re
from dataclasses import dataclass, field
from pathlib import Path

from ..keys import el_key
from .scriptlet_branches import resolve_branches
from ..parsers.jsp_jquery import JspJqueryParser, _Html
from ..textio import read_text_auto

DEFAULT_PREFIXES = {"c", "fmt", "fn", "bean", "html", "logic", "tiles", "jsp", "nested", "spring", "form", "sec"}
REPEAT_TAGS = {("c", "forEach"), ("c", "forTokens"), ("logic", "iterate"), ("nested", "iterate")}
DROP_TAGS = {("html", "messages"), ("html", "errors"), ("html", "javascript"), ("html", "base"), ("c", "set"),
             ("c", "remove"), ("c", "import"), ("c", "param"), ("c", "redirect"), ("bean", "define"),
             ("bean", "write"), ("bean", "size"), ("bean", "include"), ("bean", "parameter"), ("bean", "cookie"),
             ("bean", "header"), ("bean", "page"), ("bean", "resource"), ("bean", "struts"), ("logic", "redirect"),
             ("logic", "forward"), ("tiles", "useAttribute"), ("tiles", "importAttribute"), ("fmt", "setLocale"),
             ("fmt", "setBundle"), ("fmt", "setTimeZone"), ("fmt", "requestEncoding"), ("fmt", "param"),
             ("jsp", "useBean"), ("jsp", "setProperty"), ("jsp", "getProperty"), ("jsp", "forward"), ("jsp", "param"),
             ("jsp", "params"), ("jsp", "plugin"), ("jsp", "fallback")}
INCLUDE_RE = re.compile(
    r"<%@\s*include\s+file\s*=\s*[\"']([^\"']+)[\"']\s*%>"
    r"|<jsp:include\s+page\s*=\s*[\"']([^\"'<%]+)[\"'][^>]*?(?:/>|>.*?</jsp:include>)", re.S | re.I)
_ATTR_RE = re.compile(r"([\w:.-]+)\s*=\s*(?:\"([^\"]*)\"|'([^']*)')")


class _PosHtml(_Html):
    """정적 분석과 같은 요소 추출 + 각 요소 시작 태그의 위치(줄·열)."""

    def __init__(self):
        super().__init__()
        self.pos: list[tuple[int, int]] = []

    def handle_starttag(self, tag, attrs):
        n = len(self.elements)
        super().handle_starttag(tag, attrs)
        if len(self.elements) > n:
            self.pos.append(self.getpos())


def load_messages(paths: list[Path]) -> dict[str, str]:
    """Java properties (MessageResources*.properties) — \\uXXXX·줄 이음·EUC-KR/UTF-8."""
    out: dict[str, str] = {}
    for p in paths:
        try:
            text = read_text_auto(p)
        except OSError:
            continue
        logical, buf = [], ""
        for line in text.splitlines():
            s = line.lstrip()
            if not buf and (not s or s[0] in "#!"):
                continue
            if s.endswith("\\") and not s.endswith("\\\\"):
                buf += s[:-1]
                continue
            logical.append(buf + s)
            buf = ""
        for ln in logical:
            mm = re.match(r"((?:\\.|[^=:\s])+)\s*[=:\s]\s*(.*)$", ln)
            if not mm:
                continue
            k = mm.group(1).replace("\\", "")
            v = re.sub(r"\\u([0-9a-fA-F]{4})", lambda x: chr(int(x.group(1), 16)), mm.group(2))
            v = v.replace("\\n", " ").replace("\\t", " ").replace("\\", "")
            out.setdefault(k, v)
    return out


@dataclass
class RenderedScreen:
    html: str
    keymap: dict[int, str]                       # data-pmsx 번호 → 요소 키
    deps: list[Path] = field(default_factory=list)   # 화면 JSP + include 파일 (증분 렌더 판단용)
    frameset: bool = False


class JspStaticRenderer:
    def __init__(self, ctx):
        self.ctx = ctx
        cfg = ctx.config
        p = JspJqueryParser()
        p.ctx = ctx
        p.webapp = cfg.source_dir("webapp")
        p.jsp_root = cfg.source_dir("jsp")
        self.p = p
        self.context_root = (cfg.get("render.contextRoot") or cfg.context_path or "").rstrip("/")
        self.repeat = max(1, min(int(cfg.get("render.repeat", 2)), 3))
        self.branch_policy = str(cfg.get("render.branchPolicy", "rich")).lower()   # rich(내용 많은 분기) | last(else) | first(if)
        self.placeholders: dict[str, str] = {str(k): str(v) for k, v in (cfg.get("render.placeholders") or {}).items()}
        self.messages = load_messages(self._message_files())
        self._parsed: dict[Path, tuple[str, _PosHtml]] = {}

    # ---------------------------------------------------------------- 공통
    def _message_files(self) -> list[Path]:
        cfg = self.ctx.config
        files = [cfg.resolve(x) for x in (cfg.get("render.messageResources") or [])]
        if not files:
            roots = [d for d in (cfg.source_dir("java"), cfg.source_dir("webapp")) if d and d.is_dir()]
            for r in roots:
                for name in ("MessageResources_ko.properties", "MessageResources.properties",
                             "ApplicationResources_ko.properties", "ApplicationResources.properties"):
                    files += sorted(r.rglob(name))[:3]
        return [f for f in files if f and f.is_file()]

    def _parsed_file(self, f: Path) -> tuple[str, _PosHtml]:
        """파일 → (치환된 원문, 위치 포함 요소 파서). 정적 분석과 같은 전처리(줄·열 보존)."""
        f = f.resolve()
        hit = self._parsed.get(f)
        if hit is None:
            raw = self.p._substitute(read_text_auto(f), f)
            h = _PosHtml()
            h.feed(self.p.preprocess(raw, f))
            hit = (raw, h)
            if len(self._parsed) > 4000:
                self._parsed.clear()
            self._parsed[f] = hit
        return hit

    def _static_includes(self, jsp: Path, raw: str) -> list[Path]:
        """JspJqueryParser._screen 과 같은 순서의 include 목록 (요소 순번 계산용)."""
        if not self.ctx.config.get("source.jspIncludes"):
            return []
        includes: list[Path] = []
        todo = self.p._include_paths(raw, jsp)
        while todo and len(includes) < 30:
            f = todo.pop(0)
            if f in includes:
                continue
            includes.append(f)
            todo += [x for x in self.p._include_paths(self._parsed_file(f)[0], f) if x not in includes]
        return includes

    def screen_deps(self, jsp: Path) -> list[Path]:
        raw, _ = self._parsed_file(jsp)
        return [jsp.resolve()] + self._static_includes(jsp, raw)

    # ---------------------------------------------------------------- 표식
    @staticmethod
    def _offsets(text: str) -> list[int]:
        starts = [0]
        for i, ch in enumerate(text):
            if ch == "\n":
                starts.append(i + 1)
        return starts

    def _annotate(self, raw: str, h: _PosHtml, numbers: list[int]) -> str:
        """각 요소 시작 태그 뒤에 data-pmsx="N" 삽입 (뒤에서부터 넣어 오프셋 유지)."""
        starts = self._offsets(raw)
        ins: list[tuple[int, str]] = []
        for (line, col), el, n in zip(h.pos, h.elements, numbers):
            if line - 1 >= len(starts):
                continue
            o = starts[line - 1] + col
            tag = el["tag"]
            if raw[o:o + 1] != "<" or raw[o + 1:o + 1 + len(tag)].lower() != tag:
                continue
            ins.append((o + 1 + len(tag), f' data-pmsx="{n}"'))
        out = raw
        for o, s in sorted(ins, reverse=True):
            out = out[:o] + s + out[o:]
        return out

    # ---------------------------------------------------------------- 화면
    def render_screen(self, skey: str, jsp: Path) -> RenderedScreen:
        jsp = jsp.resolve()
        raw, h = self._parsed_file(jsp)
        includes = self._static_includes(jsp, raw)
        # 요소 번호·키 (JspJqueryParser._screen 과 같은 규칙)
        seq = 0
        used: set[str] = set()
        keymap: dict[int, str] = {}
        numbers: dict[Path, list[int]] = {}
        for f in [jsp] + includes:
            fh = self._parsed_file(f)[1] if f != jsp else h
            nums = []
            for el in fh.elements:
                seq += 1
                eid = el["id"] or (f"name:{el['name']}" if el["name"] else f"{el['tag']}{seq}")
                if eid in used:
                    eid = f"{eid}.{seq}"
                used.add(eid)
                keymap[seq] = el_key(skey, eid)
                nums.append(seq)
            numbers.setdefault(f, nums)          # 같은 파일이 두 번 나오면 정적 분석처럼 첫 번째 기준
        annotated = {f: self._annotate(self._parsed_file(f)[0] if f != jsp else raw,
                                       self._parsed_file(f)[1] if f != jsp else h, nums)
                     for f, nums in numbers.items()}
        html = self._expand(jsp, annotated, set(), 0)
        html = self._finish(html)
        frameset = bool(re.search(r"<frameset\b", html, re.I))
        return RenderedScreen(html, keymap, [jsp] + includes, frameset)

    # ---------------------------------------------------------------- include 인라인
    def _resolve_include(self, ref: str, base: Path) -> Path | None:
        ref = ref.strip().split("?", 1)[0]
        if not ref:
            return None
        webapp = self.p.webapp
        f = (webapp / ref.lstrip("/")) if ref.startswith("/") and webapp else (base.parent / ref)
        return f.resolve() if f.is_file() else None

    def _expand(self, f: Path, annotated: dict[Path, str], stack: set[Path], depth: int) -> str:
        text = annotated.get(f)
        if text is None:
            text = self._parsed_file(f)[0]
        if depth > 8:
            return text

        def sub(mm: re.Match) -> str:
            ref = mm.group(1) or mm.group(2) or ""
            target = self._resolve_include(ref, f)
            if target is None or target in stack:
                return ""
            return self._expand(target, annotated, stack | {f}, depth + 1)
        return INCLUDE_RE.sub(sub, text)

    # ---------------------------------------------------------------- JSP 제거·치환
    def _placeholder(self, expr: str) -> str:
        e = expr.strip()
        if e in self.placeholders:
            return self.placeholders[e]
        for k, v in self.placeholders.items():
            if k.startswith("re:") and re.search(k[3:], e):
                return v
        if re.fullmatch(r"(pageContext\.)?request\.(getContextPath\(\)|contextPath)|CONTEXT_PATH|ctx", e):
            return self.context_root
        return ""

    def _finish(self, text: str) -> str:
        prefixes = set(DEFAULT_PREFIXES)
        prefixes.update(re.findall(r"<%@\s*taglib\b[^%]*?prefix\s*=\s*[\"'](\w+)[\"']", text))
        text = re.sub(r"<%--.*?--%>", "", text, flags=re.S)
        text = re.sub(r"<%@.*?%>", "", text, flags=re.S)
        text = re.sub(r"<%!.*?%>", "", text, flags=re.S)
        text = resolve_branches(text, self.branch_policy)      # if/else 는 한 분기만 (겹침·숨김 래퍼 중복 방지)
        text = re.sub(r"<%=(.*?)%>", lambda m: htmllib.escape(self._placeholder(m.group(1)), quote=True), text, flags=re.S)
        text = re.sub(r"<%.*?%>", "", text, flags=re.S)
        text = re.sub(r"\$\{([^{}]*)\}", lambda m: self._placeholder(m.group(1).replace("pageContext.request.contextPath", "CONTEXT_PATH")
                                                                       if "contextPath" in m.group(1) else m.group(1)), text)
        text = self._taglibs(text, prefixes)
        if not re.search(r"<meta[^>]+charset", text, re.I):
            text = re.sub(r"(<head[^>]*>)", r'\1<meta charset="utf-8">', text, count=1, flags=re.I) if re.search(r"<head", text, re.I) \
                else '<meta charset="utf-8">' + text
        else:
            text = re.sub(r"(<meta[^>]+charset\s*=\s*[\"']?)[\w-]+", r"\1utf-8", text, flags=re.I)
        return text

    # ---------------------------------------------------------------- 태그 라이브러리
    def _msg(self, attrs: dict[str, str]) -> str:
        key = attrs.get("key", "")
        v = self.messages.get(key)
        if v is None:
            return ""
        for i in range(5):
            if f"arg{i}" in attrs:
                v = v.replace("{%d}" % i, attrs[f"arg{i}"])
        return htmllib.escape(v)

    @staticmethod
    def _attrs(s: str) -> dict[str, str]:
        return {m.group(1): (m.group(2) if m.group(2) is not None else m.group(3) or "") for m in _ATTR_RE.finditer(s or "")}

    @staticmethod
    def _lit(v: str | None) -> str:
        """EL·표현식이 남아 있지 않은 속성값만 그대로 (이미 빈 값으로 치환됐을 수 있음)."""
        return htmllib.escape(v or "", quote=True)

    def _html_tag(self, name: str, a: dict[str, str], body: str | None) -> str:
        lit = self._lit
        prop = lit(a.get("property") or a.get("name") or "")
        idattr = f' id="{lit(a["styleId"])}"' if a.get("styleId") else ""
        cls = f' class="{lit(a["styleClass"])}"' if a.get("styleClass") else ""
        style = f' style="{lit(a["style"])}"' if a.get("style") else ""
        ev = "".join(f' {k}="{lit(v)}"' for k, v in a.items() if k.lower().startswith("on"))
        common = idattr + cls + style + ev
        val = lit(a.get("value") or "")
        inner = body or ""
        if name == "html":
            return f"<html>{inner}</html>"
        if name == "form":
            act = lit(a.get("action") or "")
            return f'<form name="{lit(a.get("styleId") or a.get("name") or "")}" action="{act}"{common}>{inner}</form>'
        if name in ("text", "password", "hidden", "file"):
            return f'<input type="{name}" name="{prop}" value="{val}"{common}/>'
        if name in ("checkbox", "multibox", "radio"):
            t = "radio" if name == "radio" else "checkbox"
            return f'<input type="{t}" name="{prop}" value="{val}"{common}/>'
        if name == "textarea":
            return f'<textarea name="{prop}"{common}>{val}</textarea>'
        if name == "select":
            return f'<select name="{prop}"{common}>{inner}</select>'
        if name == "option":
            return f'<option value="{val}">{inner}</option>'
        if name in ("options", "optionsCollection"):
            return "<option></option>"
        if name in ("submit", "button", "reset", "cancel"):
            t = {"submit": "submit", "reset": "reset"}.get(name, "button")
            label = val or re.sub(r"<[^>]+>", "", inner).strip() or name
            return f'<input type="{t}" value="{lit(label)}"{common}/>'
        if name == "image":
            return f'<input type="image" src="{lit(a.get("src") or a.get("page") or "")}" alt="{lit(a.get("alt") or "")}"{common}/>'
        if name == "img":
            return f'<img src="{lit(a.get("src") or a.get("page") or "")}" alt="{lit(a.get("alt") or "")}"{common}/>'
        if name == "link":
            href = a.get("href") or a.get("page") or a.get("action") or a.get("forward") or "#"
            return f'<a href="{lit(href)}"{common}>{inner}</a>'
        if name == "rewrite":
            return lit(a.get("href") or a.get("page") or a.get("action") or "")
        if name == "frame":
            return f'<frame src="{lit(a.get("href") or a.get("page") or "")}"{common}/>'
        return inner

    def _handle(self, prefix: str, name: str, attrs_s: str, body: str | None) -> str:
        a = self._attrs(attrs_s)
        key = (prefix, name)
        if key in DROP_TAGS:
            return ""
        if key in REPEAT_TAGS:
            return (body or "") * self.repeat
        if prefix == "bean" and name == "message" or prefix == "fmt" and name == "message":
            return self._msg(a)
        if prefix == "c":
            if name == "out":
                v = a.get("value", "")
                return self._lit(v) if v and "${" not in v else self._lit(a.get("default", ""))
            if name == "url":
                return self._lit(a.get("value", ""))
            if name in ("when", "otherwise"):
                return f"\x01{name}\x02{body or ''}\x03"     # c:choose 가 닫힐 때 첫 분기만 고른다
            return body or ""
        if prefix == "fmt":
            if name in ("bundle",):
                return body or ""
            return ""
        if prefix == "html":
            return self._html_tag(name, a, body)
        if prefix == "tiles":
            return ""
        return body or ""

    @staticmethod
    def _choose(body: str) -> str:
        """c:choose → 첫 c:when (없으면 c:otherwise) 본문. 분기는 _handle 이 표식으로 감싸 둔다."""
        for tag in ("when", "otherwise"):
            mm = re.search("\x01" + tag + "\x02(.*?)\x03", body, re.S)
            if mm:
                return mm.group(1)
        return ""

    def _taglibs(self, text: str, prefixes: set[str]) -> str:
        """네임스페이스 태그를 안쪽부터 바꾼다 (짝이 없는 시작 태그는 빈 태그로 취급)."""
        pfx = "|".join(sorted(re.escape(p) for p in prefixes))
        tag_re = re.compile(rf"<(/?)({pfx}):([\w-]+)((?:[^>\"']|\"[^\"]*\"|'[^']*')*?)(/?)>")
        out: list[str] = []
        pos = 0
        stack: list[tuple[str, str, str, int, int]] = []        # (prefix, name, attrs, out 위치, 원문 위치)
        for mm in tag_re.finditer(text):
            closing, prefix, name, attrs, selfclose = mm.groups()
            out.append(text[pos:mm.start()])
            pos = mm.end()
            if closing:
                # 짝 찾기
                idx = next((i for i in range(len(stack) - 1, -1, -1) if stack[i][0] == prefix and stack[i][1] == name), None)
                if idx is None:
                    continue
                # 사이에 짝 없는 시작 태그는 빈 태그로 처리
                while len(stack) - 1 > idx:
                    p2, n2, a2, o2, _ = stack.pop()
                    seg = "".join(out[o2 + 1:])
                    del out[o2 + 1:]
                    out[o2] = self._handle(p2, n2, a2, None) + seg
                p2, n2, a2, o2, _ = stack.pop()
                body = "".join(out[o2 + 1:])
                del out[o2 + 1:]
                out[o2] = self._choose(body) if (p2, n2) == ("c", "choose") else self._handle(p2, n2, a2, body)
            elif selfclose:
                out.append(self._handle(prefix, name, attrs, None))
            else:
                stack.append((prefix, name, attrs, len(out), mm.start()))
                out.append("")              # 자리 (닫힐 때 결과로 바뀜)
        out.append(text[pos:])
        while stack:                         # 끝까지 닫히지 않은 시작 태그
            p2, n2, a2, o2, _ = stack.pop()
            seg = "".join(out[o2 + 1:])
            del out[o2 + 1:]
            out[o2] = self._handle(p2, n2, a2, None) + seg
        return re.sub("\x01\\w+\x02|\x03", "", "".join(out))
