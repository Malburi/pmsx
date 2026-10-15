"""결과 패키지 — model.json + captures/*.png|jpg + report.html → zip (docs/08 §3)."""
from __future__ import annotations

import json
import zipfile
from pathlib import Path

from .config import Config
from .report import render_report


def write_package(cfg: Config, model: dict, out_dir: Path, run_id: str, warnings: list[dict] | None = None,
                  incremental: dict | None = None) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "model.json").write_text(json.dumps(model, ensure_ascii=False, indent=2), encoding="utf-8")
    (out_dir / "report.html").write_text(render_report(model, warnings or [], incremental), encoding="utf-8")
    zip_path = out_dir.parent / f"pmsx-{cfg.sys_code}-{run_id.split('-', 1)[-1]}.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(out_dir / "model.json", "model.json")
        z.write(out_dir / "report.html", "report.html")
        cap = out_dir / "captures"
        if cap.is_dir():
            for png in sorted(f for f in cap.iterdir() if f.suffix.lower() in (".png", ".jpg", ".jpeg")):
                z.write(png, f"captures/{png.name}")
    return zip_path
