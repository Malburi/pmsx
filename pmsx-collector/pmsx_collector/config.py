"""수집 설정(collector.yaml) 로딩. 상대 경로는 설정 파일 위치 기준으로 해석한다."""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

DEFAULT_DANGER_LABELS = ["삭제", "저장", "등록", "승인", "결재", "전송", "발송", "확정", "마감"]
DEFAULT_DANGER_IDS = ["del", "save", "apprv", "confirm"]


@dataclass
class Config:
    path: Path
    raw: dict[str, Any]
    base: Path = field(init=False)

    def __post_init__(self):
        self.base = self.path.parent.resolve()

    # ---------- 공통 ----------
    def get(self, dotted: str, default=None):
        cur: Any = self.raw
        for part in dotted.split("."):
            if not isinstance(cur, dict) or part not in cur:
                return default
            cur = cur[part]
        return cur

    def resolve(self, rel: str | None) -> Path | None:
        if not rel:
            return None
        p = Path(rel)
        return p if p.is_absolute() else (self.base / p).resolve()

    # ---------- 시스템 ----------
    @property
    def sys_code(self) -> str:
        return self.get("system.code")

    @property
    def schema(self) -> str:
        return self.get("db.schema", self.sys_code)

    # ---------- 소스 ----------
    @property
    def source_root(self) -> Path:
        return self.resolve(self.get("source.root", "."))

    def source_dir(self, name: str) -> Path | None:
        return self.resolve(self.get(f"source.{name}"))

    def rel(self, p: Path) -> str:
        """키·file 값에 쓰는 소스 루트 기준 상대 경로 (슬래시 표기)."""
        try:
            return p.resolve().relative_to(self.source_root).as_posix()
        except ValueError:
            return p.as_posix()

    @property
    def include_snippets(self) -> bool:
        return bool(self.get("source.includeSnippets", True))

    @property
    def common_scripts(self) -> list[str]:
        return list(self.get("source.commonScripts", []) or [])

    @property
    def context_path(self) -> str:
        return (self.get("source.contextPath", "") or "").rstrip("/")

    # ---------- 캡처 ----------
    @property
    def danger_labels(self) -> list[str]:
        return list(self.get("capture.dangerLabels", DEFAULT_DANGER_LABELS))

    @property
    def danger_ids(self) -> list[str]:
        return [s.lower() for s in self.get("capture.dangerIds", DEFAULT_DANGER_IDS)]

    def env_value(self, spec: dict) -> str:
        """로그인 스텝 값: value | valueEnv(+default)."""
        if "value" in spec:
            return str(spec["value"])
        if "valueEnv" in spec:
            return os.environ.get(spec["valueEnv"], str(spec.get("default", "")))
        return ""


def load_config(path: str | Path) -> Config:
    p = Path(path).resolve()
    with open(p, encoding="utf-8") as f:
        raw = yaml.safe_load(f) or {}
    cfg = Config(p, raw)
    if not cfg.sys_code:
        raise ValueError(f"{p}: system.code 가 필요합니다.")
    return cfg
