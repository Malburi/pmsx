<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%-- 사원상세 팝업 HR0102 - 인라인 스크립트 --%>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<title>사원상세</title>
<link rel="stylesheet" href="<c:url value='/css/cmm/legacy.css'/>">
<script src="<c:url value='/vendor/jquery-3.7.1.min.js'/>"></script>
<script src="<c:url value='/js/cmm/common.js'/>"></script>
<script>
$(function() {
    fn_loadDetail();
    $('#btnClose').on('click', function() { window.close(); });
});

function fn_loadDetail() {
    $.ajax({
        url: '/hr/emp/detail.do',
        type: 'POST',
        data: { empNo: gfn_getParam('empNo') },
        dataType: 'json',
        success: function(res) {
            $('#spnEmpNo').text(res.emp.empNo);
            $('#spnEmpNm').text(res.emp.empNm);
            $('#spnDeptNm').text(res.emp.deptNm);
            $('#spnEmail').text(res.emp.email);
        }
    });
}
</script>
</head>
<body class="popup">
<div class="title">■ 사원상세</div>
<table class="form">
    <tr><th>사원번호</th><td><span id="spnEmpNo"></span></td></tr>
    <tr><th>사원명</th><td><span id="spnEmpNm"></span></td></tr>
    <tr><th>부서</th><td><span id="spnDeptNm"></span></td></tr>
    <tr><th>이메일</th><td><span id="spnEmail"></span></td></tr>
</table>
<div class="btn-area bottom"><span class="right"><button type="button" id="btnClose">닫기</button></span></div>
</body>
</html>
