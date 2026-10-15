<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%-- 교육이력관리 HR0105 / 2024.03 신규 (객체 모듈 패턴 + 위임 이벤트 사용) --%>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<title>교육이력관리</title>
<link rel="stylesheet" href="<c:url value='/css/cmm/legacy.css'/>">
<script src="<c:url value='/vendor/jquery-3.7.1.min.js'/>"></script>
<script src="<c:url value='/js/cmm/common.js'/>"></script>
<script src="<c:url value='/js/hr/HR0105.js'/>"></script>
</head>
<body>
<div class="title">■ 교육이력관리 [HR0105]</div>

<form id="frmEdu" class="search-box" onsubmit="return false;">
    <label for="cboEduType">교육구분</label>
    <select id="cboEduType" name="eduTypeCd" style="width:120px"></select>
    <span class="lbl">교육연도</span>
    <input type="text" name="eduYear" title="교육연도" value="2026" style="width:60px">
    <label for="txtEduNm">교육명</label>
    <input type="text" id="txtEduNm" name="eduNm" style="width:160px">
</form>

<div class="btn-area">
    <span class="sub-title">&#9654; 교육 이력</span>
    <span class="right">
        <button type="button" data-act="reset">초기화</button>
        <button type="button" id="btnEduStat">통계</button>
        <button type="button" id="btnEduSearch">조회</button>
        <button type="button" class="btn-excel">엑셀</button>
    </span>
</div>

<table id="grdEdu" class="grid" title="교육 이력">
    <thead>
        <tr>
            <th data-col="empNo">사원번호</th>
            <th data-col="empNm">사원명</th>
            <th data-col="eduTypeNm">구분</th>
            <th data-col="eduNm">교육명</th>
            <th data-col="eduHours">시간</th>
            <th data-col="instNm">교육기관</th>
        </tr>
    </thead>
    <tbody></tbody>
</table>

<div class="btn-area bottom">
    <span class="right">
        <button type="button" id="btnEduSave">저장</button>
    </span>
</div>
</body>
</html>
