"""정적 렌더링 캡처 (capture.mode: static-render) — JSP → HTML 변환, 스크립틀릿 분기 정리, 요소 표식·좌표 매칭.

샘플: 테스트 안에서 만드는 작은 가상 JSP 프로젝트 + samples/hr-struts·hr-legacy (모두 가상 코드).
"""
import shutil
from pathlib import Path

import pytest
import yaml

from pmsx_collector.config import Config, load_config
from pmsx_collector.model import ModelBuilder
from pmsx_collector.parsers import Context
from pmsx_collector.pipeline import run
from pmsx_collector.render.jsp_static import JspStaticRenderer, load_messages
from pmsx_collector.render.scriptlet_branches import resolve_branches
from pmsx_collector.render.static_capture import _Site

from conftest import ROOT, make_config

HAS_CHROME = shutil.which("chrome") or Path(r"C:/Program Files/Google/Chrome/Application/chrome.exe").is_file()


# ---------------------------------------------------------------- 스크립틀릿 분기
def test_branches_policies():
    t = ('A<% if(cmd.equals("U")){ %>EDIT<% } else { %>NEW<% } %>B'
         '<% for(int i=0;i<n;i++){ %><tr/><% } %>C<% if(x){ %>ONLY<% } %>D'
         '<% try { %>T<% } catch(Exception e) { %>F<% } %>E<% String s="}{"; if (s.equals("{")) { %>S<% } %>G')
    assert resolve_branches(t, "last") == "ANEWB<tr/>CONLYDTESG"
    assert resolve_branches(t, "first") == "AEDITB<tr/>CONLYDTESG"


def test_branches_rich_prefers_content_and_visible():
    rows = '<% if(n>0){ %><tr><td>a</td><td>b</td></tr><% } else { %><tr><td>없음</td></tr><% } %>'
    assert resolve_branches(rows) == "<tr><td>a</td><td>b</td></tr>"
    hidden = '<% if(h){ %><div style="display:none"><% } else { %><div><% } %>X'
    assert resolve_branches(hidden) == "<div>X"
    chain = '<% if(a){ %>X<% } else if(b){ %><p>Y</p><% } else { %>Z<% } %>'
    assert resolve_branches(chain) == "<p>Y</p>"


def test_load_messages(tmp_path):
    p = tmp_path / "MessageResources_ko.properties"
    p.write_text("# c\nBTN_SAVE=\\uC800\\uC7A5\nTIT_LIST = 사원 {0} 목록\nLONG=a \\\n  b\n", encoding="utf-8")
    m = load_messages([p])
    assert m["BTN_SAVE"] == "저장" and m["TIT_LIST"] == "사원 {0} 목록" and m["LONG"] == "a b"


# ---------------------------------------------------------------- 가상 JSP 프로젝트
@pytest.fixture()
def jsp_project(tmp_path):
    root = tmp_path / "proj"
    jsp = root / "web" / "WEB-INF" / "jsp"
    (jsp / "common").mkdir(parents=True)
    (jsp / "emp").mkdir(parents=True)
    (root / "web" / "css").mkdir(parents=True)
    (root / "src").mkdir()
    (root / "src" / "MessageResources_ko.properties").write_text("BTN_SEARCH=조회\nTIT_EMP=사원 목록\n", encoding="utf-8")
    (root / "web" / "css" / "base.css").write_text("body{margin:0;font-family:sans-serif} .box{width:600px;height:200px}", encoding="utf-8")
    (jsp / "common" / "incHead.jspf").write_text(
        '<%@ taglib prefix="c" uri="c" %>\n<link rel="stylesheet" href="<%= CSS_PATH %>base.css"/>\n'
        '<input type="button" id="btnCommon" value="공통"/>\n', encoding="utf-8")
    (jsp / "emp" / "empList.jsp").write_text(
        '<%@ page contentType="text/html;charset=euc-kr" %>\n'
        '<html><head><title><bean:message key="TIT_EMP"/></title>\n'
        '<%@ include file="/WEB-INF/jsp/common/incHead.jspf" %>\n</head><body>\n'
        '<% String cmd = param.getString("cmd"); %>\n'
        '<% if (cmd.equals("U")) { %><div id="w" style="display:none"><% } else { %><div id="w"><% } %>\n'
        '<form name="f1" action="?method=list">\n'
        '<input type="text" id="empNm" name="empNm" value="<%= empNm %>"/>\n'
        '<input type="button" value="<bean:message key="BTN_SEARCH"/>" onclick="onSearch();"/>\n'
        '<html:text property="deptNm" styleId="deptNm"/>\n'
        '<c:choose><c:when test="${a}"><span>첫분기</span></c:when><c:otherwise><span>기타</span></c:otherwise></c:choose>\n'
        '<table id="grid"><c:forEach var="r" items="${list}"><tr><td>${r.name}</td></tr></c:forEach></table>\n'
        '<div class="box"></div>\n'
        '</form></div>\n</body></html>\n', encoding="cp949")
    raw = {"system": {"code": "TJ", "name": "t", "uiTech": ["jsp-jquery"], "dbType": "oracle"},
           "source": {"root": ".", "webapp": "web", "jsp": "web/WEB-INF/jsp", "java": "src", "jspIncludes": True,
                      "jspVars": {"CSS_PATH": "/css/"}},
           "capture": {"mode": "static-render"}, "render": {"parallel": 2, "timeoutSec": 8},
           "output": str(tmp_path / "out")}
    (root / "collector.yaml").write_text(yaml.safe_dump(raw, allow_unicode=True), encoding="utf-8")
    return load_config(root / "collector.yaml")


def test_convert_jsp(jsp_project):
    cfg = jsp_project
    res = run(make_config(cfg, **{"capture.mode": ""}), capture=False, log=lambda *_: None)
    keys = {e["key"] for e in res.model["elements"]}
    ctx = Context(cfg, ModelBuilder("TJ", "t", [], [], None))
    r = JspStaticRenderer(ctx)
    jsp = cfg.source_dir("jsp") / "emp" / "empList.jsp"
    rs = r.render_screen("scr:TJ:empList", jsp)
    h = rs.html
    assert "<%" not in h and "${" not in h and "<bean:" not in h and "<c:" not in h
    assert "<title>사원 목록</title>" in h                       # bean:message → MessageResources 문구
    assert 'value="조회"' in h
    assert 'href="/css/base.css"' in h and 'id="btnCommon"' in h  # include 인라인 + 경로 상수
    assert 'name="deptNm"' in h and 'id="deptNm"' in h          # html:text → input
    assert "첫분기" in h and "기타" not in h                     # c:choose 첫 분기
    assert h.count("<tr>") == 2                                  # c:forEach 2회 반복
    assert "display:none" not in h                               # if/else 중 보이는 분기
    # 표식 번호 → 정적 분석 요소 키 (전부 실제 요소)
    assert set(rs.keymap.values()) <= keys
    assert {"el:TJ:empList#empNm", "el:TJ:empList#btnCommon", "el:TJ:empList#grid"} <= set(rs.keymap.values())
    assert h.count("data-pmsx=") >= 4


def test_site_serves_readonly(tmp_path):
    (tmp_path / "res").mkdir()
    (tmp_path / "res" / "a.js").write_bytes("var s='한글';".encode("cp949"))
    (tmp_path / "res" / "WEB-INF").mkdir()
    (tmp_path / "res" / "WEB-INF" / "secret.txt").write_text("x")
    (tmp_path / "outside.txt").write_text("x")
    site = _Site([("/r/", tmp_path / "res")], [], "").start()
    try:
        import urllib.request
        base = f"http://127.0.0.1:{site.port}"
        with urllib.request.urlopen(base + "/r/a.js") as resp:
            assert resp.headers["Content-Type"].startswith("text/javascript")
            assert "한글" in resp.read().decode("utf-8")              # EUC-KR 리소스 → UTF-8
        site.put("t1", "<p>hi</p>")
        with urllib.request.urlopen(base + "/any/x.do?__pmsx=t1") as resp:
            assert resp.read() == b"<p>hi</p>"
        for bad in ("/r/../outside.txt", "/r/%2e%2e/outside.txt", "/r/WEB-INF/secret.txt", "/nope.css"):
            with pytest.raises(urllib.error.HTTPError):
                urllib.request.urlopen(base + bad)
    finally:
        site.stop()


@pytest.mark.skipif(not HAS_CHROME, reason="Chrome 없음")
def test_static_render_pipeline(jsp_project):
    res = run(jsp_project, capture=True, log=lambda *_: None)
    assert res.errors == [], res.errors
    m = res.model
    s = next(x for x in m["screens"] if x["screenId"] == "empList")
    cap = s["captures"][0]
    assert cap["state"] == "rendered" and cap["file"].endswith(".jpg")
    assert (res.out_dir / cap["file"]).is_file()
    els = {e["elementId"]: e for e in m["elements"] if e["screenKey"] == s["key"]}
    assert els["empNm"].get("box") and els["empNm"]["matched"] == "full"
    assert els["btnCommon"].get("box")                         # include 파일의 요소도 좌표
    assert m["manifest"]["stats"]["screensRendered"] == 1


@pytest.mark.skipif(not HAS_CHROME, reason="Chrome 없음")
@pytest.mark.parametrize("sample", ["hr-struts", "hr-legacy"])
def test_static_render_samples(tmp_path, sample):
    base = load_config(ROOT.parent / "samples" / sample / "collector.yaml")
    cfg = make_config(base, **{"output": str(tmp_path / "out"), "capture.enabled": False, "capture.mode": "static-render"})
    res = run(cfg, capture=True, log=lambda *_: None)
    assert res.errors == [], res.errors
    m = res.model
    jsp_screens = [s for s in m["screens"] if (s.get("sourceFile") or "").endswith(".jsp")]
    rendered = [s for s in jsp_screens if any(c.get("state") == "rendered" for c in s.get("captures", []))]
    assert len(rendered) >= max(1, len(jsp_screens) - 1)
    for s in rendered:
        assert (res.out_dir / s["captures"][0]["file"]).is_file()
    keys = {s["key"] for s in rendered}
    els = [e for e in m["elements"] if e["screenKey"] in keys]
    boxed = [e for e in els if e.get("box")]
    assert els and len(boxed) / len(els) >= 0.6, (len(boxed), len(els))
