"""중간모델 스키마 검증 (docs/schema/asis-model.schema.json 사본을 패키지에 포함)."""
from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path

from jsonschema import Draft202012Validator

SCHEMA_PATH = Path(__file__).parent / "schema" / "asis-model.schema.json"


@lru_cache(maxsize=1)
def _validator() -> Draft202012Validator:
    with open(SCHEMA_PATH, encoding="utf-8") as f:
        return Draft202012Validator(json.load(f))


def validate_model(model: dict) -> list[str]:
    errs = sorted(_validator().iter_errors(model), key=lambda e: list(e.absolute_path))
    return [f"{'/'.join(map(str, e.absolute_path)) or '(root)'}: {e.message}" for e in errs]


def validate_file(path: str | Path) -> list[str]:
    with open(path, encoding="utf-8") as f:
        return validate_model(json.load(f))
