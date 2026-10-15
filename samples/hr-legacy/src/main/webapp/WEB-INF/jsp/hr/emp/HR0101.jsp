<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%-- 사원관리 화면 HR0101 / 2019.03 최초작성, 2021.07 입사일 검색 추가 --%>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<title>사원관리</title>
<link rel="stylesheet" href="<c:url value='/css/cmm/legacy.css'/>">
<script src="<c:url value='/vendor/jquery-3.7.1.min.js'/>"></script>
<script src="<c:url value='/js/cmm/common.js'/>"></script>
<script src="<c:url value='/js/hr/HR0101.js'/>"></script>
</head>
<body>
<div class="title">■ 사원관리 [HR0101]</div>

<form id="frmSearch" class="search-box" onsubmit="return false;">
    <label for="txtEmpNm">사원명</label>
    <input type="text" id="txtEmpNm" name="empNm" style="width:150px">
    <button type="button" id="btnEmpPop" class="btn-icon" title="사원찾기">&#128269;</button>
    <label for="cboDept">부서</label>
    <select id="cboDept" name="deptCd" style="width:140px"></select>
    <label for="calHireFrom">입사일</label>
    <input type="date" id="calHireFrom" name="hireFrom"> ~
    <input type="date" id="calHireTo" name="hireTo">
</form>

<div class="btn-area">
    <span class="sub-title">&#9654; 사원 목록 (총 <b id="totCnt">0</b>건)</span>
    <span class="right">
        <button type="button" id="btnPrint">인쇄</button>
        <button type="button" id="btnSearch" onclick="fn_search()">조회</button>
        <button type="button" id="btnExcel">엑셀</button>
    </span>
</div>

<table id="grdEmp" class="grid" title="사원 목록">
    <thead>
        <tr>
            <th data-col="empNo">사원번호</th>
            <th data-col="empNm">사원명</th>
            <th data-col="deptNm">부서</th>
            <th data-col="posNm">직위</th>
            <th data-col="hireDt">입사일</th>
        </tr>
    </thead>
    <tbody></tbody>
</table>

<div class="btn-area bottom">
    <span class="right">
        <button type="button" id="btnNew">신규</button>
        <button type="button" id="btnSave">저장</button>
        <button type="button" id="btnDelete">삭제</button>
    </span>
</div>
</body>
</html>
