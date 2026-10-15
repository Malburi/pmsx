"""tree-sitter 공용 헬퍼."""
from __future__ import annotations

from functools import lru_cache
from typing import Iterator

from tree_sitter import Language, Node, Parser


@lru_cache(maxsize=None)
def parser(lang: str) -> Parser:
    if lang == "java":
        import tree_sitter_java as mod
    elif lang == "js":
        import tree_sitter_javascript as mod
    elif lang in ("ts", "tsx"):
        import tree_sitter_typescript as tsmod
        return Parser(Language(tsmod.language_tsx() if lang == "tsx" else tsmod.language_typescript()))
    elif lang == "cs":
        import tree_sitter_c_sharp as mod      # WinForms·ASP.NET·.NET 데이터 접근 (R3)
    else:  # pragma: no cover
        raise ValueError(lang)
    return Parser(Language(mod.language()))


def parse(lang: str, src: str):
    return parser(lang).parse(src.encode("utf-8"))


def text(node: Node | None) -> str:
    return node.text.decode("utf-8") if node is not None and node.text is not None else ""


def walk(node: Node) -> Iterator[Node]:
    """전위 순회 (소스 등장 순서)."""
    stack = [node]
    while stack:
        n = stack.pop()
        yield n
        stack.extend(reversed(n.children))


def string_value(node: Node | None) -> str | None:
    """문자열 리터럴이면 값을, 아니면 None."""
    if node is None:
        return None
    if node.type in ("string", "string_literal", "template_string"):
        t = text(node)
        if len(t) >= 2 and t[0] in "'\"`" and t[-1] == t[0]:
            return t[1:-1]
    return None
