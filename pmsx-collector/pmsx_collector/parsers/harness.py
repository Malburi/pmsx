"""harness `_workspace/index/*.json` 연계 (FR-CL-019).

AX-Harness 결정론 인덱서(build-index.mjs)가 만든 인덱스를 우리 중간모델에 합친다.
  call_graph.json   nodes(id·type·file·line) / edges(call·inject·inherit)      → calls
  sql_usage.json    sqls(id=ns.id) / usages(sql_id·method)                    → executes
  schema.json       tables / relations(foreign_key·query_join)                → 테이블·FK·추정 관계 근거
  api_contract.json endpoints(method·path·handler) / consumers(file·line)     → apis·handles·request

병합 모드 (harness.mode)
  fill  (기본) 우리 파서가 만든 노드 사이의 연결만 보탠다. 신뢰도 HIGH 이거나, 출발 노드에 같은 종류 연결이 없을 때만.
               → 우리 분석과 겹치는 연결은 자연히 합쳐지고(같은 키), 인덱서 오탐이 기존 흐름을 흐리지 않는다.
  union        harness 에만 있는 함수·SQL 노드도 새로 만들고 모든 연결을 넣는다 (우리 파서가 없는 기술 스택용).
"""
from __future__ import annotations

import json
from pathlib import Path

from ..keys import api_key, fn_key, tbl_key
from . import Context

CONF = {"HIGH": 0.9, "MEDIUM": 0.75, "LOW": 0.55}
LANG_BY_EXT = {".java": "java", ".js": "js", ".jsx": "js", ".mjs": "js", ".ts": "ts", ".tsx": "ts", ".vue": "js",
               ".xjs": "js", ".xfdl": "xfdl", ".cs": "cs", ".vb": "vb", ".jsp": "jsp", ".xml": "xml"}
ANNOTATION_NAMES = {"GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping"}


def _load(d: Path, name: str) -> dict:
    p = d / f"{name}.json"
    if not p.is_file():
        return {}
    try:
        return json.loads(p.read_text(encoding="utf-8"))
    except (ValueError, OSError):
        return {}


class HarnessParser:
    name = "harness"
    layer = "ui"

    def detect(self, root: Path) -> float:
        return 1.0 if (root / "_workspace" / "index" / "call_graph.json").is_file() else 0.0

    def parse(self, ctx: Context) -> None:
        cfg, m = ctx.config, ctx.model
        ws = cfg.resolve(cfg.get("harness.workspace"))
        idx = ws / "index" if ws and (ws / "index").is_dir() else ws
        if not idx or not (idx / "call_graph.json").is_file():
            m.fail("parse", "harness.workspace", f"harness 인덱스(call_graph.json)를 찾지 못함: {ws}")
            return
        self.ctx = ctx
        self.union = cfg.get("harness.mode", "fill") == "union"
        self.hroot = cfg.resolve(cfg.get("harness.root")) if cfg.get("harness.root") else (idx.parent.parent if idx.name == "index" else idx.parent)
        self.added = {"calls": 0, "executes": 0, "handles": 0, "request": 0, "nodes": 0, "tables": 0, "relations": 0, "joins": 0}
        self._index_units()
        self.node_key: dict[str, str] = {}
        cg = _load(idx, "call_graph")
        for n in cg.get("nodes", []):
            key = self._map_node(n)
            if key:
                self.node_key[n["id"]] = key
        for e in cg.get("edges", []):
            if e.get("type") == "call":
                self._edge(e.get("from"), e.get("to"), "calls", e.get("confidence"), e.get("file"), e.get("line"))
        self._sql(_load(idx, "sql_usage"))
        self._schema(_load(idx, "schema"))
        self._api(_load(idx, "api_contract"))
        m.parsers_used.append(f"harness({', '.join(f'{k}+{v}' for k, v in self.added.items() if v)})")

    # ---------- 우리 코드 단위 색인 ----------
    def _bases(self) -> list[Path]:
        cfg = self.ctx.config
        out = [cfg.source_root]
        for k in ("webapp", "nexacro", "vue", "react", "java"):
            d = cfg.source_dir(k)
            if d:
                out.append(d)
        return out

    def _index_units(self) -> None:
        """(절대 파일 경로, 이름) → 우리 code unit 키."""
        self.units: dict[tuple[str, str], str] = {}
        self.units_by_file: dict[str, list[dict]] = {}
        bases = self._bases()
        for cu in self.ctx.model.code_units.values():
            f = cu["file"].lstrip("/")
            for b in bases:
                p = (b / f)
                if p.is_file():
                    ap = str(p.resolve()).lower()
                    self.units[(ap, cu["name"])] = cu["key"]
                    self.units[(ap, cu["name"].split(".")[-1])] = self.units.get((ap, cu["name"].split(".")[-1]), cu["key"])
                    self.units_by_file.setdefault(ap, []).append(cu)
                    break

    def _abs(self, file: str | None) -> Path | None:
        if not file:
            return None
        p = (self.hroot / file)
        return p.resolve() if p.exists() else None

    def _map_node(self, n: dict) -> str | None:
        if n.get("type") not in ("method", "function"):
            return None
        ap = self._abs(n.get("file"))
        if ap is None:
            return None
        parts = n["id"].split(".")
        short, two = parts[-1], ".".join(parts[-2:])
        key = self.units.get((str(ap).lower(), two)) or self.units.get((str(ap).lower(), short))
        if key or not self.union or short in ANNOTATION_NAMES:
            return key
        lang = LANG_BY_EXT.get(ap.suffix.lower(), "other")
        name = two if lang in ("java", "cs") else short
        key = fn_key(lang, self.ctx.config.rel(ap), name)
        self.ctx.model.add_code_unit(key, lang, self.ctx.config.rel(ap), name, n.get("line"))
        self.added["nodes"] += 1
        return key

    def _edge(self, frm_id: str | None, to_id: str | None, kind: str, conf: str | None, file: str | None, line) -> None:
        frm, to = self.node_key.get(frm_id or ""), self.node_key.get(to_id or "")
        self._edge_keys(frm, to, kind, conf, file, line)

    def _edge_keys(self, frm: str | None, to: str | None, kind: str, conf: str | None, file: str | None, line) -> None:
        m = self.ctx.model
        if not frm or not to or frm == to:
            return
        exists = any(e["to"] == to for e in m.edges_from(frm, kind))
        if exists:
            m.add_edge(frm, to, kind, evidence=f"harness:{file}:{line}")      # 같은 연결 — 근거만 보탬
            return
        if not self.union and (conf or "").upper() != "HIGH" and m.edges_from(frm, kind):
            return
        m.add_edge(frm, to, kind, confidence=CONF.get((conf or "").upper(), 0.6), evidence=f"harness:{file}:{line}")
        self.added[kind] = self.added.get(kind, 0) + 1

    # ---------- SQL ----------
    def _sql(self, su: dict) -> None:
        ctx, m = self.ctx, self.ctx.model
        for u in su.get("usages", []):
            skey = ctx.sql_by_ref.get(u.get("sql_id", ""))
            frm = self.node_key.get(u.get("method", ""))
            if skey and frm:
                self._edge_keys(frm, skey, "executes", u.get("confidence"), u.get("file"), u.get("line"))

    # ---------- 스키마 ----------
    def _schema(self, sc: dict) -> None:
        ctx, cfg, m = self.ctx, self.ctx.config, self.ctx.model
        for t in sc.get("tables", []):
            key = tbl_key(cfg.schema, t["name"])
            if key in m.tables and m.tables[key].get("columns"):
                continue
            cols = [{"name": c["name"].upper(), "dataType": c.get("type") or "UNKNOWN", "nullable": bool(c.get("nullable", True)),
                     "pk": bool(c.get("primary_key"))} for c in t.get("columns", [])]
            m.add_table(key, cfg.schema, t["name"], columns=cols or None, comment=t.get("comment"))
            ctx.table_pk.setdefault(t["name"].upper(), [c["name"] for c in cols if c["pk"]])
            ctx.table_cols.setdefault(t["name"].upper(), {c["name"] for c in cols})
            self.added["tables"] += 1
        have = {(r["from"], r["to"], tuple(map(tuple, r["columns"]))) for r in m.relations}
        for r in sc.get("relations", []):
            a, b = r.get("from_table", "").upper(), r.get("to_table", "").upper()
            fc, tc = [c.upper() for c in r.get("from_columns", [])], [c.upper() for c in r.get("to_columns", [])]
            if not a or not b or len(fc) != len(tc):
                continue
            if r.get("type") == "foreign_key":
                rel = (tbl_key(cfg.schema, a), tbl_key(cfg.schema, b), tuple(zip(fc, tc)))
                if rel not in have:
                    m.relations.append({"from": rel[0], "to": rel[1], "columns": [list(p) for p in rel[2]], "kind": "fk", "confidence": 1.0})
                    have.add(rel)
                    self.added["relations"] += 1
            elif r.get("type") == "query_join" and len(fc) == 1:
                skey = ctx.sql_by_ref.get(r.get("sql_id", ""), f"harness:{r.get('sql_id')}")
                ev = (skey, (a, fc[0]), (b, tc[0]))
                if ev not in ctx.join_evidence:
                    ctx.join_evidence.append(ev)
                    self.added["joins"] += 1

    # ---------- API ----------
    def _api(self, ac: dict) -> None:
        m = self.ctx.model
        for ep in ac.get("endpoints", []):
            method, path = (ep.get("method") or "GET").upper(), self.ctx.normalize_url(ep.get("path") or "/")
            handler = self.node_key.get(ep.get("handler", ""))
            akey = api_key(method, path)
            if akey not in m.apis:
                if not handler and not self.union:
                    continue
                m.add_api(akey, method, path)
            if handler:
                self._edge_keys(akey, handler, "handles", ep.get("confidence"), ep.get("file"), ep.get("line"))
        for c in ac.get("consumers", []):
            ap = self._abs(c.get("file"))
            if ap is None:
                continue
            line = int(c.get("line") or 0)
            owner = next((u["key"] for u in self.units_by_file.get(str(ap).lower(), [])
                          if u.get("lineStart", 0) <= line <= u.get("lineEnd", 0)), None)
            method, path = (c.get("method") or "GET").upper(), self.ctx.normalize_url(c.get("path_literal") or c.get("path_pattern") or "/")
            akey = api_key(method, path)
            if owner and (akey in m.apis or self.union):
                m.add_api(akey, method, path)
                self._edge_keys(owner, akey, "request", c.get("confidence"), c.get("file"), c.get("line"))
