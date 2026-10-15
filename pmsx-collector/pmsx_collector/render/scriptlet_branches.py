"""스크립틀릿 분기 정리 — <% if (..) { %> A <% } else { %> B <% } %> 에서 한 분기만 남긴다.

서버 없이 JSP 를 그리면 조건 분기의 양쪽 HTML 이 모두 나와 화면이 겹치거나(display:none 래퍼가 두 번 열림)
깨진다. Java 코드의 중괄호 구조를 따라 if/else 사슬은 한 분기(기본: 마지막 = else, 보통 '신규 등록·기본 보기')만,
try/catch 는 try 본문만 남기고, for/while 등 일반 블록은 본문을 1회 남긴다.
"""
from __future__ import annotations

import re

SCRIPTLET_RE = re.compile(r"<%(?![=@!-])(.*?)%>", re.S)


def _clean_java(code: str) -> str:
    """문자열·문자·주석을 공백으로 지운다 (중괄호 판단용)."""
    out, i, n = [], 0, len(code)
    while i < n:
        c = code[i]
        if c == "/" and i + 1 < n and code[i + 1] == "/":
            j = code.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif c == "/" and i + 1 < n and code[i + 1] == "*":
            j = code.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append(" " * (j - i))
            i = j
        elif c in "\"'":
            j = i + 1
            while j < n and code[j] != c:
                j += 2 if code[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(" " * (j - i))
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def _score(html: str) -> float:
    """분기 선택 점수: 태그 수 + 글자 수(가중) — 숨김(display:none) 분기는 뒤로."""
    tags = len(re.findall(r"<[a-zA-Z]", html))
    text = len(re.sub(r"<[^>]*>|\s+", "", html))
    hidden = re.search(r"display\s*:\s*none|visibility\s*:\s*hidden", html, re.I)
    return tags + 0.05 * text - (1000 if hidden else 0)


class _Frame:
    __slots__ = ("kind", "branches", "pending")

    def __init__(self, kind: str):
        self.kind = kind                 # if | try | block | root
        self.branches: list[list[str]] = [[]]
        self.pending = False             # 분기 하나가 닫혔고, else/catch 가 이어질 수 있는 상태


def resolve_branches(text: str, policy: str = "rich") -> str:
    stack: list[_Frame] = [_Frame("root")]

    def emit(s: str):
        stack[-1].branches[-1].append(s)

    def finalize(fr: _Frame) -> str:
        if fr.kind == "if":
            if policy == "first":
                br = fr.branches[0]
            elif policy == "last" or len(fr.branches) == 1:
                br = fr.branches[-1]
            else:                                      # rich: 내용이 가장 많은(숨김이 아닌) 분기
                scored = [(_score("".join(b)), i) for i, b in enumerate(fr.branches)]
                br = fr.branches[max(scored)[1]]
        elif fr.kind == "try":
            br = fr.branches[0]
        else:
            br = [s for b in fr.branches for s in b]
        return "".join(br)

    def settle_pending():
        while len(stack) > 1 and stack[-1].pending:
            fr = stack.pop()
            emit(finalize(fr))

    pos = 0
    for mm in SCRIPTLET_RE.finditer(text):
        seg = text[pos:mm.start()]
        pos = mm.end()
        if seg.strip():
            settle_pending()
        emit(seg) if not (stack[-1].pending and not seg.strip()) else None
        code = _clean_java(mm.group(1))
        header_start = 0
        for i, ch in enumerate(code):
            if ch in ";":
                if code[header_start:i].strip():
                    settle_pending()
                header_start = i + 1
            elif ch == "{":
                header = code[header_start:i].strip()
                top = stack[-1]
                if top.pending and re.match(r"(else|catch|finally)\b", header):
                    top.pending = False
                    top.branches.append([])
                else:
                    if header:
                        settle_pending()
                    kind = "if" if re.match(r"if\b", header) else "try" if re.match(r"try\b", header) else "block"
                    stack.append(_Frame(kind))
                header_start = i + 1
            elif ch == "}":
                if code[header_start:i].strip():
                    settle_pending()
                settle_pending()
                if len(stack) > 1:
                    top = stack[-1]
                    if top.kind in ("if", "try"):
                        top.pending = True       # 다음 코드가 else/catch 인지 봐야 사슬을 끝낼 수 있다
                    else:
                        stack.pop()
                        emit(finalize(top))
                header_start = i + 1
        rest = code[header_start:].strip()
        if rest and not re.match(r"(else|catch|finally)\b", rest):
            settle_pending()
    tail = text[pos:]
    if tail.strip():
        settle_pending()
    emit(tail)
    while len(stack) > 1:
        fr = stack.pop()
        emit(finalize(fr))
    return "".join(stack[0].branches[0])
