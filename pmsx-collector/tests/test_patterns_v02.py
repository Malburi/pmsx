"""Wave A(v0.2) 보완 패턴 — JS 함수 표현식·객체 메서드·선택자·위임, Spring 생성자 주입, iBatis DAO, 동명 Mapper."""
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest

from pmsx_collector.config import load_config
from pmsx_collector.parsers.jsp_jquery import match_selector
from pmsx_collector.parsers.mybatis import flatten, to_plain_sql
from pmsx_collector.pipeline import run

from conftest import make_config

FIXTURES = Path(__file__).parent / "fixtures"
JS = "fn:js:/js/hr/HR0105.js#"
EDU = "fn:java:src/main/java/kr/go/hr/edu/"


@pytest.fixture(scope="module")
def model(sample_config, tmp_path_factory):
    cfg = make_config(sample_config, output=str(tmp_path_factory.mktemp("out")))
    res = run(cfg, capture=False, log=lambda *_: None)
    assert res.errors == [], res.errors
    return res.model


def _to(model, frm, kind=None):
    return [e["to"] for e in model["edges"] if e["from"] == frm and (kind is None or e["kind"] == kind)]


def test_model_version_and_namespace(model):
    assert model["manifest"]["modelVersion"] == "0.2"
    sql = {s["key"]: s for s in model["sqlStatements"]}
    assert sql["sql:EmpMapper.insertEmp"]["namespace"] == "kr.go.hr.emp.service.impl.EmpMapper"
    assert sql["sql:edu.selectEduList"]["namespace"] == "edu"          # iBatis sqlMap namespace


def test_function_expressions_and_object_methods(model):
    codes = {c["key"] for c in model["codeUnits"]}
    # var 객체 리터럴의 pair 메서드 / 축약 메서드, var·const·let 함수 표현식
    assert {JS + "HR0105.init", JS + "HR0105.search", JS + "HR0105.save", JS + "HR0105.openDetail",
            JS + "fn_reset", JS + "fn_eduStat", JS + "fn_eduExcel"} <= codes
    assert JS + "HR0105.search" in _to(model, JS + "HR0105.init", "calls")          # this.search()
    assert JS + "HR0105.search" in _to(model, JS + "fn_reset", "calls")             # HR0105.search()
    assert JS + "HR0105.search" in _to(model, JS + "HR0105.save", "calls")          # 콜백 인자 HR0105.search
    assert "api:POST /hr/edu/summary.do" in _to(model, JS + "fn_eduStat", "request")  # 화살표 함수 본문


def test_selector_styles_and_delegation(model):
    els = {e["key"]: e for e in model["elements"] if e["screenKey"] == "scr:HR:HR0105"}
    by_label = {e["label"]: k for k, e in els.items()}
    assert _to(model, "el:HR:HR0105#btnEduSearch", "event") == [JS + "HR0105.search"]   # $(document).on('click', '#id')
    assert _to(model, by_label["엑셀"], "event") == [JS + "fn_eduExcel"]                 # .btn-excel
    assert _to(model, by_label["초기화"], "event") == [JS + "fn_reset"]                  # button[data-act="reset"]
    assert _to(model, "el:HR:HR0105#name:eduYear", "event") == [JS + "HR0105.search"]   # input[name="eduYear"]
    evs = {(e["from"], e.get("event")) for e in model["edges"] if e["kind"] == "event"}
    assert ("el:HR:HR0105#cboEduType", "change") in evs
    assert ("el:HR:HR0105#grdEdu", "dblclick") in evs                                   # 'tbody tr' 위임 → 그리드
    assert "scr:HR:HR0102" in _to(model, JS + "HR0105.openDetail", "navigates")


def test_constructor_injection_and_ibatis_dao(model):
    ctrl = EDU + "web/EduController.java#EduController.selectEduList"
    impl = EDU + "service/impl/EduServiceImpl.java#EduServiceImpl."
    dao = EDU + "service/impl/EduDAO.java#EduDAO."
    assert _to(model, ctrl, "calls") == [impl + "selectEduList"]                 # 생성자 주입 final 필드
    assert _to(model, impl + "selectEduList", "calls") == [dao + "selectEduList"]  # @RequiredArgsConstructor
    assert _to(model, dao + "selectEduList", "executes") == ["sql:edu.selectEduList"]          # list("edu.x")
    assert _to(model, dao + "selectEduSummary", "executes") == ["sql:edu.selectEduSummary"]    # getSqlMapClientTemplate()
    save = set(_to(model, impl + "saveEdu"))
    assert {"sql:EmpMapper.selectEmp", dao + "insertEdu", dao + "updateEdu"} <= save           # MyBatis Mapper 혼용
    crud = {e["to"]: e.get("crud") for e in model["edges"] if e["from"] == "sql:edu.selectEduList"}
    assert crud == {"tbl:HR.TB_EDU_HIST": "R", "tbl:HR.TB_EMP": "R", "tbl:HR.TB_CODE": "R"}


def test_ibatis_dynamic_prepend():
    xml = """<sqlMap namespace="x"><select id="s">SELECT A FROM T
      <dynamic prepend="WHERE"><isNotEmpty prepend="AND" property="a">A = #a#</isNotEmpty>
      <isNotEmpty prepend="AND" property="b">B = #b#</isNotEmpty></dynamic></select></sqlMap>"""
    sel = ET.fromstring(xml).find("select")
    assert " ".join(to_plain_sql(flatten(sel, {})).split()) == "SELECT A FROM T WHERE A = :a AND B = :b"


def test_match_selector_unit():
    els = [{"tag": "button", "id": "", "cls": ["btn", "btn-excel"], "attrs": {"class": "btn btn-excel", "type": "button"}},
           {"tag": "input", "id": "x", "cls": [], "attrs": {"name": "eduYear", "type": "text"}},
           {"tag": "button", "id": "b2", "cls": [], "attrs": {"data-act": "reset"}}]
    assert match_selector(".btn-excel", els) == [els[0]]
    assert match_selector("#frm input[name='eduYear']", els) == [els[1]]
    assert match_selector('button[data-act="reset"]', els) == [els[2]]
    assert match_selector("button[data-act^=res], .btn", els) == [els[2], els[0]]
    assert match_selector("tbody tr", els) == []
    assert match_selector("div.btn-excel", els) == []


def test_same_name_mapper_keys_use_full_namespace(tmp_path):
    cfg = load_config(FIXTURES / "mapper_collision" / "collector.yaml")
    cfg.raw["output"] = str(tmp_path)
    res = run(cfg, capture=False, log=lambda *_: None)
    assert res.errors == []
    m = res.model
    sql = {s["key"]: s["namespace"] for s in m["sqlStatements"]}
    assert sql == {"sql:kr.go.a.emp.EmpMapper.selectName": "kr.go.a.emp.EmpMapper",
                   "sql:kr.go.b.emp.EmpMapper.selectName": "kr.go.b.emp.EmpMapper"}
    a = "fn:java:java/kr/go/a/svc/AService.java#AService.run"
    b = "fn:java:java/kr/go/b/emp/BService.java#BService.run"
    assert _to(m, a, "executes") == ["sql:kr.go.a.emp.EmpMapper.selectName"]   # import 로 해석
    assert _to(m, b, "executes") == ["sql:kr.go.b.emp.EmpMapper.selectName"]   # 같은 패키지로 해석
    assert _to(m, "sql:kr.go.b.emp.EmpMapper.selectName", "accesses") == ["tbl:T.TB_B_EMP"]
