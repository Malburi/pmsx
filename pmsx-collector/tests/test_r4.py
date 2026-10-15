"""R4 현장 투입 편의 기능 — 증분 수집 · init · doctor · upload · 리포트."""
from __future__ import annotations

import copy
import importlib.util
import json
import os
import shutil
import threading
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest
import yaml

from pmsx_collector.cli import main as cli_main
from pmsx_collector.config import Config, load_config
from pmsx_collector.doctor import FAIL, OK, SKIP, WARN, run_doctor
from pmsx_collector.incremental import State
from pmsx_collector.pipeline import run
from pmsx_collector.report import _Graph, render_report
from pmsx_collector.scaffold import detect, guess_submission_callers, guess_wrappers, init_config
from pmsx_collector.upload import UploadError, upload

from conftest import ROOT

SAMPLES = ROOT.parent / "samples"
ALL = ["hr-legacy", "hr-nexacro", "hr-spa", "hr-websquare", "hr-xplatform", "hr-aspnet", "hr-winforms"]
QUIET = lambda *_: None  # noqa: E731


def _copy_sample(name: str, dst: Path) -> Path:
    work = dst / name
    shutil.copytree(SAMPLES / name, work, ignore=shutil.ignore_patterns("out", "__pycache__", "writes.log", "node_modules"))
    return work


def _with(cfg: Config, **overrides) -> Config:
    raw = copy.deepcopy(cfg.raw)
    for dotted, value in overrides.items():
        cur = raw
        parts = dotted.split(".")
        for p in parts[:-1]:
            cur = cur.setdefault(p, {})
        cur[parts[-1]] = value
    return Config(cfg.path, raw)


# ================================================================ init


@pytest.mark.parametrize("name", ALL)
def test_init_detects_same_tech_as_hand_config(name, tmp_path):
    hand = load_config(SAMPLES / name / "collector.yaml")
    out, d = init_config(SAMPLES / name, tmp_path / "collector.yaml", code=hand.sys_code, name=hand.get("system.name"))
    gen = load_config(out)
    assert set(gen.get("system.uiTech")) == set(hand.get("system.uiTech"))
    assert set(gen.get("system.serverTech")) == set(hand.get("system.serverTech"))
    assert gen.get("system.dbType") == hand.get("system.dbType")
    assert gen.get("capture.enabled") is False                 # 캡처는 사람이 확인한 뒤 켠다
    text = out.read_text(encoding="utf-8")
    assert "# 감지 근거" in text and "[화면]" in text
    # 생성한 설정으로 정적 분석한 결과가 손으로 쓴 설정과 같아야 한다
    rh = run(_with(hand, output=str(tmp_path / "hand")), capture=False, log=QUIET)
    rg = run(gen, capture=False, log=QUIET)
    assert rg.errors == []
    keys = ["screens", "elements", "buttonsWithHandler", "buttonsReachingData", "edges", "tables", "menus"]
    sh, sg = rh.model["manifest"]["stats"], rg.model["manifest"]["stats"]
    assert {k: sg[k] for k in keys} == {k: sh[k] for k in keys}


def test_init_refuses_overwrite(tmp_path):
    out = tmp_path / "collector.yaml"
    out.write_text("x", encoding="utf-8")
    with pytest.raises(FileExistsError):
        init_config(SAMPLES / "hr-legacy", out)
    assert cli_main(["init", str(SAMPLES / "hr-legacy"), "-o", str(out)]) == 2
    assert cli_main(["init", str(SAMPLES / "hr-legacy"), "-o", str(out), "--force", "--code", "HR"]) == 0
    assert yaml.safe_load(out.read_text(encoding="utf-8"))["system"]["code"] == "HR"


def test_detect_wrappers_and_legacy_signals():
    js = """
function gfn_ajax(url, data, callback) { $.ajax({ url: url, type: 'POST', data: data }); }
function gfn_list(svcUrl, param) { gfn_ajax(svcUrl, param, null); }
function gfn_get(sel, url) { $.get(url, {}, function(){}); }
function gfn_popup(url, w, h) { return window.open(url, 'p'); }
this.gfn_tran = function(id, url, inDs, outDs, args, callback) { this.transaction(id, url, inDs, outDs, args, callback); };
this.gfn_pop = function(pid, formUrl, args, callback) { var c = new ChildFrame(pid, "absolute", 0, 0, 1, 1, null, null, formUrl); c.showModal(this, args, this, callback); };
gcm.exec = function(sbmId, opts) { $p.executeSubmission(sbmId); };
"""
    apis, navs = guess_wrappers([js])
    assert apis["gfn_ajax"] == {"urlArg": 0, "method": "POST"}
    assert apis["gfn_list"] == {"urlArg": 0, "method": "POST"}          # 래퍼를 부르는 래퍼 (전파)
    assert apis["gfn_get"] == {"urlArg": 1, "method": "GET"}
    assert apis["gfn_tran"] == {"urlArg": 1, "method": "POST", "callbackArg": 5}
    assert navs["gfn_popup"] == {"urlArg": 0}
    assert navs["gfn_pop"] == {"urlArg": 1, "popup": True, "callbackArg": 3}
    assert guess_submission_callers([js]) == {"gcm.exec": 0}


def test_detect_nexacro14_files_stay_with_nexacro():
    d = detect(SAMPLES / "hr-nexacro")                        # PY0101P01 은 FDL 1.5(Nexacro 14) 형식
    assert list(d.ui) == ["nexacro"] and "xplatform" not in d.ui
    d2 = detect(SAMPLES / "hr-xplatform")
    assert set(d2.ui) == {"xplatform", "miplatform"} and d2.front_navs["Dialog"]["popup"] is True


# ================================================================ 증분 수집


def test_incremental_static_reuse_and_partial(tmp_path):
    work = _copy_sample("hr-nexacro", tmp_path)
    cfg = load_config(work / "collector.yaml")
    full = run(cfg, capture=False, log=QUIET)
    out_root = work / "out"
    s1 = State.load(out_root)
    assert s1 and s1.run_id == full.run_id and set(s1.screens) == {s["key"] for s in full.model["screens"]}

    # 변경 없음 → 이전 결과 재사용 (모델 내용 동일, 회차만 새로)
    again = run(cfg, capture=False, log=QUIET, incremental=True)
    st = again.model["manifest"]["stats"]
    assert st["incremental"] == 1 and st["incrementalChangedFiles"] == 0
    assert again.run_id != full.run_id and again.errors == []
    strip = lambda m: json.loads(json.dumps({k: v for k, v in m.items() if k != "manifest"}))  # noqa: E731
    assert strip(again.model) == strip(full.model)
    with zipfile.ZipFile(again.zip_path) as z:
        assert any(n.startswith("captures/") for n in z.namelist())     # 와이어프레임 PNG 도 함께
        assert "증분 수집" in z.read("report.html").decode("utf-8")

    # 화면 1개 수정 → 그 화면 해시만 바뀐다
    f = work / "nexacro/PY/PY0102.xfdl"
    f.write_text(f.read_text(encoding="utf-8").replace("</FDL>", "<!-- 수정 -->\n</FDL>"), encoding="utf-8")
    part = run(cfg, capture=False, log=QUIET, incremental=True)
    assert part.model["manifest"]["stats"]["incrementalChangedFiles"] == 1
    s3 = State.load(out_root)
    changed = {k for k in s3.screens if s3.screens[k]["hash"] != s1.screens[k]["hash"]}
    assert changed == {"scr:PY:PY0102"}

    # 설정이 바뀌면 전체 수집
    cfg2 = _with(cfg, **{"inference.minEvidence": 2})
    full2 = run(cfg2, capture=False, log=QUIET, incremental=True)
    assert full2.model["manifest"]["stats"]["incremental"] == 0


@pytest.mark.capture
def test_incremental_recaptures_only_changed_screen(tmp_path):
    from test_capture_integration import _chrome_available
    if not _chrome_available():
        pytest.skip("설치된 Chrome 이 없어 생략")
    work = _copy_sample("hr-legacy", tmp_path)
    spec = importlib.util.spec_from_file_location("hr_mock_copy", work / "mock_server.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    log = tmp_path / "writes.log"
    httpd = mod.serve(0, log)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    try:
        cfg = _with(load_config(work / "collector.yaml"), **{"capture.baseUrl": f"http://127.0.0.1:{httpd.server_address[1]}"})
        full = run(cfg, capture=True, log=QUIET)
        js = work / "src/main/webapp/js/hr/HR0103.js"
        js.write_text(js.read_text(encoding="utf-8") + "\n// 수정\n", encoding="utf-8")
        part = run(cfg, capture=True, log=QUIET, incremental=True)
    finally:
        httpd.shutdown()
        httpd.server_close()
    st, st0 = part.model["manifest"]["stats"], full.model["manifest"]["stats"]
    assert st["incrementalScreensRecaptured"] == 1 and st["incrementalScreensReused"] == st0["screensCaptured"] - 1
    # 재사용한 화면의 동적 관측·차단 기록도 그대로 남는다
    for k in ("screensCaptured", "elements", "elementsMatchedFull", "edgesDynamic", "blockedRequests"):
        assert st[k] == st0[k], k
    assert st["durationSec"] < st0["durationSec"]
    with zipfile.ZipFile(part.zip_path) as z:
        assert {"captures/HR0101.png", "captures/HR0103.png"} <= set(z.namelist())
    assert not log.exists() or log.read_text(encoding="utf-8").strip() == ""


# ================================================================ doctor


class _Ok(BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, *a):
        pass


def test_doctor_reports_paths_output_target_and_db(tmp_path):
    base = load_config(SAMPLES / "hr-legacy" / "collector.yaml")
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), _Ok)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    try:
        cfg = _with(base, output=str(tmp_path / "out"), **{"capture.baseUrl": f"http://127.0.0.1:{httpd.server_address[1]}",
                                                         "source.jsp": "no/such/dir"})
        checks = run_doctor(cfg, skip={"browser"})
    finally:
        httpd.shutdown()
        httpd.server_close()
    by = {(c.item, c.status) for c in checks}
    assert ("source.jsp", FAIL) in by and ("source.java", OK) in by
    assert ("출력 폴더 쓰기", OK) in by
    assert ("대상 URL 응답", OK) in by and ("운영계 차단", OK) in by
    assert any(c.item == "DB 접속" and c.status == SKIP for c in checks)
    assert not list((tmp_path / "out").glob(".doctor-*"))           # 쓰기 점검 파일은 지운다

    deny = _with(base, output=str(tmp_path / "o2"), **{"capture.baseUrl": "http://hr.example.go.kr"})
    assert any(c.item == "운영계 차단" and c.status == FAIL for c in run_doctor(deny, skip={"browser"}))

    db = _with(base, output=str(tmp_path / "o3"), **{"db.type": "informix", "db.connection": {"host": "x"}})
    assert any(c.item == "DB 드라이버" and c.status == FAIL for c in run_doctor(db, skip={"browser", "network"}))


def test_doctor_db_timeout_and_font(tmp_path, monkeypatch):
    base = load_config(SAMPLES / "hr-nexacro" / "collector.yaml")
    cfg = _with(base, output=str(tmp_path / "out"), **{"db.type": "oracle",
                                                      "db.connection": {"host": "10.255.255.1", "port": 1521, "service": "X", "user": "u"}})
    import pmsx_collector.dbmeta as dbmeta
    import time as _t
    monkeypatch.setattr(dbmeta, "open_readonly", lambda c: _t.sleep(5))
    checks = run_doctor(cfg, skip={"browser", "network"}, db_timeout=0.5)
    assert any(c.item == "DB 접속" and c.status == FAIL and "응답 없음" in c.detail for c in checks)
    assert any(c.item == "한글 글꼴" and c.status in (OK, WARN) for c in checks)   # 와이어프레임 쓰는 기술


def test_doctor_cli_exit_code(tmp_path):
    good = tmp_path / "good.yaml"
    raw = load_config(SAMPLES / "hr-nexacro" / "collector.yaml").raw
    raw = copy.deepcopy(raw)
    raw["source"]["root"] = str(SAMPLES / "hr-nexacro")
    for k in ("nexacro", "java", "mapper"):
        raw["source"][k] = str(SAMPLES / "hr-nexacro" / raw["source"][k])
    raw["db"]["ddlFiles"] = [str(SAMPLES / "hr-nexacro" / f) for f in raw["db"]["ddlFiles"]]
    raw["menu"]["nexacroDataset"] = str(SAMPLES / "hr-nexacro" / raw["menu"]["nexacroDataset"])
    raw["output"] = str(tmp_path / "out")
    good.write_text(yaml.safe_dump(raw, allow_unicode=True), encoding="utf-8")
    assert cli_main(["doctor", "-c", str(good), "--skip", "browser,network,db"]) == 0
    raw["source"]["java"] = str(tmp_path / "missing")
    good.write_text(yaml.safe_dump(raw, allow_unicode=True), encoding="utf-8")
    assert cli_main(["doctor", "-c", str(good), "--skip", "browser,network,db"]) == 1


# ================================================================ upload


class _FakeServer(BaseHTTPRequestHandler):
    systems = {"P1": [{"systemId": "S1", "code": "HR", "name": "인사"}], "P2": []}
    received: dict = {}

    def _json(self, code, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):  # noqa: N802
        n = int(self.headers.get("Content-Length", 0))
        data = self.rfile.read(n)
        if self.path == "/api/v1/auth/login":
            body = json.loads(data)
            if body == {"loginId": "analyst", "password": "pw!"}:
                return self._json(200, {"accessToken": "TOKEN"})
            return self._json(401, {"code": "AUTH_INVALID", "message": "아이디 또는 비밀번호가 올바르지 않습니다."})
        if self.path == "/api/v1/systems/S1/runs":
            _FakeServer.received = {"auth": self.headers.get("Authorization"), "ctype": self.headers.get("Content-Type"), "data": data}
            return self._json(201, {"runId": "R1", "seq": 3, "diff": {"screensAdded": 1}})
        self._json(404, {"code": "NOT_FOUND", "message": "x"})

    def do_GET(self):  # noqa: N802
        if self.headers.get("Authorization") != "Bearer TOKEN":
            return self._json(401, {"code": "AUTH_EXPIRED", "message": "x"})
        if self.path == "/api/v1/projects":
            return self._json(200, [{"projectId": "P1", "name": "차세대"}, {"projectId": "P2", "name": "운영"}])
        if self.path.startswith("/api/v1/projects/") and self.path.endswith("/systems"):
            return self._json(200, self.systems[self.path.split("/")[4]])
        self._json(404, {"code": "NOT_FOUND", "message": "x"})

    def log_message(self, *a):
        pass


@pytest.fixture()
def fake_server():
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), _FakeServer)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown()
    httpd.server_close()


def test_upload_finds_system_and_posts_zip(fake_server, tmp_path, monkeypatch):
    pkg = tmp_path / "pmsx-HR-20261015-010101.zip"
    with zipfile.ZipFile(pkg, "w") as z:
        z.writestr("model.json", "{}")
    monkeypatch.setenv("PMSX_UPLOAD_PW", "pw!")
    res = upload(fake_server, "HR", "analyst", pkg)
    assert res["system"]["systemId"] == "S1" and res["run"]["seq"] == 3
    got = _FakeServer.received
    assert got["auth"] == "Bearer TOKEN" and got["ctype"].startswith("multipart/form-data")
    assert b'name="file"; filename="pmsx-HR-20261015-010101.zip"' in got["data"] and pkg.read_bytes() in got["data"]

    with pytest.raises(UploadError, match="시스템 코드 XX"):
        upload(fake_server, "XX", "analyst", pkg)
    monkeypatch.setenv("PMSX_UPLOAD_PW", "wrong")
    with pytest.raises(UploadError, match="AUTH_INVALID"):
        upload(fake_server, "HR", "analyst", pkg)
    monkeypatch.delenv("PMSX_UPLOAD_PW")
    with pytest.raises(UploadError, match="환경변수"):
        upload(fake_server, "HR", "analyst", pkg)


def test_upload_cli_uses_latest_package(fake_server, tmp_path, monkeypatch):
    cfg_dir = tmp_path / "proj"
    (cfg_dir / "out").mkdir(parents=True)
    (cfg_dir / "collector.yaml").write_text("system: { code: HR, name: 인사 }\noutput: out\n", encoding="utf-8")
    for i, n in enumerate(["pmsx-HR-20261015-000001.zip", "pmsx-HR-20261015-000002.zip"]):
        p = cfg_dir / "out" / n
        with zipfile.ZipFile(p, "w") as z:
            z.writestr("model.json", "{}")
        os.utime(p, (1_700_000_000 + i, 1_700_000_000 + i))
    monkeypatch.setenv("PMSX_UPLOAD_PW", "pw!")
    assert cli_main(["upload", "-c", str(cfg_dir / "collector.yaml"), "--server", fake_server, "--user", "analyst"]) == 0
    assert b"pmsx-HR-20261015-000002.zip" in _FakeServer.received["data"]
    assert cli_main(["upload", "-c", str(cfg_dir / "collector.yaml"), "--server", "http://127.0.0.1:9", "--user", "analyst"]) == 1


# ================================================================ 리포트


def test_report_screen_table_and_unlinked_causes():
    model = {
        "manifest": {"runId": "T-1", "collectedAt": "2026-10-15T00:00:00+09:00", "collectorVersion": "0.3.0",
                     "parsers": ["x"], "stats": {"screens": 1, "durationSec": 1}},
        "system": {"code": "T", "name": "테스트"},
        "screens": [{"key": "scr:T:A", "screenId": "A", "name": "화면A", "uiTech": "jsp-jquery", "url": "/a.do"}],
        "elements": [
            {"key": "el:T:A#b1", "screenKey": "scr:T:A", "elementId": "b1", "label": "조회", "type": "button", "matched": "full"},
            {"key": "el:T:A#b2", "screenKey": "scr:T:A", "elementId": "b2", "label": "인쇄", "type": "button", "matched": "full"},
            {"key": "el:T:A#b3", "screenKey": "scr:T:A", "elementId": "b3", "label": "동적", "type": "button", "matched": "dynamic-only"},
            {"key": "el:T:A#b4", "screenKey": "scr:T:A", "elementId": "b4", "label": "외부", "type": "button", "matched": "full"},
            {"key": "el:T:A#b5", "screenKey": "scr:T:A", "elementId": "b5", "label": "닫기", "type": "button", "matched": "full"},
        ],
        "codeUnits": [{"key": "fn:js:a.js#search", "lang": "js", "file": "a.js", "name": "search"},
                      {"key": "fn:js:a.js#close", "lang": "js", "file": "a.js", "name": "close"}],
        "edges": [
            {"from": "el:T:A#b1", "to": "fn:js:a.js#search", "kind": "event", "source": "static", "confidence": 1},
            {"from": "fn:js:a.js#search", "to": "api:POST /a/list.do", "kind": "request", "source": "static", "confidence": 1},
            {"from": "api:POST /a/list.do", "to": "sql:A.list", "kind": "handles", "source": "static", "confidence": 1},
            {"from": "el:T:A#b4", "to": "fn:js:lib.js#gfn_print", "kind": "event", "source": "static", "confidence": 1},
            {"from": "el:T:A#b5", "to": "fn:js:a.js#close", "kind": "event", "source": "static", "confidence": 1},
        ],
        "relations": [], "blockedRequests": [], "failures": [{"stage": "capture", "target": "/a.do", "message": "느림"}],
    }
    g = _Graph(model)
    els = {e["elementId"]: e for e in model["elements"]}
    assert g.reaches_api("el:T:A#b1") and g.reaches_data("el:T:A#b1")
    assert g.cause(els["b2"]).startswith("핸들러 없음")
    assert g.cause(els["b3"]).startswith("동적 바인딩")
    assert g.cause(els["b4"]).startswith("외부 함수") and "gfn_print" in g.cause(els["b4"])
    assert g.cause(els["b5"]).startswith("화면 내 동작")
    html = render_report(model, [], {"enabled": True, "mode": "partial", "prev_run_id": "T-0", "reason": "변경 1",
                                     "changed": {"added": [], "removed": [], "modified": ["a.js"]},
                                     "recaptured": ["scr:T:A"], "reused": []})
    assert "화면별 현황 (1)" in html and "서버까지 이어지지 않은 버튼 (4)" in html
    assert "[capture] 느림" in html and "다시 캡처한 화면 1개: A" in html
