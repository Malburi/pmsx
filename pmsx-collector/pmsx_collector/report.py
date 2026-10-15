"""실행 리포트(report.html) — docs/04 §6. 외부 리소스 없이 단일 HTML.

구성: 요약 타일 → (증분 수집) → 화면별 현황 → 미연결 버튼(원인 추정) → 차단 요청 → 위험 버튼 → 미매칭 요소
      → 추정 관계 → 실패 → 경고
"""
from __future__ import annotations

from collections import defaultdict
from html import escape

BUTTON_TYPES = ("button", "popup", "link")


def _rows(items, cols, empty: str = "없음"):
    if not items:
        return f'<p class="muted">{escape(empty)}</p>'
    head = "".join(f"<th>{escape(c[0])}</th>" for c in cols)
    body = "".join("<tr>" + "".join(f"<td>{escape(str(c[1](i)))}</td>" for c in cols) + "</tr>" for i in items)
    return f'<div class="scroll"><table><thead><tr>{head}</tr></thead><tbody>{body}</tbody></table></div>'


class _Graph:
    """model.json 엣지로 버튼 도달성 계산 (merge.compute_stats 와 같은 규칙)."""

    def __init__(self, model: dict):
        self.by_from: dict[str, list[dict]] = defaultdict(list)
        for e in model["edges"]:
            self.by_from[e["from"]].append(e)
        self.code_units = {c["key"]: c for c in model.get("codeUnits", [])}

    def handlers(self, key: str) -> list[str]:
        return [e["to"] for e in self.by_from.get(key, []) if e["kind"] == "event"]

    def fns(self, key: str, depth: int = 6) -> list[str]:
        seen: list[str] = []
        frontier = self.handlers(key)
        for _ in range(depth):
            nxt = []
            for f in frontier:
                if f in seen or not f.startswith("fn:"):
                    continue
                seen.append(f)
                nxt += [e["to"] for e in self.by_from.get(f, []) if e["kind"] == "calls"]
            frontier = nxt
        return seen

    def reaches_api(self, key: str) -> bool:
        return any(e["kind"] == "request" for f in self.fns(key) for e in self.by_from.get(f, []))

    def reaches_data(self, key: str, depth: int = 10) -> bool:
        seen, frontier = {key}, [key]
        for _ in range(depth):
            nxt = []
            for f in frontier:
                for e in self.by_from.get(f, []):
                    if e["kind"] == "navigates":
                        continue
                    t = e["to"]
                    if t.startswith(("sql:", "sp:", "tbl:")):
                        return True
                    if t not in seen:
                        seen.add(t)
                        nxt.append(t)
            frontier = nxt
        return False

    def navigates(self, key: str) -> bool:
        return any(e["kind"] == "navigates" for f in [key, *self.fns(key)] for e in self.by_from.get(f, []))

    def cause(self, el: dict) -> str:
        """서버(API·데이터)까지 이어지지 않는 버튼의 원인 추정."""
        hs = self.handlers(el["key"])
        if not hs:
            if el.get("matched") == "dynamic-only":
                return "동적 바인딩 추정 — 캡처에서만 발견, 소스에서 이벤트 연결을 찾지 못함"
            return "핸들러 없음 — 소스에 이벤트 연결이 없음"
        missing = [h for h in hs if h.startswith("fn:") and h not in self.code_units]
        if missing:
            return "외부 함수 — 정의를 찾지 못함: " + ", ".join(h.rsplit("#", 1)[-1] for h in missing)
        if self.navigates(el["key"]):
            return "화면 이동·팝업만 (서버 호출 없음)"
        return "화면 내 동작 — 서버 호출 없음 (초기화·닫기 등)"


def _screen_rows(model: dict, g: _Graph) -> list[dict]:
    els_by: dict[str, list[dict]] = defaultdict(list)
    for e in model["elements"]:
        els_by[e["screenKey"]].append(e)
    fails_by: dict[str, list[str]] = defaultdict(list)
    url_to_key = {s.get("url"): s["key"] for s in model["screens"] if s.get("url")}
    for f in model["failures"]:
        t = f.get("target") or ""
        k = t.split("#", 1)[0] if t.startswith("scr:") else url_to_key.get(t)
        if k:
            fails_by[k].append(f"[{f['stage']}] {f['message']}")
    rows = []
    for s in model["screens"]:
        els = els_by.get(s["key"], [])
        btns = [e for e in els if e["type"] in BUTTON_TYPES]
        with_h = [b for b in btns if g.handlers(b["key"])]
        caps = s.get("captures", [])
        cap = "없음" if not caps else ("와이어프레임" if all(c.get("state") == "wireframe" for c in caps) else "캡처")
        rows.append({
            "id": s["screenId"], "name": s.get("name", ""), "tech": s.get("uiTech", ""), "cap": cap,
            "els": len(els), "matched": sum(1 for e in els if e.get("matched") == "full"),
            "btn": f"{len(with_h)}/{len(btns)}",
            "api": sum(1 for b in with_h if g.reaches_api(b["key"])),
            "data": sum(1 for b in with_h if g.reaches_data(b["key"])),
            "fail": " / ".join(fails_by.get(s["key"], [])) or "-",
        })
    return rows


def _incremental_html(inc: dict | None) -> str:
    if not inc or not inc.get("enabled"):
        return ""
    ch = inc.get("changed") or {}
    mode = {"full": "전체 수집", "reuse": "이전 결과 재사용", "partial": "부분(바뀐 화면만 재캡처)"}.get(inc.get("mode"), inc.get("mode"))
    files = [("추가", f) for f in ch.get("added", [])] + [("수정", f) for f in ch.get("modified", [])] + \
            [("삭제", f) for f in ch.get("removed", [])]
    return f"""
<h2>증분 수집</h2>
<p>방식: <b>{escape(str(mode))}</b> · 이전 회차 {escape(str(inc.get('prev_run_id') or '-'))} · {escape(inc.get('reason') or '')}</p>
<p class="muted">다시 캡처한 화면 {len(inc.get('recaptured', []))}개: {escape(', '.join(k.rsplit(':', 1)[-1] for k in inc.get('recaptured', [])) or '-')}<br>
이전 캡처를 재사용한 화면 {len(inc.get('reused', []))}개: {escape(', '.join(k.rsplit(':', 1)[-1] for k in inc.get('reused', [])) or '-')}</p>
{_rows(files[:300], [("변경", lambda r: r[0]), ("파일", lambda r: r[1])], "변경된 파일 없음")}"""


def render_report(model: dict, warnings: list[dict] | None = None, incremental: dict | None = None) -> str:
    warnings = warnings or []
    man, st = model["manifest"], model["manifest"]["stats"]
    els = model["elements"]
    g = _Graph(model)
    names = {s["key"]: f"{s['screenId']} {s.get('name', '')}" for s in model["screens"]}
    buttons = [e for e in els if e["type"] in BUTTON_TYPES]
    unlinked = [e for e in buttons if not (g.reaches_api(e["key"]) or g.reaches_data(e["key"]))]
    danger = [e for e in els if e.get("danger")]
    unmatched = [e for e in els if e["matched"] != "full"]
    inferred = [r for r in model["relations"] if r["kind"] == "inferred"]
    tiles = [
        ("화면", st.get("screens"), f"캡처 {st.get('screensCaptured')} · 와이어프레임 {st.get('screensWireframe', 0)}"),
        ("요소", st.get("elements"), f"매칭률 {st.get('elementMatchRate', 0) * 100:.0f}%"),
        ("버튼 → 핸들러", f"{st.get('buttonsWithHandler')}/{st.get('buttons')}",
         f"API 도달 {st.get('buttonsReachingApi')} · 데이터 도달 {st.get('buttonsReachingData', '-')}"),
        ("연결선", st.get("edges"), f"동적 확인 {st.get('edgesDynamic')}"),
        ("차단된 요청", st.get("blockedRequests"), "변경성 요청 abort"),
        ("추정 관계", st.get("relationsInferred"), f"FK {st.get('relationsFk')}"),
        ("실패 · 경고", f"{st.get('failures')} · {len(warnings)}", "아래 목록 참고"),
    ]
    tile_html = "".join(f'<div class="tile"><span>{escape(t)}</span><b>{escape(str(v))}</b><small>{escape(s)}</small></div>' for t, v, s in tiles)
    screen_rows = _screen_rows(model, g)
    return f"""<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>수집 리포트 {escape(man['runId'])}</title>
<style>
:root{{--bg:#f6f7f9;--panel:#fff;--line:#e3e6eb;--text:#1d2430;--muted:#667085;--accent:#2f6fed;--bad:#c2410c}}
@media (prefers-color-scheme: dark){{:root{{--bg:#11141a;--panel:#191d25;--line:#2b313c;--text:#e6e9ef;--muted:#98a1b0;--accent:#7aa2ff;--bad:#fb923c}}}}
body{{margin:0;background:var(--bg);color:var(--text);font:14px/1.5 "Malgun Gothic",system-ui,sans-serif;padding:24px max(16px,4vw)}}
h1{{font-size:20px;margin:0 0 4px}} h2{{font-size:15px;margin:28px 0 8px}}
.muted{{color:var(--muted)}}
.tiles{{display:grid;grid-template-columns:repeat(auto-fill,minmax(160px,1fr));gap:10px;margin-top:16px}}
.tile{{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:10px 12px;display:flex;flex-direction:column}}
.tile span{{color:var(--muted);font-size:12px}} .tile b{{font-size:22px;font-variant-numeric:tabular-nums}} .tile small{{color:var(--muted)}}
.scroll{{overflow-x:auto}}
table{{border-collapse:collapse;width:100%;background:var(--panel);border:1px solid var(--line);font-size:13px}}
th,td{{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line);vertical-align:top}}
td{{font-variant-numeric:tabular-nums}}
th{{color:var(--muted);font-weight:600}}
</style></head><body>
<h1>수집 리포트 — {escape(model['system']['name'])} ({escape(model['system']['code'])})</h1>
<div class="muted">회차 {escape(man['runId'])} · {escape(man['collectedAt'])} · 수집기 v{escape(man['collectorVersion'])} · 파서 {escape(', '.join(man['parsers']))} · {st.get('durationSec')}초</div>
<div class="tiles">{tile_html}</div>
{_incremental_html(incremental)}

<h2>화면별 현황 ({len(screen_rows)})</h2>
<p class="muted">버튼 = 핸들러 연결/전체 버튼. API 도달 = 서버 요청까지, 데이터 도달 = SQL·프로시저·테이블까지 이어진 버튼 수.</p>
{_rows(screen_rows, [("화면", lambda r: r['id']), ("이름", lambda r: r['name']), ("기술", lambda r: r['tech']), ("캡처", lambda r: r['cap']),
                     ("요소", lambda r: r['els']), ("매칭", lambda r: r['matched']), ("버튼", lambda r: r['btn']),
                     ("API 도달", lambda r: r['api']), ("데이터 도달", lambda r: r['data']), ("실패 사유", lambda r: r['fail'])])}

<h2>서버까지 이어지지 않은 버튼 ({len(unlinked)})</h2>
<p class="muted">원인은 자동 추정입니다. '핸들러 없음'·'동적 바인딩'·'외부 함수'는 뷰어의 핫스팟 편집기에서 연결을 보정하세요. '화면 내 동작'은 정상인 경우가 많습니다.</p>
{_rows(unlinked, [("화면", lambda e: names.get(e['screenKey'], e['screenKey'])), ("요소", lambda e: e['elementId']), ("라벨", lambda e: e['label']),
                  ("원인 추정", g.cause)])}

<h2>차단된 요청 ({len(model['blockedRequests'])})</h2>
<p class="muted">캡처 중 버튼 클릭으로 발생한 변경성 요청은 서버로 보내지 않고 기록만 했습니다.</p>
{_rows(model['blockedRequests'], [("요소", lambda b: b['elementKey']), ("메서드", lambda b: b['method']), ("URL", lambda b: b['url']), ("시각", lambda b: b['at'])])}

<h2>위험 버튼으로 클릭 제외 ({len(danger)})</h2>
{_rows(danger, [("화면", lambda e: names.get(e['screenKey'], e['screenKey'])), ("요소", lambda e: e['elementId']), ("라벨", lambda e: e['label'])])}

<h2>정적·동적 미매칭 요소 ({len(unmatched)})</h2>
{_rows(unmatched, [("화면", lambda e: names.get(e['screenKey'], e['screenKey'])), ("요소", lambda e: e['elementId']), ("라벨", lambda e: e['label']), ("타입", lambda e: e['type']), ("상태", lambda e: e['matched'])])}

<h2>추정 관계 후보 ({len(inferred)})</h2>
{_rows(inferred, [("자식", lambda r: r['from']), ("부모", lambda r: r['to']), ("컬럼", lambda r: ', '.join(f'{a}→{b}' for a, b in r['columns'])), ("근거 SQL 수", lambda r: r.get('evidenceCount')), ("신뢰도", lambda r: r['confidence'])])}

<h2>실패 ({len(model['failures'])})</h2>
{_rows(model['failures'], [("단계", lambda f: f['stage']), ("대상", lambda f: f['target']), ("내용", lambda f: f['message'])])}

<h2>경고 ({len(warnings)})</h2>
<p class="muted">분석은 됐지만 점검이 필요한 항목입니다 (예: MyBatis ${{}} 문자열 치환, 정의를 찾지 못한 외부 프로시저 호출).</p>
{_rows(warnings, [("종류", lambda w: w['kind']), ("대상", lambda w: w['target']), ("내용", lambda w: w['message'])])}
</body></html>
"""
