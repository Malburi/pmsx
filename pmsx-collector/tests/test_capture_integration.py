"""mock 서버 + 설치된 Chrome 으로 실제 캡처까지 돌리는 통합 테스트."""
import zipfile

import pytest

from pmsx_collector.pipeline import run

from conftest import make_config

pytestmark = pytest.mark.capture


def _chrome_available() -> bool:
    try:
        from playwright.sync_api import sync_playwright
        with sync_playwright() as p:
            p.chromium.launch(channel="chrome", headless=True).close()
        return True
    except Exception:  # noqa: BLE001
        return False


@pytest.fixture(scope="module")
def chrome():
    if not _chrome_available():
        pytest.skip("설치된 Chrome 이 없어 캡처 통합 테스트 생략")


def test_capture_blocks_writes_and_packages(chrome, sample_config, mock_server, tmp_path):
    base_url, write_log = mock_server
    # 위험 버튼 규칙을 비워 모든 버튼을 누르게 해도 서버에 쓰기 요청이 도달하면 안 된다
    cfg = make_config(sample_config, **{"capture.baseUrl": base_url, "capture.dangerLabels": [], "capture.dangerIds": [],
                                        "output": str(tmp_path / "out")})
    res = run(cfg, capture=True, log=lambda *_: None)
    m = res.model
    assert res.errors == []

    # ★ 쓰기 요청은 서버에 하나도 도달하지 않음
    assert not write_log.exists() or write_log.read_text(encoding="utf-8").strip() == ""
    blocked = {b["url"].split(base_url, 1)[-1] for b in m["blockedRequests"]}
    assert "/hr/dept/sort.do" in blocked                       # 확인창 없는 쓰기 버튼 → 가로채서 차단

    st = m["manifest"]["stats"]
    assert st["screensCaptured"] == 6
    # 재로딩은 상태가 바뀐 경우에만 → 클릭 수보다 적어야 한다
    assert st["captureReloads"] < st["captureClicks"]
    assert st["captureExtraEvents"] >= 3                       # change·dblclick 시도
    caps = {s["key"]: s["captures"][0] for s in m["screens"] if s.get("captures")}
    assert caps["scr:HR:HR0101"]["scale"] == 1
    assert st["elementMatchRate"] >= 0.9
    els = {e["key"]: e for e in m["elements"]}
    assert els["el:HR:HR0101#btnSave"]["box"]["w"] > 0

    # 동적 관측이 정적 연결을 확인 (source=dynamic)
    dyn = {(e["from"], e["to"]) for e in m["edges"] if e["source"] == "dynamic"}
    assert ("fn:js:/js/hr/HR0101.js#fn_search", "api:POST /hr/emp/list.do") in dyn
    assert ("fn:js:/js/hr/HR0101.js#fn_openEmpPopup", "scr:HR:HR0101P01") in dyn
    # 클릭 외 이벤트: 그리드 행 더블클릭 → 상세 팝업, 콤보 change → 재조회
    assert ("fn:js:/js/hr/HR0105.js#HR0105.openDetail", "scr:HR:HR0102") in dyn
    assert ("fn:js:/js/hr/HR0101.js#fn_grdDblClick", "scr:HR:HR0102") in dyn
    assert ("fn:js:/js/hr/HR0105.js#HR0105.search", "api:POST /hr/edu/list.do") in dyn

    # 메뉴에는 있으나 소스·화면 없는 HR0201 은 실패로 기록
    assert any(f["stage"] == "capture" and "HR0201" in f["target"] for f in m["failures"])

    with zipfile.ZipFile(res.zip_path) as z:
        names = set(z.namelist())
    assert {"model.json", "report.html", "captures/HR0101.png"} <= names


def test_deny_host_refuses_capture(chrome, sample_config, tmp_path):
    cfg = make_config(sample_config, **{"capture.baseUrl": "http://hr.example.go.kr", "output": str(tmp_path / "out")})
    res = run(cfg, capture=True, log=lambda *_: None)
    assert any("운영계" in f["message"] for f in res.model["failures"])
    assert res.model["manifest"]["stats"]["screensCaptured"] == 0
