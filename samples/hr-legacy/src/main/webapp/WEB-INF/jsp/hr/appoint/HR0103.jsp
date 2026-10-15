<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%-- 발령관리 화면 HR0103 --%>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<title>발령관리</title>
<link rel="stylesheet" href="<c:url value='/css/cmm/legacy.css'/>">
<script src="<c:url value='/vendor/jquery-3.7.1.min.js'/>"></script>
<script src="<c:url value='/js/cmm/common.js'/>"></script>
<script src="<c:url value='/js/hr/HR0103.js'/>"></script>
</head>
<body>
<div class="title">■ 발령관리 [HR0103]</div>
<form id="frmSearch" class="search-box" onsubmit="return false;">
    <label for="cboApptType">발령유형</label>
    <select id="cboApptType" name="apptType" style="width:140px"></select>
    <label for="calApptFrom">발령일</label>
    <input type="date" id="calApptFrom" name="apptFrom"> ~
    <input type="date" id="calApptTo" name="apptTo">
    <button type="button" id="btnSearch">조회</button>
</form>

<table id="grdAppt" class="grid" title="발령 목록">
    <thead>
        <tr>
            <th data-col="apptNo">발령번호</th><th data-col="empNm">사원명</th><th data-col="apptTypeNm">발령유형</th>
            <th data-col="fromDeptNm">이전부서</th><th data-col="toDeptNm">발령부서</th><th data-col="apptDt">발령일</th><th data-col="status">상태</th>
        </tr>
    </thead>
    <tbody></tbody>
</table>

<div class="edit-box">
    <label for="txtApptEmpNo">사원번호</label><input type="text" id="txtApptEmpNo" name="empNo" style="width:100px">
    <label for="cboToDept">발령부서</label><select id="cboToDept" name="toDeptCd" style="width:140px"></select>
    <label for="calApptDt">발령일</label><input type="date" id="calApptDt" name="apptDt">
</div>

<div class="btn-area bottom">
    <span class="right">
        <button type="button" id="btnRegist">발령등록</button>
        <button type="button" id="btnConfirm">발령확정</button>
    </span>
</div>
</body>
</html>
