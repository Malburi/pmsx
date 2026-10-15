"""Struts 1 + 자체 프레임워크(coperframe 류) — struts1·queryxml 파서, JSP 확장(include·JSP 변수·EUC-KR·상대 URL·
폼 action·opener 호출·인라인 처리), 폴더 메뉴, init 감지. 샘플: samples/hr-struts (가상 코드)."""
from pathlib import Path

import pytest

from pmsx_collector.config import load_config
from pmsx_collector.parsers.queryxml import plain_sql, strip_dynamic_blocks
from pmsx_collector.parsers.struts1 import clean_query_id
from pmsx_collector.pipeline import run
from pmsx_collector.scaffold import detect
from pmsx_collector.textio import decode_auto

from conftest import ROOT, make_config

SAMPLE = ROOT.parent / "samples" / "hr-struts"


@pytest.fixture(scope="module")
def hs(tmp_path_factory):
    cfg = make_config(load_config(SAMPLE / "collector.yaml"), output=str(tmp_path_factory.mktemp("hs")))
    res = run(cfg, capture=False, log=lambda *_: None)
    assert res.errors == [], res.errors
    return res.model


def _edges(model, kind=None):
    out = {}
    for e in model["edges"]:
        if kind is None or e["kind"] == kind:
            out.setdefault(e["from"], []).append(e)
    return out


def _reach(model, start, depth=12):
    E = _edges(model)
    seen, frontier = set(), [start]
    for _ in range(depth):
        nxt = []
        for f in frontier:
            for e in E.get(f, []):
                if e["kind"] == "navigates":
                    continue
                if e["to"] not in seen:
                    seen.add(e["to"])
                    nxt.append(e["to"])
        frontier = nxt
    return seen


# ---------------------------------------------------------------- 단위
def test_dynamic_blocks_and_query_id_suffix():
    sql = "SELECT A FROM T WHERE 1=1 $1{AND B = ?} $2{AND C IN (SELECT X FROM U WHERE $3{Y = ?})}"
    assert strip_dynamic_blocks(sql) == "SELECT A FROM T WHERE 1=1 AND B = ? AND C IN (SELECT X FROM U WHERE Y = ?)"
    assert ":p" in plain_sql(sql, r"\$\d+\{") and "?" not in plain_sql(sql, r"\$\d+\{")
    assert clean_query_id("CODE_S01,1") == "CODE_S01" and clean_query_id(" EMP_I01 ") == "EMP_I01"


def test_decode_auto_euc_kr():
    raw = "사원 목록".encode("cp949")
    assert decode_auto(raw) == "사원 목록"
    assert decode_auto("사원".encode("utf-8")) == "사원"


# ---------------------------------------------------------------- 서버
def test_routes_from_web_xml_worker_and_dispatch(hs):
    handles = {e["from"]: e["to"] for e in hs["edges"] if e["kind"] == "handles"}
    # struts-hr.xml 은 web.xml 에만 등록 (struts.configs 없음) — WorkerAction: method=save → EmpService.doSave
    assert handles["api:POST /hr/emp/EmpAction.do?method=save"].endswith("#EmpService.doSave")
    # 표준 DispatchAction: ActionMapping 시그니처 메서드만 (helper 제외)
    assert handles["api:GET /hr/dept/DeptAction.do?method=list"].endswith("#DeptAction.list")
    assert not any("method=helper" in k for k in handles)


def test_service_query_ids_literal_local_buffer_and_subcall(hs):
    ex = _edges(hs, "executes")
    save = next(k for k in ex if k.endswith("#EmpService.doSave"))
    targets = {e["to"] for e in ex[save]}
    assert {"sql:EMP_I01", "sql:EMP_U01", "sql:EMP_HIST_I01"} <= targets       # 지역 변수 2값 + StringBuffer
    calls = _edges(hs, "calls")
    edit = next(k for k in calls if k.endswith("#EmpService.doEdit"))
    assert any(e["to"].endswith("#CodeService.doCodeList") for e in calls[edit])   # subCall("CodeService","codeList")
    code = next(k for k in ex if k.endswith("#CodeService.doCodeList"))
    assert [e["to"] for e in ex[code]] == ["sql:CODE_S01"]                         # "CODE_S01,1" 접미사 제거


def test_query_xml_tables_crud_and_inferred_columns(hs):
    acc = {(e["from"], e["to"]): e for e in hs["edges"] if e["kind"] == "accesses"}
    assert acc[("sql:EMP_I01", "tbl:HS.TB_EMP")]["crud"] == "C"
    assert acc[("sql:EMP_U01", "tbl:HS.TB_EMP")]["crud"] == "U"
    assert ("sql:EMP_LIST_S01", "tbl:HS.TB_DEPT") in acc                          # $1{…} 블록 안 조건도 분석
    emp = next(t for t in hs["tables"] if t["key"] == "tbl:HS.TB_EMP")
    assert {c["name"] for c in emp["columns"]} >= {"EMP_NO", "EMP_NM", "DEPT_CD"}   # DDL 없을 때 SQL 에서 추정


# ---------------------------------------------------------------- 화면
def test_jsp_euckr_include_vars_and_screen_urls(hs):
    scr = {s["screenId"]: s for s in hs["screens"]}
    assert scr["empList"]["name"] == "사원 목록"                                      # EUC-KR <caption>
    assert scr["empList"]["url"] == "/hr/emp/EmpAction.do?method=list"               # forward 로 화면 URL
    assert scr["empPop"]["kind"] == "popup"
    ev = _edges(hs, "event")
    # include(.jspf) 안 함수가 화면 요소 이벤트로 연결
    assert any(e["to"].endswith("incHead.jspf#onCheckKey") for e in ev["el:HS:empList#name:empNm"])
    # <body onload>
    assert any(e["to"].endswith("#onInit") for e in ev["scr:HS:empList"])


def test_button_without_id_relative_url_to_table(hs):
    """id 없는 <img onclick> → onSave → frm.action = SAVE_PATH + "&cmd=I" (상대 URL, 상수) → doSave → SQL → 테이블."""
    r = _reach(hs, "el:HS:empList#img3")
    assert "api:POST /hr/emp/EmpAction.do?method=save" in r
    assert "sql:EMP_U01" in r and "tbl:HS.TB_EMP_HIST" in r
    req = next(e for e in hs["edges"] if e["kind"] == "request" and e["from"].endswith("#onSave"))
    assert req["condition"] == 'confirm("저장하시겠습니까?")'


def test_link_href_javascript_location_and_popup(hs):
    assert "api:GET /hr/emp/EmpAction.do?method=edit" in _reach(hs, "el:HS:empList#a4")   # <a href="javascript:onEdit()">
    nav = _edges(hs, "navigates")
    assert any(e["to"] == "scr:HS:empPop" for e in nav[next(k for k in nav if k.endswith("#onPop"))])
    # 인라인 onclick="location.href='…'" (함수 없음) → 인라인 코드 단위 → Action
    assert "fn:java:src/kr/sample/hr/dept/DeptAction.java#DeptAction.list" in _reach(hs, "el:HS:deptList#input1")


def test_opener_call_and_form_action_submit(hs):
    calls = _edges(hs, "calls")
    sel = next(k for k in calls if k.endswith("empPop.jsp#onSelect"))
    assert any(e["to"].endswith("emp_list.js#onPopupSave") for e in calls[sel])   # opener.onPopupSave → 부모 화면 함수
    req = _edges(hs, "request")
    ps = next(k for k in req if k.endswith("#onPopupSave"))
    # frm.submit() 만 있는 함수 → <form action="?method=list"> (empList) / "?method=save" (empEdit)
    assert {e["to"] for e in req[ps]} == {"api:POST /hr/emp/EmpAction.do?method=list",
                                           "api:POST /hr/emp/EmpAction.do?method=save"}


def test_directory_menu(hs):
    menus = {m["key"]: m for m in hs["menus"]}
    assert menus["menu:HS:D:hr/emp"]["name"] == "사원"
    assert menus["menu:HS:S:empList"]["screenKey"] == "scr:HS:empList"
    assert menus["menu:HS:S:empList"]["parentKey"] == "menu:HS:D:hr/emp"
    assert "menu:HS:S:empPop" not in menus                                          # 팝업은 메뉴에 넣지 않음


def test_init_detects_struts_and_query_xml():
    d = detect(SAMPLE)
    assert "struts1" in d.server and "queryxml" in d.server
    st = d.extra["struts"]
    assert st["workerActions"][0]["type"] == "kr.sample.fw.WorkerAction"
    assert st["workerActions"][0]["serviceAttr"] == "command"
    assert d.extra["source"]["keepQueryParams"] == ["method"]
    assert Path(d.source["jsp"]).name == "jsp"
