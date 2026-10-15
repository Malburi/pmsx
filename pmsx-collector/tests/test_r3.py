"""R3 수집기 — WebSquare · MiPlatform/XPlatform · WinForms · ASP.NET(WebForms/MVC) · .NET 데이터 접근 · 데스크톱 캡처."""
import shutil
import subprocess
from pathlib import Path

import pytest

from pmsx_collector.config import load_config
from pmsx_collector.parsers.aspnet import aspx_to_html, razor_to_html
from pmsx_collector.parsers.ts import parse
from pmsx_collector.pipeline import run

from conftest import ROOT, make_config

SAMPLES = ROOT.parent / "samples"


def _edges(model):
    out = {}
    for e in model["edges"]:
        out.setdefault(e["from"], []).append(e)
    return out


def _reach(model, start, depth=12):
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


def _edge(model, frm, to, kind=None):
    return next((e for e in model["edges"] if e["from"] == frm and e["to"] == to and (kind is None or e["kind"] == kind)), None)


def _run(sample, tmp_path_factory, **over):
    cfg = make_config(load_config(SAMPLES / sample / "collector.yaml"), output=str(tmp_path_factory.mktemp(sample)), **over)
    res = run(cfg, capture=False, log=lambda *_: None)
    assert res.errors == [], res.errors           # 스키마 v0.2 통과
    return res.model


# ---------------------------------------------------------------- WinForms + .NET 데이터 접근
@pytest.fixture(scope="module")
def hw(tmp_path_factory):
    return _run("hr-winforms", tmp_path_factory)


def test_winforms_screens_menus_and_wireframes(hw):
    scr = {s["key"]: s for s in hw["screens"]}
    assert set(scr) == {f"scr:HW:{n}" for n in ("MainForm", "EmpForm", "DeptForm", "AttendForm", "EmpSearchPopup")}
    assert scr["scr:HW:EmpForm"]["name"] == "사원관리" and scr["scr:HW:EmpForm"]["uiTech"] == "winforms"
    assert scr["scr:HW:EmpSearchPopup"]["kind"] == "popup"                    # ShowDialog + FixedDialog
    assert all(c["state"] == "wireframe" for s in scr.values() for c in s["captures"])
    assert scr["scr:HW:EmpForm"]["captures"][0]["width"] == 884                  # ClientSize
    menus = {m["key"]: m for m in hw["menus"]}
    assert menus["menu:HW:mnuEmp"]["screenKey"] == "scr:HW:EmpForm"
    assert menus["menu:HW:mnuAttendList"]["screenKey"] == "scr:HW:AttendForm"   # 헬퍼 메서드 OpenForm(new AttendForm())
    assert menus["menu:HW:mnuEmp"]["parentKey"] == "menu:HW:mnuHr"
    fails = [f for f in hw["failures"]]
    assert [f["target"] for f in fails] == ["menu:HW:mnuAttend"]               # 의도: 화면 없는 메뉴


def test_winforms_designer_coordinates_and_labels(hw):
    els = {e["key"]: e for e in hw["elements"]}
    t = els["el:HW:EmpForm#txtEmpNm"]
    assert (t["box"]["x"], t["box"]["y"]) == (12 + 64, 12 + 23)                 # GroupBox 오프셋 누적
    assert t["label"] == "사원명"                                                # 같은 줄 왼쪽 Label
    assert els["el:HW:DeptForm#btnRename"]["box"]["x"] == 12 + 4 + 567           # TabControl → TabPage → 버튼
    assert els["el:HW:EmpForm#dgvEmp"]["binding"] == "cols: EmpNo, EmpNm, DeptNm, HireDt"
    assert els["el:HW:EmpForm#btnEmpSearch"]["type"] == "popup"
    assert els["el:HW:MainForm#mnuEmp"]["type"] == "link"


def test_winforms_save_flow_to_stored_procedure(hw):
    save = "el:HW:EmpForm#btnSave"
    reach = _reach(hw, save)
    for n in ("fn:cs:src/HrWin/Forms/EmpForm.cs#EmpForm.btnSave_Click",
              "fn:cs:src/HrWin/Services/Services.cs#EmpService.SaveEmp",      # IEmpService 필드 → 구현체
              "fn:cs:src/HrWin/Data/EmpDao.cs#EmpDao.SaveEmp",                 # 생성자 주입
              "sp:HW.USP_EMP_SAVE", "tbl:HW.TB_EMP", "tbl:HW.TB_EMP_HIST"):
        assert n in reach, n
    e = _edge(hw, "fn:cs:src/HrWin/Forms/EmpForm.cs#EmpForm.btnSave_Click", "fn:cs:src/HrWin/Services/Services.cs#EmpService.SaveEmp")
    assert e["condition"] == 'confirm("저장하시겠습니까?")'
    acc = _edge(hw, "sp:HW.USP_EMP_SAVE", "tbl:HW.TB_EMP", "accesses")
    assert acc["crud"] == "CU"


def test_dotnet_ado_dapper_ef_and_tsql(hw):
    sql = {s["key"]: s for s in hw["sqlStatements"]}
    # ADO.NET: verbatim + += 조건 누적
    assert "TB_DEPT D ON D.DEPT_CD = E.DEPT_CD" in sql["sql:EmpDao.SelectEmpList"]["text"]
    assert "ORDER BY E.EMP_NO" in sql["sql:EmpDao.SelectEmpList"]["text"]
    assert "sql:EmpDao.SelectDeptCode" in sql                                  # cmd.CommandText = const
    # Dapper Execute → UPDATE
    assert _edge(hw, "sql:EmpDao.DeleteEmp", "tbl:HW.TB_EMP", "accesses")["crud"] == "U"
    # StringBuilder.Append 조립 + Dapper Query
    assert "JOIN dbo.TB_EMP E ON E.EMP_NO = A.EMP_NO" in sql["sql:AttendDao.SelectAttendList"]["text"]
    # Dapper commandType: StoredProcedure → SP, SP → SP (EXEC)
    assert _edge(hw, "fn:cs:src/HrWin/Data/AttendDao.cs#AttendDao.CloseMonth", "sp:HW.USP_ATTEND_CLOSE", "executes")
    assert _edge(hw, "sp:HW.USP_ATTEND_CLOSE", "sp:HW.USP_WRITE_LOG", "calls")
    # EF: DbSet.Add → C, Find + 속성 대입 + SaveChanges → U, Where/ToList → R
    assert sql["sql:DeptService.AddDept"]["kind"] == "insert"
    assert _edge(hw, "sql:DeptService.RenameDept", "tbl:HW.TB_DEPT", "accesses")["crud"] == "RU"
    assert _edge(hw, "sql:DeptService.GetDeptList", "tbl:HW.TB_DEPT", "accesses")["crud"] == "R"
    # T-SQL DDL: MS_Description 코멘트, 프로시저(GO 배치)
    tbl = {t["key"]: t for t in hw["tables"]}
    assert tbl["tbl:HW.TB_EMP"]["comment"] == "사원"
    assert next(c for c in tbl["tbl:HW.TB_EMP"]["columns"] if c["name"] == "EMP_NM")["comment"] == "사원명"
    # 스텁·목업 파일은 제외 (dotnet.exclude)
    assert not any("Stubs.cs" in c["file"] or "MockData.cs" in c["file"] for c in hw["codeUnits"])
    rel = {(r["from"], r["to"], r["kind"]) for r in hw["relations"]}
    assert ("tbl:HW.TB_EMP", "tbl:HW.TB_DEPT", "inferred") in rel


def test_cs_string_evaluation():
    from pmsx_collector.parsers.dotnet import CsIndex
    src = 'class A { const string T = "TB_X"; void M(int x) { var s = @"SELECT ""A"" " + "FROM " + T + $" WHERE ID = {x}"; } }'
    tree = parse("cs", src)
    expr = [n for n in _iter(tree.root_node) if n.type == "binary_expression" and n.parent.type != "binary_expression"][0]

    class C:
        consts = {"T": "TB_X"}
    idx = CsIndex.__new__(CsIndex)
    idx.by_name = {}
    assert idx.eval_str(expr, {}, C) == 'SELECT "A" FROM TB_X WHERE ID = ?'


def _iter(n):
    stack = [n]
    while stack:
        x = stack.pop()
        yield x
        stack.extend(reversed(x.children))


# ---------------------------------------------------------------- 데스크톱 캡처 (실제 WinForms 앱)
def _hrwin_exe() -> Path | None:
    exe = SAMPLES / "hr-winforms" / "src" / "HrWin" / "bin" / "Release" / "net7.0-windows" / "HrWin.exe"
    if not exe.is_file() and shutil.which("dotnet"):
        subprocess.run(["dotnet", "build", "-c", "Release", "-nologo", "-v", "q"], cwd=exe.parents[3], timeout=300,
                       capture_output=True)
    return exe if exe.is_file() else None


def _hrwin_pids() -> set[int]:
    import win32process  # noqa: F401
    out = subprocess.run(["tasklist", "/FI", "IMAGENAME eq HrWin.exe", "/FO", "CSV", "/NH"], capture_output=True, text=True)
    return {int(line.split('","')[1]) for line in out.stdout.splitlines() if line.startswith('"HrWin.exe"')}


def test_desktop_capture_winforms(tmp_path):
    pytest.importorskip("pywinauto")
    exe = _hrwin_exe()
    if exe is None:
        pytest.skip("HrWin.exe 를 빌드할 수 없음 (.NET SDK 없음)")
    before = _hrwin_pids()
    cfg = make_config(load_config(SAMPLES / "hr-winforms" / "collector.yaml"), output=str(tmp_path), **{"capture.enabled": True})
    res = run(cfg, log=lambda *_: None)
    try:
        assert res.errors == []
        st = res.model["manifest"]["stats"]
        assert st["screensCaptured"] == 5                         # 메인 + 메뉴 3화면 + 버튼으로 연 팝업
        assert st["elementsMatchedFull"] >= 20                    # AutomationId = 디자이너 필드명
        assert st["captureClicks"] >= 5
        els = {e["key"]: e for e in res.model["elements"]}
        assert els["el:HW:EmpForm#btnSave"]["danger"] is True    # 위험 버튼은 누르지 않음
        nav = _edge(res.model, "fn:cs:src/HrWin/Forms/EmpForm.cs#EmpForm.btnEmpSearch_Click", "scr:HW:EmpSearchPopup", "navigates")
        assert nav["source"] == "dynamic"                         # 실제로 팝업이 열린 것을 관측
        cap = next(s for s in res.model["screens"] if s["key"] == "scr:HW:EmpForm")["captures"][0]
        assert cap["state"] != "wireframe" and cap["scale"] >= 1
        # 숨은 탭(조직도) 요소는 캡처 좌표계로 보정
        assert els["el:HW:DeptForm#btnRename"]["box"]["x"] > 500
        msgs = [f["message"] for f in res.model["failures"]]
        assert any("준비 중입니다" in m for m in msgs)             # 메뉴 → 메시지 상자 (자동으로 닫음)
    finally:
        assert _hrwin_pids() <= before, "캡처 후 앱 프로세스가 남음"


# ---------------------------------------------------------------- WebSquare
@pytest.fixture(scope="module")
def bw(tmp_path_factory):
    return _run("hr-websquare", tmp_path_factory)


def test_websquare_submission_flow(bw):
    save = "el:BW:BW0101#btn_save"
    fn = "fn:xml:ui/BW/BW0101.xml#scwin.btn_save_onclick"
    assert _edge(bw, save, fn, "event")
    req = _edge(bw, fn, "api:POST /bw/req/save.do", "request")                # gcm.executeSubmission("sbm_save") → 서브미션 action
    assert req and req["condition"] == 'confirm("저장하시겠습니까?")'
    assert _edge(bw, fn, "fn:xml:ui/BW/BW0101.xml#scwin.sbm_save_submitdone", "calls")["condition"] == "콜백"
    reach = _reach(bw, save)
    assert {"tbl:BW.TB_WELFARE_REQ", "tbl:BW.TB_WELFARE_REQ_HIST"} <= reach
    # 화면 로드: 공통 gcm.loadCode → $p.ajax
    assert "api:POST /bw/cmm/code/list.do" in _reach(bw, "scr:BW:BW0101")
    # $p.ajax({action}) + 프로시저
    assert "sp:BW.SP_WELFARE_COPY_YEAR" in _reach(bw, "el:BW:BW0102#btn_copyYear")


def test_websquare_layout_and_popup(bw):
    els = {e["key"]: e for e in bw["elements"]}
    e = els["el:BW:BW0101#ibx_empNm"]
    assert (e["box"]["x"], e["box"]["y"]) == (10 + 66, 40 + 9) and e["label"] == "사원명"
    assert e["binding"] == "dma_search.EMP_NM"
    assert els["el:BW:BW0102#btn_stdSave"]["box"]["x"] == 10 + 0 + 682         # 탭 콘텐츠 기준
    assert els["el:BW:BW0101#grd_req"]["binding"].startswith("dlt_req: REQ_NO")
    assert els["el:BW:BW0101#btn_emp"]["type"] == "popup"
    scr = {s["key"]: s for s in bw["screens"]}
    assert scr["scr:BW:BW0101P01"]["kind"] == "popup" and scr["scr:BW:BW0101"]["name"] == "복지포인트 신청"
    assert {f["target"] for f in bw["failures"]} == {"fn:xml:ui/BW/BW0101.xml#scwin.grd_req_oncelldblclick", "menu:BW:B0104"}


# ---------------------------------------------------------------- MiPlatform / XPlatform
@pytest.fixture(scope="module")
def at(tmp_path_factory):
    return _run("hr-xplatform", tmp_path_factory)


def test_xplatform_and_miplatform(at):
    scr = {s["key"]: s for s in at["screens"]}
    assert scr["scr:AT:AT0101"]["uiTech"] == "xplatform" and scr["scr:AT:AT0102"]["uiTech"] == "miplatform"
    assert at["manifest"]["parsers"].count("xplatform") == 1                     # 두 기술명 → 파서 1개
    els = {e["key"]: e for e in at["elements"]}
    # XPlatform: Form/Div 바로 아래 컴포넌트, position="absolute l t r b"
    assert els["el:AT:AT0101#div_search.btn_emp"]["box"]["x"] == 10 + 216
    # MiPlatform: 대문자 속성, Tab/TabPage, contents/format 그리드, BindDataset+Column
    assert els["el:AT:AT0102#tab_appr.tp_wait.btn_approve"]["box"]["x"] == 10 + 900
    assert els["el:AT:AT0102#tab_appr.tp_wait.grd_appr"]["binding"].startswith("ds_appr: CHK, REQ_NO")
    assert els["el:AT:AT0102#edt_dept"]["binding"] == "ds_cond.DEPT_CD"
    fn = "fn:xml:AT/AT0102.xml#btn_approve_OnClick"
    assert _edge(at, fn, "api:POST /at/appr/approve.do", "request")["condition"] == 'confirm("선택한 신청을 승인하시겠습니까?")'
    assert "tbl:AT.TB_ATT_APPR" in _reach(at, "el:AT:AT0102#tab_appr.tp_wait.btn_approve")
    assert _edge(at, "scr:AT:AT0102", "fn:xml:AT/AT0102.xml#AT0102_OnLoadCompleted", "event")
    assert "scr:AT:AT0101P01" in _reach(at, "el:AT:AT0101#div_search.btn_emp")
    assert at["failures"] == []


# ---------------------------------------------------------------- ASP.NET WebForms + MVC
@pytest.fixture(scope="module")
def rc(tmp_path_factory):
    return _run("hr-aspnet", tmp_path_factory)


def test_aspx_and_razor_preprocess_keep_lines():
    aspx = '<asp:Button ID="btnA" runat="server" Text="저장"\n OnClick="x" OnClientClick="return confirm(\'a\');" />\n<%= txt.ClientID %>'
    out = aspx_to_html(aspx)
    assert out.count("\n") == aspx.count("\n") and 'id="btnA"' in out and 'value="저장"' in out and "txt" in out
    razor = '@{ ViewBag.Title = "목록"; }\n@Html.TextBoxFor(m => m.Name, new { id = "txtName" })\n<a href="@Url.Action("List", "Emp")">x</a>'
    html, title = razor_to_html(razor, "Emp")
    assert title == "목록" and 'id="txtName"' in html and "/Emp/List" in html and html.count("\n") == razor.count("\n")


def test_webforms_postback_and_webmethod(rc):
    save = "el:RC:RecruitEdit#btnSave"
    ev = _edge(rc, save, "fn:cs:src/RcWeb/Recruit/RecruitEdit.aspx.cs#RecruitEdit.btnSave_Click", "event")
    assert ev["condition"] == 'confirm("저장하시겠습니까?")'                    # OnClientClick
    assert {"sp:RC.USP_RECRUIT_SAVE", "tbl:RC.TB_RECRUIT", "scr:RC:RecruitList"} <= _reach(rc, save)   # Response.Redirect
    assert _edge(rc, "api:POST /Recruit/RecruitList.aspx/GetOpenCount",
                 "fn:cs:src/RcWeb/Recruit/RecruitList.aspx.cs#RecruitList.GetOpenCount", "handles")
    sql = {s["key"] for s in rc["sqlStatements"]}
    assert {"sql:RecruitDac.SelectRecruitList", "sql:RecruitDac.UpdateStatus", "sql:RecruitDac.SelectOpenCount"} <= sql
    assert next(s for s in rc["screens"] if s["key"] == "scr:RC:RecruitList")["name"] == "채용공고 목록"


def test_mvc_routes_views_and_redirect(rc):
    menus = {m["key"]: m for m in rc["menus"]}
    assert menus["menu:RC:R0102"]["screenKey"] == "scr:RC:Applicant.Index"          # /Applicant → Index 별칭
    pass_ = "el:RC:Applicant.Index#btnPass"
    r = _reach(rc, pass_)
    assert "api:POST /applicant/pass/{id}" in r                                     # "/applicant/pass/" + id → [Route]
    assert {"tbl:RC.TB_APPLICANT", "tbl:RC.TB_APPLICANT_HIST"} <= r
    assert _edge(rc, "fn:cs:src/RcMvc/Controllers/ApplicantController.cs#ApplicantController.Pass", "scr:RC:Applicant.Index", "navigates")
    assert next(s for s in rc["screens"] if s["key"] == "scr:RC:Applicant.Detail")["kind"] == "popup"   # window.open
    assert _edge(rc, "fn:cs:src/RcMvc/Repositories/ApplicantRepository.cs#ApplicantRepository.Insert",
                 "sp:RC.USP_APPLICANT_INSERT", "executes")
    assert rc["failures"] == []
