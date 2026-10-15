import json
import xml.etree.ElementTree as ET

import pytest

from pmsx_collector.parsers.mybatis import flatten, to_plain_sql
from pmsx_collector.parsers.sql_analyzer import analyze
from pmsx_collector.pipeline import run

from conftest import DOCS_SCHEMA, make_config

# ---------------------------------------------------------------- SQL 분석


def test_select_join_crud_and_join_pairs():
    info = analyze("SELECT E.EMP_NO, D.DEPT_NM FROM TB_EMP E JOIN TB_DEPT D ON D.DEPT_CD = E.DEPT_CD WHERE E.USE_YN = 'Y'")
    assert info.tables["TB_EMP"]["crud"] == {"R"}
    assert info.tables["TB_DEPT"]["crud"] == {"R"}
    assert {"EMP_NO", "DEPT_CD", "USE_YN"} <= info.tables["TB_EMP"]["columns"]
    assert (("TB_DEPT", "DEPT_CD"), ("TB_EMP", "DEPT_CD")) in info.joins


def test_insert_select_marks_target_create_and_source_read():
    info = analyze("INSERT INTO TB_EMP_HIST (HIST_SEQ, EMP_NO) SELECT SEQ_EMP_HIST.NEXTVAL, E.EMP_NO FROM TB_EMP E WHERE E.EMP_NO = :empNo")
    assert info.tables["TB_EMP_HIST"]["crud"] == {"C"}
    assert info.tables["TB_EMP"]["crud"] == {"R"}
    assert "SEQ_EMP_HIST" not in info.tables


def test_anonymous_block_and_procedure_call():
    blk = analyze("BEGIN UPDATE TB_DEPT SET SORT_ORD = :a WHERE DEPT_CD = :b ; END;")
    assert blk.tables["TB_DEPT"]["crud"] == {"U"}
    call = analyze("{ call SP_APPT_CONFIRM( :apptNo, :userId, :resultCd ) }")
    assert call.procedures == ["SP_APPT_CONFIRM"]


def test_merge_is_create_update():
    info = analyze("MERGE INTO TB_DEPT T USING (SELECT :d AS DEPT_CD FROM DUAL) S ON (T.DEPT_CD = S.DEPT_CD) "
                   "WHEN MATCHED THEN UPDATE SET T.DEPT_NM = :n WHEN NOT MATCHED THEN INSERT (DEPT_CD, DEPT_NM) VALUES (:d, :n)")
    assert info.tables["TB_DEPT"]["crud"] >= {"C", "U"}


def test_mybatis_dynamic_sql_flatten():
    xml = """<mapper><sql id="cols">A, B</sql>
      <select id="s">SELECT <include refid="cols"/> FROM T <where><if test="x">AND A = #{a}</if></where></select>
      <update id="u">UPDATE T <set><if test="y">A = #{a},</if> B = #{row.b},</set> WHERE C = #{c, jdbcType=VARCHAR}</update>
    </mapper>"""
    doc = ET.fromstring(xml)
    frags = {s.get("id"): s for s in doc.iter("sql")}
    sel = to_plain_sql(flatten(doc.find("select"), frags))
    upd = to_plain_sql(flatten(doc.find("update"), frags))
    assert "SELECT A, B FROM T WHERE A = :a" in " ".join(sel.split())
    assert "SET A = :a, B = :row_b WHERE C = :c" in " ".join(upd.split())

# ---------------------------------------------------------------- 정적 수집 전체


@pytest.fixture(scope="module")
def static_model(sample_config, tmp_path_factory):
    cfg = make_config(sample_config, output=str(tmp_path_factory.mktemp("out")))
    res = run(cfg, capture=False, log=lambda *_: None)
    assert res.errors == [], res.errors
    return res.model


def _edges(model):
    out = {}
    for e in model["edges"]:
        out.setdefault(e["from"], []).append(e)
    return out


def test_save_button_workflow_chain(static_model):
    E = _edges(static_model)
    ev = E["el:HR:HR0101#btnSave"]
    assert [e["to"] for e in ev if e["kind"] == "event"] == ["fn:js:/js/hr/HR0101.js#fn_save"]
    req = [e for e in E["fn:js:/js/hr/HR0101.js#fn_save"] if e["kind"] == "request"]
    assert req[0]["to"] == "api:POST /hr/emp/save.do"
    assert req[0]["condition"] == 'confirm("저장하시겠습니까?")'
    ctrl = "fn:java:src/main/java/kr/go/hr/emp/web/EmpController.java#EmpController.saveEmp"
    impl = "fn:java:src/main/java/kr/go/hr/emp/service/impl/EmpServiceImpl.java#EmpServiceImpl.saveEmp"
    assert [e["to"] for e in E["api:POST /hr/emp/save.do"]] == [ctrl]
    assert [e["to"] for e in E[ctrl]] == [impl]
    sqls = {e["to"]: e for e in E[impl] if e["kind"] == "executes"}
    assert {"sql:EmpMapper.insertEmp", "sql:EmpMapper.updateEmp", "sql:EmpMapper.insertEmpHist"} <= set(sqls)
    assert sqls["sql:EmpMapper.insertEmp"]["condition"] == '"I".equals(row.getRowStatus())'
    tb = {(s, e["to"]): e["crud"] for s in ("sql:EmpMapper.insertEmp", "sql:EmpMapper.updateEmp") for e in E[s]}
    assert tb[("sql:EmpMapper.insertEmp", "tbl:HR.TB_EMP")] == "C"
    assert tb[("sql:EmpMapper.updateEmp", "tbl:HR.TB_EMP")] == "U"


def test_procedure_flow_from_confirm_button(static_model):
    E = _edges(static_model)
    assert [e["to"] for e in E["sql:AppointMapper.callApptConfirmProc"]] == ["sp:HR.SP_APPT_CONFIRM"]
    crud = {e["to"]: e["crud"] for e in E["sp:HR.SP_APPT_CONFIRM"]}
    assert crud["tbl:HR.TB_EMP"] == "U" and crud["tbl:HR.TB_EMP_HIST"] == "C" and set(crud["tbl:HR.TB_APPOINT"]) == {"R", "U"}


def test_event_binding_styles(static_model):
    E = _edges(static_model)
    to = lambda k: [e["to"] for e in E.get(k, []) if e["kind"] == "event"]  # noqa: E731
    assert to("el:HR:HR0101#btnSearch") == ["fn:js:/js/hr/HR0101.js#fn_search"]       # onclick 속성
    assert to("el:HR:HR0101#btnExcel") == ["fn:js:/js/hr/HR0101.js#fn_excel"]         # .click(function(){ fn_excel(); })
    assert to("el:HR:HR0101#txtEmpNm")[0].startswith("fn:js:/js/hr/HR0101.js#anon@L")  # 조건 있는 익명 핸들러
    assert to("el:HR:HR0104#btnApplySort") == ["fn:jsp:/WEB-INF/jsp/hr/dept/HR0104.jsp#fn_applySort"]  # 인라인 스크립트
    assert to("el:HR:HR0101#btnPrint") == []                                          # 핸들러 없음


def test_screens_menus_popups(static_model):
    screens = {s["key"]: s for s in static_model["screens"]}
    assert screens["scr:HR:HR0101P01"]["kind"] == "popup"
    assert screens["scr:HR:HR0102"]["kind"] == "popup"            # gfn_popup('/hr/emp/HR0102.do?empNo=' + ...)
    menus = {m["key"]: m for m in static_model["menus"]}
    assert menus["menu:HR:M0101"]["screenKey"] == "scr:HR:HR0101"
    assert menus["menu:HR:M0201"]["screenKey"] is None
    els = {e["key"]: e for e in static_model["elements"]}
    assert els["el:HR:HR0101#btnEmpPop"]["type"] == "popup"
    assert els["el:HR:HR0101#grdEmp"]["binding"].startswith("cols: empNo")


def test_inferred_relations(static_model):
    rel = {(r["from"], r["to"], r["kind"]): r for r in static_model["relations"]}
    assert ("tbl:HR.TB_EMP_HIST", "tbl:HR.TB_EMP", "fk") in rel
    emp_dept = rel[("tbl:HR.TB_EMP", "tbl:HR.TB_DEPT", "inferred")]
    assert emp_dept["columns"] == [["DEPT_CD", "DEPT_CD"]] and emp_dept["evidenceCount"] >= 3
    assert ("tbl:HR.TB_EMP_HIST", "tbl:HR.TB_EMP", "inferred") not in rel   # FK 있으면 추정 안 함


def test_bundled_schema_matches_docs():
    bundled = json.loads((__import__("pmsx_collector.validate", fromlist=["x"]).SCHEMA_PATH).read_text(encoding="utf-8"))
    if not DOCS_SCHEMA.exists():
        pytest.skip("docs 스키마 없음")
    assert bundled == json.loads(DOCS_SCHEMA.read_text(encoding="utf-8")), "pmsx_collector/schema 사본을 docs 와 동기화하세요"
