"""메뉴 수집 — 메뉴 테이블 export(CSV) · INSERT 문 SQL 파일 · DB 직접 조회(menu.query)."""
from __future__ import annotations

import csv
import re
from pathlib import Path

from ..keys import menu_key
from . import Context


def _rows_from_sql(path: Path) -> list[dict]:
    """INSERT INTO TB_MENU (A, B, ..) VALUES ('x', 'y', ..); 형태."""
    rows = []
    for m in re.finditer(r"INSERT\s+INTO\s+\w+\s*\(([^)]*)\)\s*VALUES\s*\((.*?)\)\s*;", path.read_text(encoding="utf-8"), re.S | re.I):
        names = [c.strip().upper() for c in m.group(1).split(",")]
        raw_vals = re.findall(r"'(?:[^']|'')*'|NULL|-?\d+", m.group(2), re.I)
        vals = [None if v.upper() == "NULL" else v.strip("'").replace("''", "'") for v in raw_vals]
        rows.append(dict(zip(names, vals)))
    return rows


def rows_from_nexacro_dataset(path: Path, dataset_id: str | None = None) -> list[dict]:
    """Nexacro Dataset XML(<Dataset><Rows><Row><Col id="X">v</Col>…) → 행 목록. xfdl 안의 Dataset 도 id 로 지정 가능."""
    import xml.etree.ElementTree as ET
    doc = ET.fromstring(path.read_bytes())
    dss = [doc] if doc.tag == "Dataset" else list(doc.iter("Dataset"))
    if dataset_id:
        dss = [d for d in dss if d.get("id") == dataset_id]
    rows: list[dict] = []
    for ds in dss[:1]:
        rws = ds.find("Rows")
        for r in (rws.findall("Row") if rws is not None else []):
            rows.append({c.get("id", "").upper(): (c.text or "").strip() for c in r.findall("Col")})
    return rows


class MenuParser:
    name = "menu"
    layer = "ui"   # 화면 키 확정 전 단계에서 호출 (jsp 파서가 사용)

    def detect(self, root: Path) -> float:
        return 0.5

    def parse(self, ctx: Context) -> list[dict]:
        cfg, m = ctx.config, ctx.model
        cols = {"id": "MENU_ID", "parent": "UP_MENU_ID", "name": "MENU_NM", "url": "MENU_URL", "order": "SORT_ORD"}
        cols.update(cfg.get("menu.columns", {}) or {})
        rows: list[dict] = []
        if cfg.get("menu.csv"):
            p = cfg.resolve(cfg.get("menu.csv"))
            with open(p, encoding="utf-8-sig") as f:
                rows = list(csv.DictReader(f))
        elif cfg.get("menu.sqlFile"):
            rows = _rows_from_sql(cfg.resolve(cfg.get("menu.sqlFile")))
        elif cfg.get("menu.nexacroDataset"):
            rows = rows_from_nexacro_dataset(cfg.resolve(cfg.get("menu.nexacroDataset")), cfg.get("menu.datasetId"))
        elif cfg.get("menu.router"):
            from .spa import menu_rows_from_router     # SPA 라우터(Vue/React) 정의에서 메뉴 생성
            rows = menu_rows_from_router(ctx)
            cols = {"id": "MENU_ID", "parent": "UP_MENU_ID", "name": "MENU_NM", "url": "MENU_URL", "order": "SORT_ORD"}
        elif cfg.get("menu.directory"):
            return []                                # 화면 파서가 끝난 뒤 폴더 구조로 만든다 (ui_common.finalize_ui)
        elif cfg.get("menu.query"):
            from ..dbmeta import run_select        # 메뉴 테이블을 DB 에서 직접 조회 (읽기 전용, SELECT 만)
            try:
                rows = run_select(cfg, cfg.get("menu.query"))
            except Exception as e:  # noqa: BLE001
                m.fail("menu", "menu.query", f"메뉴 조회 실패: {str(e).splitlines()[0][:200]}")
                return []
        else:
            m.fail("menu", "menu", "메뉴 소스(menu.csv / menu.sqlFile / menu.nexacroDataset / menu.router / menu.query)가 없어 메뉴 트리를 만들지 못했습니다.")
            return []
        out = []
        for r in rows:
            mid = (r.get(cols["id"]) or "").strip()
            if not mid:
                continue
            parent = (r.get(cols["parent"]) or "").strip()
            entry = {"key": menu_key(cfg.sys_code, mid), "parentKey": menu_key(cfg.sys_code, parent) if parent else None,
                     "name": (r.get(cols["name"]) or "").strip(), "order": int(r.get(cols["order"]) or 0),
                     "screenKey": None}
            url = (r.get(cols["url"]) or "").strip()
            if url:
                # Nexacro 폼 URL(PY::PY0101.xfdl)은 서버 경로가 아니므로 그대로 둔다
                entry["url"] = url if "::" in url else ctx.normalize_url(url)
            m.menus[entry["key"]] = entry
            out.append(entry)
        return out


def build_directory_menu(ctx: Context) -> int:
    """메뉴 소스가 없을 때(DB 메뉴 테이블 반입 불가 등) 화면 소스 폴더 구조로 메뉴 트리를 만든다.

    menu:
      directory:
        maxDepth: 3                    # 폴더 단계 상한 (그 아래 화면은 마지막 폴더에 모은다)
        labels: {back: 관리자, front: 사용자, "back/main/code": 코드관리}   # 폴더 경로 또는 폴더명 → 표시 이름
        includePopups: false
    """
    cfg, m = ctx.config, ctx.model
    spec = cfg.get("menu.directory") or {}
    if spec is True:
        spec = {}
    root = cfg.source_dir(spec.get("root", "jsp")) if isinstance(spec.get("root", "jsp"), str) else None
    max_depth = int(spec.get("maxDepth", 3))
    labels = spec.get("labels") or {}
    popups = bool(spec.get("includePopups", False))
    made = 0
    order: dict[str | None, int] = {}

    def node(path: str) -> str:
        key = menu_key(cfg.sys_code, "D:" + path)
        if key not in m.menus:
            parent = path.rsplit("/", 1)[0] if "/" in path else None
            pkey = node(parent) if parent else None
            order[pkey] = order.get(pkey, 0) + 1
            name = labels.get(path) or labels.get(path.rsplit("/", 1)[-1]) or path.rsplit("/", 1)[-1]
            m.menus[key] = {"key": key, "parentKey": pkey, "name": str(name), "order": order[pkey], "screenKey": None}
        return key

    for s in sorted(m.screens.values(), key=lambda x: x.get("sourceFile") or ""):
        if s.get("kind") == "popup" and not popups:
            continue
        src = s.get("sourceFile") or ""
        f = cfg.source_root / src
        try:
            rel = f.resolve().relative_to(root.resolve()).as_posix() if root else src
        except ValueError:
            rel = src
        dirs = rel.split("/")[:-1][:max_depth]
        pkey = node("/".join(dirs)) if dirs else None
        order[pkey] = order.get(pkey, 0) + 1
        key = menu_key(cfg.sys_code, "S:" + s["screenId"])
        m.menus[key] = {"key": key, "parentKey": pkey, "name": s.get("name") or s["screenId"], "order": order[pkey],
                        "screenKey": s["key"]}
        made += 1
    return made
