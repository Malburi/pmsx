"""중간모델 빌더 — 파서·캡처가 조각을 넣고, 마지막에 asis-model.schema.json 형태로 직렬화한다."""
from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from . import MODEL_VERSION, __version__
from .keys import sys_key

SNIPPET_MAX_LINES = 80

SOURCE_RANK = {"static": 0, "manual": 1, "dynamic": 2}


def clip_snippet(text: str | None) -> str | None:
    if text is None:
        return None
    lines = text.splitlines()
    if len(lines) > SNIPPET_MAX_LINES:
        lines = lines[:SNIPPET_MAX_LINES] + [f"... ({len(text.splitlines()) - SNIPPET_MAX_LINES}줄 생략)"]
    return "\n".join(lines)


def _public(d: dict) -> dict:
    """'_' 로 시작하는 내부 필드 제거."""
    return {k: v for k, v in d.items() if not k.startswith("_")}


class ModelBuilder:
    def __init__(self, sys_code: str, name: str, ui_tech: list[str], server_tech: list[str], db_type: str | None):
        self.system = {"key": sys_key(sys_code), "code": sys_code, "name": name,
                       "uiTech": ui_tech, "serverTech": server_tech}
        if db_type:
            self.system["dbType"] = db_type
        self.menus: dict[str, dict] = {}
        self.screens: dict[str, dict] = {}
        self.elements: dict[str, dict] = {}
        self.code_units: dict[str, dict] = {}
        self.apis: dict[str, dict] = {}
        self.sql: dict[str, dict] = {}
        self.tables: dict[str, dict] = {}
        self.relations: list[dict] = []
        self.edges: dict[tuple, dict] = {}
        self.blocked: list[dict] = []
        self.failures: list[dict] = []
        self.warnings: list[dict] = []          # 실패는 아니지만 분석자가 볼 것 (report.html 전용, model.json 미포함)
        self.parsers_used: list[str] = []
        self.capture_stats: dict[str, float] = {}
        self._order: dict[str, int] = {}
        self._indexed = 0
        self._out: dict[str, list[dict]] = {}       # from → 엣지 (edges_from 색인 — 대규모 시스템에서 O(E) 탐색 방지)

    # ---------- 노드 ----------
    def add_code_unit(self, key: str, lang: str, file: str, name: str, line_start: int | None = None,
                      line_end: int | None = None, snippet: str | None = None, common: bool = False) -> dict:
        cu = self.code_units.get(key)
        if cu is None:
            cu = {"key": key, "lang": lang, "file": file, "name": name}
            if line_start:
                cu["lineStart"] = line_start
            if line_end:
                cu["lineEnd"] = line_end
            if snippet is not None:
                cu["snippet"] = clip_snippet(snippet)
            if common:
                cu["common"] = True
            self.code_units[key] = cu
        return cu

    def add_api(self, key: str, method: str, path: str) -> dict:
        api = self.apis.get(key)
        if api is None:
            api = {"key": key, "method": method.upper(), "path": path}
            self.apis[key] = api
        return api

    def add_table(self, key: str, schema: str, name: str, **extra) -> dict:
        t = self.tables.get(key)
        if t is None:
            t = {"key": key, "schema": schema, "name": name.upper(), "kind": "table"}
            self.tables[key] = t
        for k, v in extra.items():
            if v not in (None, [], ""):
                t[k] = v
        return t

    def fail(self, stage: str, target: str, message: str):
        self.failures.append({"stage": stage, "target": target, "message": message})

    def warn(self, kind: str, target: str, message: str):
        self.warnings.append({"kind": kind, "target": target, "message": message})

    # ---------- 엣지 ----------
    def next_order(self, frm: str) -> int:
        self._order[frm] = self._order.get(frm, 0) + 1
        return self._order[frm]

    def add_edge(self, frm: str, to: str, kind: str, source: str = "static", confidence: float = 0.95,
                 event: str | None = None, condition: str | None = None, crud: str | None = None,
                 columns: list[str] | None = None, evidence: str | None = None, order: int | None = None) -> dict:
        k = (frm, to, kind, event)
        e = self.edges.get(k)
        if e is None:
            e = {"from": frm, "to": to, "kind": kind, "source": source, "confidence": round(confidence, 2),
                 "order": order if order is not None else self.next_order(frm)}
            if event:
                e["event"] = event
            if condition:
                e["condition"] = condition[:200]
            if evidence:
                e["evidence"] = evidence
            self.edges[k] = e
            self._out.setdefault(frm, []).append(e)
            self._indexed += 1
        else:
            # 동적 관측 > 수동 > 정적. 더 강한 근거면 덮어쓴다.
            if SOURCE_RANK[source] > SOURCE_RANK[e["source"]]:
                e["source"] = source
                if evidence:
                    e["evidence"] = evidence if not e.get("evidence") else f"{e['evidence']} | {evidence}"
            e["confidence"] = round(max(e["confidence"], confidence), 2)
            if condition and not e.get("condition"):
                e["condition"] = condition[:200]
        if crud:
            merged = set(e.get("crud", "")) | set(crud)
            e["crud"] = "".join(c for c in "CRUD" if c in merged)
        if columns:
            e["columns"] = sorted(set(e.get("columns", [])) | set(columns))
        return e

    def edges_from(self, frm: str, kind: str | None = None) -> list[dict]:
        # 색인은 add_edge 로 넣은 엣지만 담는다. 외부에서 edges 를 직접 바꾼 경우(드묾)에 대비해 개수가 다르면 다시 만든다.
        if self._indexed != len(self.edges):
            self._out = {}
            for e in self.edges.values():
                self._out.setdefault(e["from"], []).append(e)
            self._indexed = len(self.edges)
        lst = [e for e in self._out.get(frm, []) if e["from"] == frm and (kind is None or e["kind"] == kind)]
        return sorted(lst, key=lambda e: e["order"])

    # ---------- 직렬화 ----------
    def to_dict(self, run_id: str, stats: dict[str, float]) -> dict[str, Any]:
        return {
            "manifest": {
                "modelVersion": MODEL_VERSION,
                "collectorVersion": __version__,
                "runId": run_id,
                "collectedAt": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
                "parsers": self.parsers_used,
                "stats": stats,
            },
            "system": self.system,
            "menus": sorted(self.menus.values(), key=lambda m: (m.get("parentKey") or "", m.get("order", 0), m["key"])),
            "screens": sorted(self.screens.values(), key=lambda s: s["key"]),
            "elements": [_public(e) for e in sorted(self.elements.values(), key=lambda e: (e["screenKey"], e.get("_seq", 0), e["key"]))],
            "codeUnits": sorted(self.code_units.values(), key=lambda c: c["key"]),
            "apis": sorted(self.apis.values(), key=lambda a: a["key"]),
            "sqlStatements": sorted(self.sql.values(), key=lambda s: s["key"]),
            "tables": sorted(self.tables.values(), key=lambda t: t["key"]),
            "relations": self.relations,
            "edges": sorted(self.edges.values(), key=lambda e: (e["from"], e["order"], e["to"])),
            "blockedRequests": self.blocked,
            "failures": self.failures,
        }
