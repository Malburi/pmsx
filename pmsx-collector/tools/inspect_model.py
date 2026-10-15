"""개발용: model.json 요약 — 실패·메뉴·화면·요소 → 워크플로우 트리 출력.

사용: python tools/inspect_model.py <model.json> [요소키 ...]
"""
import json
import sys
from collections import defaultdict


def main() -> None:
    model = json.load(open(sys.argv[1], encoding="utf-8"))
    E = defaultdict(list)
    for e in model["edges"]:
        E[e["from"]].append(e)
    print("failures:")
    for f in model["failures"]:
        print("  ", f["stage"], f["target"], "-", f["message"])
    print("menus:")
    for mn in model["menus"]:
        print("  ", mn["key"], mn["name"], "->", mn.get("screenKey"), mn.get("url", ""))
    print("screens:")
    for s in model["screens"]:
        print("  ", s["key"], s["name"], s["kind"], [c.get("state") for c in s.get("captures", [])])
    print("sql:")
    for s in model["sqlStatements"]:
        print("  ", s["key"], s["kind"], s.get("file", ""))
    print("relations:", [(r["from"], r["to"], r["kind"], r["columns"]) for r in model["relations"]])
    targets = sys.argv[2:] or [e["key"] for e in model["elements"] if e["type"] in ("button", "popup")]

    def walk(k, d, seen):
        for e in sorted(E.get(k, []), key=lambda x: x.get("order", 0)):
            extra = " ".join(x for x in (e.get("crud", ""), f"if {e['condition']}" if e.get("condition") else "") if x)
            print("    " + "  " * d + f"-{e['kind']}-> {e['to']} {extra}")
            if e["to"] not in seen and d < 9:
                walk(e["to"], d + 1, seen | {e["to"]})

    for t in targets:
        print("WORKFLOW", t)
        walk(t, 0, {t})


if __name__ == "__main__":
    main()
