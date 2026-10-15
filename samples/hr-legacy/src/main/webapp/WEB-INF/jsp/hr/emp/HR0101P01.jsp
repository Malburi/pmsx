<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%-- 사원검색 팝업 HR0101P01 --%>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<title>사원검색</title>
<link rel="stylesheet" href="<c:url value='/css/cmm/legacy.css'/>">
<script src="<c:url value='/vendor/jquery-3.7.1.min.js'/>"></script>
<script src="<c:url value='/js/cmm/common.js'/>"></script>
<script src="<c:url value='/js/hr/HR0101P01.js'/>"></script>
</head>
<body class="popup">
<div class="title">■ 사원검색</div>
<div class="search-box">
    <label for="txtKeyword">사원명/사번</label>
    <input type="text" id="txtKeyword" name="empNm" style="width:180px">
    <button type="button" id="btnPopSearch">조회</button>
</div>
<table id="grdPopEmp" class="grid" title="검색 결과">
    <thead>
        <tr><th data-col="empNo">사원번호</th><th data-col="empNm">사원명</th><th data-col="deptNm">부서</th></tr>
    </thead>
    <tbody></tbody>
</table>
<div class="btn-area bottom">
    <span class="right">
        <button type="button" id="btnSelect">선택</button>
        <button type="button" id="btnClose">닫기</button>
    </span>
</div>
</body>
</html>
