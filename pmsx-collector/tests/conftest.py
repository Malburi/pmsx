import copy
import importlib.util
import threading
from pathlib import Path

import pytest

from pmsx_collector.config import Config, load_config

ROOT = Path(__file__).resolve().parents[1]
SAMPLE = ROOT.parent / "samples" / "hr-legacy"
DOCS_SCHEMA = ROOT.parent / "docs" / "schema" / "asis-model.schema.json"


@pytest.fixture(scope="session")
def sample_config() -> Config:
    return load_config(SAMPLE / "collector.yaml")


def make_config(base: Config, **overrides) -> Config:
    """샘플 설정을 복사해 일부 값만 바꾼다. 키는 'capture.baseUrl' 처럼 점 표기."""
    raw = copy.deepcopy(base.raw)
    for dotted, value in overrides.items():
        cur = raw
        parts = dotted.split(".")
        for p in parts[:-1]:
            cur = cur.setdefault(p, {})
        cur[parts[-1]] = value
    return Config(base.path, raw)


@pytest.fixture()
def mock_server(tmp_path):
    """샘플 레거시 mock 서버를 임의 포트로 띄운다. (base_url, write_log 경로)"""
    spec = importlib.util.spec_from_file_location("hr_mock_server", SAMPLE / "mock_server.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    log = tmp_path / "writes.log"
    httpd = mod.serve(0, log)
    t = threading.Thread(target=httpd.serve_forever, daemon=True)
    t.start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}", log
    httpd.shutdown()
    httpd.server_close()
