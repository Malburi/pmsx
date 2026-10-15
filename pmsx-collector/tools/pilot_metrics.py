"""파일럿 지표 — model.json 에서 버튼별 도달 단계와 미연결 원인을 집계한다.

사용: python tools/pilot_metrics.py <out/<runId>/model.json> [--json 결과.json] [--examples N]

도달 단계 (버튼 기준, 화면 이동(navigates)은 따라가지 않음)
  handler  : event 엣지가 있음
  api      : … → request 로 API(엔드포인트)에 도달
  server   : … → API → handles 로 서버 핸들러(Action/서비스 메서드)에 도달
  sql      : … → executes 로 SQL 문에 도달
  table    : … → accesses 로 테이블에 도달
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

BUTTON_TYPES = ("button", "popup", "link")
FOLLOW = {"event", "calls", "request", "handles", "executes", "accesses"}


def analyze(model: dict, examples: int = 3) -> dict:
    out = defaultdict(list)
    for e in model["edges"]:
        if e["kind"] in FOLLOW and e.get("status", "active") != "rejected":
            out[e["from"]].append(e)
    fails_by = defaultdict(list)
    for f in model.get("failures", []):
        fails_by[f["target"]].append(f["message"])
    els = model["elements"]
    buttons = [e for e in els if e["type"] in BUTTON_TYPES]

    def reach(start: str, depth: int = 12):
        seen, frontier = {start}, [start]
        hit = {"handler": False, "api": False, "server": False, "sql": False, "table": False}
        fns: list[str] = []
        apis: list[str] = []
        for d in range(depth):
            nxt = []
            for f in frontier:
                for e in out.get(f, []):
                    t, k = e["to"], e["kind"]
                    if k == "event":
                        hit["handler"] = True
                    if k == "request":
                        hit["api"] = True
                        apis.append(t)
                    if k == "handles":
                        hit["server"] = True
                    if k == "executes" or t.startswith(("sql:", "sp:")):
                        hit["sql"] = True
                    if k == "accesses" or t.startswith("tbl:"):
                        hit["table"] = True
                    if t.startswith("fn:"):
                        fns.append(t)
                    if t not in seen:
                        seen.add(t)
                        nxt.append(t)
            frontier = nxt
        return hit, fns, apis

    stage = Counter()
    stage_fw = Counter()
    screens_url = {sc["key"] for sc in model["screens"] if sc.get("url")}
    n_fw = sum(1 for b in buttons if b["screenKey"] in screens_url)
    reasons = Counter()
    reason_examples: dict[str, list[str]] = defaultdict(list)
    for b in buttons:
        hit, fns, apis = reach(b["key"])
        for k, v in hit.items():
            if v:
                stage[k] += 1
                if b["screenKey"] in screens_url:
                    stage_fw[k] += 1
        if hit["table"]:
            continue
        # 미연결 원인 (가장 앞 단계에서 끊긴 이유)
        if not hit["handler"]:
            r = "핸들러 없음(요소에 이벤트 연결 없음)"
        elif not hit["api"]:
            msgs = [m for f in fns for m in fails_by.get(f, [])]
            if any("기준 Action" in m for m in msgs):
                r = "상대 URL 기준 Action 미상(화면을 보여 주는 Action 을 찾지 못함)"
            elif any("URL 인자" in m or "해석하지 못함" in m for m in msgs):
                r = "URL 이 동적으로 만들어져 정적 해석 불가"
            elif not fns:
                r = "핸들러 함수 정의를 찾지 못함"
            else:
                r = "서버 요청 없음(화면 안 동작·검증·팝업 열기 등)"
        elif not hit["server"]:
            r = "Action 매핑 없음(요청 URL 에 해당하는 struts action/메서드 없음)"
        elif not hit["sql"]:
            r = "서버 처리에 SQL 없음(파일 업·다운로드 등) 또는 쿼리 ID 미확정"
        else:
            r = "SQL 에서 테이블 미추출"
        reasons[r] += 1
        if len(reason_examples[r]) < examples:
            reason_examples[r].append(f"{b['screenKey'].split(':', 2)[-1]} · {b.get('label', '')} ({b['elementId']})")
    n = len(buttons) or 1
    stats = model["manifest"]["stats"]
    fail_kinds = Counter(re.sub(r"[:(].*", "", f["message"]).strip()[:40] for f in model.get("failures", []))
    return {
        "screens": len(model["screens"]),
        "elements": len(els),
        "buttons": len(buttons),
        "reach": {k: stage[k] for k in ("handler", "api", "server", "sql", "table")},
        "rate": {k: round(stage[k] / n * 100, 1) for k in ("handler", "api", "server", "sql", "table")},
        "forwardedScreens": len(screens_url),
        "buttonsOnForwardedScreens": n_fw,
        "rateOnForwardedScreens": {k: round(stage_fw[k] / (n_fw or 1) * 100, 1) for k in ("handler", "api", "server", "sql", "table")},
        "unreached": dict(reasons.most_common()),
        "unreachedExamples": dict(reason_examples),
        "apis": len(model["apis"]),
        "apisHandled": sum(1 for a in model["apis"] if any(e["kind"] == "handles" for e in out.get(a["key"], []))),
        "codeUnits": len(model["codeUnits"]),
        "sql": len(model["sqlStatements"]),
        "sqlUsed": len({e["to"] for es in out.values() for e in es if e["kind"] == "executes"}),
        "tables": len(model["tables"]),
        "edges": len(model["edges"]),
        "failures": len(model.get("failures", [])),
        "sqlParseFallback": None,
        "failureKinds": dict(fail_kinds.most_common(12)),
        "durationSec": stats.get("durationSec"),
    }


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("model")
    ap.add_argument("--json")
    ap.add_argument("--examples", type=int, default=3)
    a = ap.parse_args(argv)
    model = json.loads(Path(a.model).read_text(encoding="utf-8"))
    res = analyze(model, a.examples)
    txt = json.dumps(res, ensure_ascii=False, indent=2)
    if a.json:
        Path(a.json).write_text(txt, encoding="utf-8")
    sys.stdout.write(txt + "\n")


if __name__ == "__main__":
    main()
