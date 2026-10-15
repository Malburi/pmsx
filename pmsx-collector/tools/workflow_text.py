"""버튼 워크플로우를 텍스트 트리로 — 파일럿 보고서 예시용.

사용: python tools/workflow_text.py <model.json> <요소 키 또는 '화면ID:라벨'> [...]
"""
from __future__ import annotations

import json
import sys
from collections import defaultdict
from pathlib import Path

LANE = {"fn:js": "FRONT", "fn:jsp": "FRONT", "fn:java": "SERVER", "api:": "API", "sql:": "SQL", "sp:": "SQL", "tbl:": "DB",
        "scr:": "SCREEN"}


def lane(k: str) -> str:
    return next((v for p, v in LANE.items() if k.startswith(p)), "?")


def short(k: str, names: dict) -> str:
    if k in names:
        return names[k]
    if k.startswith("fn:"):
        return k.rsplit("#", 1)[-1] + "()"
    if k.startswith("api:"):
        return k[4:]
    return k.split(":", 1)[-1]


def render(model: dict, start: str, max_depth: int = 8) -> list[str]:
    out = defaultdict(list)
    for e in model["edges"]:
        out[e["from"]].append(e)
    for v in out.values():
        v.sort(key=lambda e: e.get("order", 0))
    names = {}
    lines: list[str] = []
    seen: set[str] = set()

    def walk(k: str, depth: int, prefix: str):
        for e in out.get(k, []):
            if e["kind"] == "navigates":
                lines.append(f"{prefix}↪ 화면 이동 {e['to'].split(':', 2)[-1]}")
                continue
            t = e["to"]
            extra = []
            if e.get("crud"):
                extra.append(e["crud"])
            if e.get("condition"):
                extra.append("조건 " + e["condition"][:60])
            if e.get("confidence", 1) < 0.9:
                extra.append(f"신뢰도 {e['confidence']}")
            tag = f"  [{' · '.join(extra)}]" if extra else ""
            again = t in seen
            lines.append(f"{prefix}{lane(t):6} {short(t, names)}{tag}{'  (위와 동일)' if again else ''}")
            if not again and depth < max_depth:
                seen.add(t)
                walk(t, depth + 1, prefix + "  ")

    lines.append(start)
    walk(start, 0, "  ")
    return lines


def main():
    model = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    els = {e["key"]: e for e in model["elements"]}
    for arg in sys.argv[2:]:
        key = arg
        if arg not in els and ":" in arg and not arg.startswith("el:"):
            sid, label = arg.split(":", 1)
            key = next((e["key"] for e in model["elements"] if e["screenKey"].endswith(":" + sid) and e.get("label") == label), arg)
        e = els.get(key)
        head = f"■ {key}  ({e.get('label')})" if e else key
        sys.stdout.write("\n".join([head] + render(model, key)[1:]) + "\n\n")


if __name__ == "__main__":
    main()
