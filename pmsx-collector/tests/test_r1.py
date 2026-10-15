"""R1 수집기 — Nexacro · Vue/React · SQL/SP 고도화 · harness 연계 · 메뉴 소스 · 와이어프레임."""
import importlib.util
import threading
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest

from pmsx_collector.config import Config, load_config
from pmsx_collector.model import ModelBuilder
from pmsx_collector.parsers import Context, Route, path_match
from pmsx_collector.parsers.ddl import link_routine_calls, register_routine
from pmsx_collector.parsers.mybatis import flatten, to_plain_sql
from pmsx_collector.parsers.nexacro import comp_box, preprocess_script
from pmsx_collector.pipeline import run

from conftest import ROOT, SAMPLE, make_config

SAMPLES = ROOT.parent / "samples"
HARNESS_FIX = ROOT / "tests" / "fixtures" / "harness_hr"


def _edges(model):
    out = {}
    for e in model["edges"]:
        out.setdefault(e["from"], []).append(e)
    return out


def _reach(model, start, depth=10):
    """start 에서 도달하는 노드 집합."""
    E = _edges(model)
    seen, frontier = set(), [start]
    for _ in range(depth):
        nxt = []
        for f in frontier:
            for e in E.get(f, []):
                if e["to"] not in seen:
                    seen.add(e["to"])
                    nxt.append(e["to"])
        frontier = nxt
    return seen


# ---------------------------------------------------------------- Nexacro
@pytest.fixture(scope="module")
def nx_model(tmp_path_factory):
    cfg = make_config(load_config(SAMPLES / "hr-nexacro" / "collector.yaml"), output=str(tmp_path_factory.mktemp("nx")))
    res = run(cfg, capture=False, log=lambda *_: None)
    assert res.errors == [], res.errors
    return res


def test_nexacro_script_preprocess_strips_types_and_includes():
    src = 'include "Lib::lib_comm.xjs";\nthis.f = function(obj:nexacro.Button, e:nexacro.ClickEventInfo) { var a:Number = 1; };'
    out, inc = preprocess_script(src)
    assert inc == ["Lib::lib_comm.xjs"]
    assert ":nexacro" not in out and ":Number" not in out
    assert out.count("\n") == src.count("\n") and len(out.splitlines()[1]) == len(src.splitlines()[1])   # 위치 보존


def test_nexacro_coordinates_both_formats():
    n = ET.fromstring('<Button left="20" top="10" right="20" height="30"/>')
    assert comp_box(n, 1000, 500) == (20, 10, 960, 30)
    p = ET.fromstring('<Button position="absolute 15 50 585 390"/>')
    assert comp_box(p, 600, 450) == (15, 50, 570, 340)
    b = ET.fromstring('<Grid left="20" top="142" right="20" bottom="70"/>')
    assert comp_box(b, 1280, 720) == (20, 142, 1240, 508)


def test_nexacro_screens_elements_and_container_offsets(nx_model):
    m = nx_model.model
    scr = {s["screenId"]: s for s in m["screens"]}
    assert set(scr) == {"PY0101", "PY0101P01", "PY0102", "PY0103"}
    assert scr["PY0101P01"]["kind"] == "popup" and scr["PY0101"]["url"] == "PY::PY0101.xfdl"
    els = {e["key"]: e for e in m["elements"]}
    cal = els["el:PY:PY0101#div_search.cal_payYm"]
    assert cal["box"]["x"] == 105 and cal["box"]["y"] == 63          # Div(20,50) + (85,13)
    assert cal["label"] == "귀속년월" and cal["binding"] == "ds_search.PAY_YM" and cal["type"] == "date"
    grid = els["el:PY:PY0101#grd_list"]
    assert grid["binding"].startswith("ds_list: EMP_NO, EMP_NM") and grid["label"] == "급여 대상자"
    # Tab 안 그리드: Tab(20,142) + 헤더 26 + (10,10)
    tg = els["el:PY:PY0102#tab_detail.tpg_allow.grd_allow"]
    assert (tg["box"]["x"], tg["box"]["y"]) == (30, 178)
    assert els["el:PY:PY0101#div_search.btn_empPop"]["type"] == "popup"


def test_nexacro_transaction_to_sp_flow(nx_model):
    m = nx_model.model
    E = _edges(m)
    h = E["el:PY:PY0101#btn_calc"][0]["to"]
    req = next(e for e in E[h] if e["kind"] == "request")
    assert req["to"] == "api:POST /py/calc/calculate.do" and "급여를 계산" in req["condition"]
    reach = _reach(m, "el:PY:PY0101#btn_calc")
    assert {"sp:PY.SP_PAY_CALC", "sp:PY.SP_PAY_TAX", "tbl:PY.TB_TAX_RATE"} <= reach      # SP → SP → 테이블
    assert any(e["to"] == "sp:PY.SP_PAY_TAX" and e["kind"] == "calls" for e in E["sp:PY.SP_PAY_CALC"])
    # 공통 래퍼 안의 transaction 은 URL 변수라 실패로 기록하지 않음, 콜백은 calls(조건=콜백)
    assert any(e["condition"] == "콜백" for e in E[h] if e["kind"] == "calls")
    # Nexacro 14 형식 팝업: function 선언 + gfn_transaction
    p = _reach(m, "el:PY:PY0101P01#btn_find")
    assert "api:POST /py/cmm/selectEmpPopList.do" in p
    # 화면 onload → 공통코드 조회
    assert "api:POST /py/cmm/selectCode.do" in _reach(m, "scr:PY:PY0103")


def test_nexacro_menu_dataset_and_wireframes(nx_model):
    m = nx_model.model
    menus = {x["key"]: x for x in m["menus"]}
    assert menus["menu:PY:PY11"]["screenKey"] == "scr:PY:PY0101" and menus["menu:PY:PY11"]["parentKey"] == "menu:PY:PY10"
    assert any(f["target"] == "menu:PY:PY22" for f in m["failures"])                # PY0104 소스 없음
    for s in m["screens"]:
        cap = s["captures"][0]
        assert cap["state"] == "wireframe"
        png = nx_model.out_dir / cap["file"]
        assert png.is_file() and png.read_bytes()[:8] == b"\x89PNG\r\n\x1a\n"
    assert m["manifest"]["stats"]["screensWireframe"] == 4


# ---------------------------------------------------------------- SPA (Vue / React)
@pytest.fixture(scope="module")
def spa_model(tmp_path_factory):
    cfg = make_config(load_config(SAMPLES / "hr-spa" / "collector.yaml"), output=str(tmp_path_factory.mktemp("spa")))
    res = run(cfg, capture=False, log=lambda *_: None)
    assert res.errors == [], res.errors
    return res.model


def test_spa_routes_menus_and_screens(spa_model):
    m = spa_model
    scr = {s["screenId"]: s for s in m["screens"]}
    assert scr["ED0101"]["url"] == "/edu/courses" and scr["ED0102"]["url"] == "/edu/apply"   # lazy import 라우트 포함
    assert scr["ED0301"]["uiTech"] == "react"
    assert scr["EduCourseDialog"]["kind"] == "popup" and scr["CertDialog"]["kind"] == "popup"
    menus = {x["name"]: x for x in m["menus"]}
    assert menus["교육과정 관리"]["parentKey"] == menus["교육관리"]["key"]
    assert menus["자격증 관리"]["screenKey"] == "scr:ED:ED0301"


def test_spa_vue_flow_through_api_module_and_template_urls(spa_model):
    m = spa_model
    E = _edges(spa_model)
    # 조회 버튼 → search → eduApi.getCoursesApi → GET /api/edu/courses (axios baseURL '/api' + 상수)
    assert "api:GET /api/edu/courses" in _reach(m, "el:ED:ED0101#btnSearch", 3)
    # 템플릿 리터럴 경로 → 서버 {courseId} 매핑과 연결, 메서드별 핸들러만
    put = E["api:PUT /api/edu/courses/{courseId}"]
    assert [e["to"].split("#")[-1] for e in put if e["kind"] == "handles"] == ["CourseRestController.update"]
    assert "tbl:ED.TB_EDU_COURSE" in _reach(m, "el:ED:EduCourseDialog#dlgBtnSave")
    # 대화상자 열기(dialogVisible.value = true) → 팝업 화면 이동
    assert "scr:ED:EduCourseDialog" in _reach(m, "el:ED:ED0101#btnNew", 2)
    # router.push({ name }) 인라인 핸들러
    assert "scr:ED:ED0102" in _reach(m, "el:ED:ED0101#btnGoApply", 2)
    # 기본 내보내기 객체 API(codeApi.getDeptList) + onMounted → 화면 onload
    assert "api:GET /api/cmm/depts" in _reach(m, "scr:ED:ED0101", 4)
    # 그리드 행 더블클릭은 그리드 요소의 dblclick 이벤트
    assert any(e.get("event") == "dblclick" for e in E["el:ED:ED0101#grdCourse"])
    # 문자열 결합 경로 'a' + id + '/cancel', fetch()
    assert "api:POST /api/edu/applies/{applyId}/cancel" in {a["key"] for a in m["apis"]}
    assert "api:GET /api/stat/edu" in _reach(m, "el:ED:ED0201#btnStatSearch", 3)


def test_spa_react_static(spa_model):
    m = spa_model
    assert "api:GET /api/cert/certs" in _reach(m, "el:ED:ED0301#btnCertSearch", 3)
    assert "scr:ED:CertDialog" in _reach(m, "el:ED:ED0301#btnCertNew", 2)       # setOpen(true)
    assert "api:GET /api/cert/certs" in _reach(m, "scr:ED:ED0301", 4)            # useEffect(…, [])


def test_path_match_and_route_template():
    assert path_match("/api/edu/courses/{courseId}", "/api/edu/courses/C1")
    assert path_match("/a/{x}/b", "/a/{y}/b") and not path_match("/a/{x}", "/a/1/2")
    cfg = Config(Path("x.yaml"), {"system": {"code": "T"}})
    ctx = Context(cfg, ModelBuilder("T", "t", [], [], None))
    ctx.routes = [Route("/api/x/excel", ["GET"], "fn:a", None, "api"), Route("/api/x/{id}", ["GET"], "fn:b", None, "api")]
    assert [r.handler_key for r in ctx.route_for("/api/x/excel")] == ["fn:a"]
    assert [r.handler_key for r in ctx.route_for("/api/x/7")] == ["fn:b"]


# ---------------------------------------------------------------- SQL / SP
def test_mybatis_choose_takes_when_and_cross_mapper_include():
    xml = """<mapper namespace="a"><select id="s">SELECT 1 FROM T ORDER BY <choose><when test="x">${col}</when><otherwise>A</otherwise></choose></select></mapper>"""
    doc = ET.fromstring(xml)
    assert "col" in to_plain_sql(flatten(doc.find("select"), {}))
    other = ET.fromstring('<mapper namespace="ed.common"><sql id="head">SELECT * FROM (</sql></mapper>')
    frags = {"ed.common.head": other.find("sql")}
    use = ET.fromstring('<select id="x"><include refid="ed.common.head"/> SELECT A FROM T)</select>')
    assert to_plain_sql(flatten(use, frags)).startswith("SELECT * FROM (")


def test_dollar_substitution_warning_and_routine_calls(tmp_path):
    cfg = load_config(SAMPLES / "hr-nexacro" / "collector.yaml")
    res = run(make_config(cfg, output=str(tmp_path)), capture=False, log=lambda *_: None)
    report = (res.out_dir / "report.html").read_text(encoding="utf-8")
    assert "sortCol" in report and "SQL 인젝션" in report                     # ${sortCol} 경고(실패 아님)
    assert "PKG_PAY_LOG.WRITE_LOG" in report
    assert not any("sortCol" in f["message"] for f in res.model["failures"])


def test_tsql_exec_links_procedures():
    cfg = Config(Path("x.yaml"), {"system": {"code": "T"}, "source": {"includeSnippets": False}})
    m = ModelBuilder("T", "t", [], [], "mssql")
    ctx = Context(cfg, m)
    register_routine(ctx, "db", "PROCEDURE", "usp_a", None,
                     "CREATE PROCEDURE dbo.usp_a AS\nBEGIN\n  UPDATE TB_X SET A = 1\n  EXEC dbo.usp_b @p = 1\n  EXECUTE [dbo].[usp_c]\n  EXEC ext.usp_unknown\nEND", "tsql")
    register_routine(ctx, "db", "PROCEDURE", "usp_b", None, "CREATE PROCEDURE dbo.usp_b AS\nSELECT 1 FROM TB_Y", "tsql")
    register_routine(ctx, "db", "PROCEDURE", "usp_c", None, "CREATE PROCEDURE dbo.usp_c AS\nDELETE FROM TB_Z", "tsql")
    link_routine_calls(ctx)
    calls = sorted(e["to"] for e in m.edges_from("sp:T.USP_A", "calls"))
    assert calls == ["sp:T.USP_B", "sp:T.USP_C"]
    assert any("ext.usp_unknown" in w["message"] for w in m.warnings)


# ---------------------------------------------------------------- harness
@pytest.mark.parametrize("mode", ["fill", "union"])
def test_harness_merge(sample_config, tmp_path, mode):
    base = run(make_config(sample_config, output=str(tmp_path / "base")), capture=False, log=lambda *_: None).model
    cfg = make_config(sample_config, output=str(tmp_path / mode),
                      **{"harness.workspace": str(HARNESS_FIX / "_workspace"), "harness.root": str(SAMPLE), "harness.mode": mode})
    res = run(cfg, capture=False, log=lambda *_: None)
    assert res.errors == [], res.errors
    m = res.model
    assert any(p.startswith("harness(") for p in m["manifest"]["parsers"])
    base_edges = {(e["from"], e["to"], e["kind"]) for e in base["edges"]}
    edges = {(e["from"], e["to"], e["kind"]) for e in m["edges"]}
    assert base_edges <= edges                                     # 우리 분석은 그대로 유지
    merged = [e for e in m["edges"] if "harness:" in (e.get("evidence") or "")]
    assert merged                                                  # 겹치는 연결에 harness 근거가 붙음
    if mode == "union":
        assert len(edges) >= len(base_edges)


def test_harness_live_indexer_if_available(tmp_path):
    """설치된 harness 인덱서가 있으면 실제로 돌려 형식 호환을 확인한다 (없으면 건너뜀)."""
    import shutil
    import subprocess
    idx = Path.home() / ".claude/plugins/marketplaces/total-ito/agents/lib/build-index.mjs"
    if not idx.is_file() or not shutil.which("node"):
        pytest.skip("harness 인덱서 없음")
    work = tmp_path / "hr"
    shutil.copytree(SAMPLE / "src", work / "src")
    shutil.copytree(SAMPLE / "db", work / "db")
    subprocess.run(["node", str(idx), "--root", str(work), "--mode", "init"], check=True, capture_output=True, timeout=120)
    assert (work / "_workspace/index/call_graph.json").is_file()


# ---------------------------------------------------------------- SPA 동적 캡처
@pytest.fixture()
def spa_server(tmp_path):
    if not (SAMPLES / "hr-spa" / "web" / "dist" / "index.html").is_file():
        pytest.skip("hr-spa/web/dist 없음 (npm run build 필요)")
    spec = importlib.util.spec_from_file_location("spa_mock", SAMPLES / "hr-spa" / "mock_server.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    log = tmp_path / "writes.log"
    httpd = mod.serve(0, log)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}", log
    httpd.shutdown()
    httpd.server_close()


def test_spa_capture_routes_blocking_and_template_merge(spa_server, tmp_path):
    base, log = spa_server
    cfg = make_config(load_config(SAMPLES / "hr-spa" / "collector.yaml"), output=str(tmp_path), **{"capture.baseUrl": base})
    res = run(cfg, capture=True, log=lambda *_: None)
    assert res.errors == [], res.errors
    m = res.model
    assert not log.exists() or log.read_text(encoding="utf-8").strip() == ""     # 쓰기 요청이 서버에 도달하지 않음
    assert any(b["method"] == "POST" and b["url"].endswith("/api/edu/applies") for b in m["blockedRequests"])
    els = {e["key"]: e for e in m["elements"]}
    assert els["el:ED:ED0101#btnSearch"]["matched"] == "full"
    assert not any("lnk" in k for k in els)                                         # 공통 레이아웃 제외
    apis = {a["key"] for a in m["apis"]}
    assert not any("C2026" in a for a in apis)                                        # 관측 경로 → {courseId} 로 합침
    # 화면 이동 클릭(과정 목록)은 이동한 화면의 onload 요청을 자기 것으로 기록하지 않음
    E = _edges(m)
    go = [e for e in E.get("fn:js:web/src/views/edu/EduApply.vue#goList", []) if e["kind"] == "request"]
    assert go == []
    assert any(e["source"] == "dynamic" and e["to"] == "scr:ED:ED0102" for e in E.get("fn:js:web/src/views/edu/EduCourseList.vue#inline@L21", []))
