<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%-- 부서관리 화면 HR0104 - 화면 스크립트 인라인 작성 --%>
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<title>부서관리</title>
<link rel="stylesheet" href="<c:url value='/css/cmm/legacy.css'/>">
<script src="<c:url value='/vendor/jquery-3.7.1.min.js'/>"></script>
<script src="<c:url value='/js/cmm/common.js'/>"></script>
<script>
$(document).ready(function() {
    $('#btnSearch').on('click', fn_search);
    $('#btnApplySort').on('click', fn_applySort);
    $('#btnDeptSave').on('click', fn_saveDept);
    fn_search();
});

function fn_search() {
    gfn_ajax('/hr/dept/list.do', { useYn: $('#cboUseYn').val() }, function(res) {
        gfn_bindGrid('#grdDept', res.list, ['deptCd', 'deptNm', 'upDeptNm', 'sortOrd', 'useYn']);
    });
}

/* 화면에서 변경한 정렬순서를 일괄 반영 (확인창 없음) */
function fn_applySort() {
    var rows = [];
    $('#grdDept tbody tr').each(function(idx) {
        var row = $(this).data('row');
        rows.push({ deptCd: row.deptCd, sortOrd: idx + 1 });
    });
    gfn_ajax('/hr/dept/sort.do', rows, function() {
        alert('정렬순서가 적용되었습니다.');
        fn_search();
    });
}

function fn_saveDept() {
    var row = gfn_getSelectedRow('#grdDept');
    if (!row) { alert('부서를 선택하세요.'); return; }
    if (!confirm('저장하시겠습니까?')) return;
    gfn_ajax('/hr/dept/save.do', row, fn_search);
}
</script>
</head>
<body>
<div class="title">■ 부서관리 [HR0104]</div>
<div class="search-box">
    <label for="cboUseYn">사용여부</label>
    <select id="cboUseYn" name="useYn"><option value="">전체</option><option value="Y">사용</option><option value="N">미사용</option></select>
    <button type="button" id="btnSearch">조회</button>
</div>
<table id="grdDept" class="grid" title="부서 목록">
    <thead>
        <tr><th data-col="deptCd">부서코드</th><th data-col="deptNm">부서명</th><th data-col="upDeptNm">상위부서</th><th data-col="sortOrd">정렬</th><th data-col="useYn">사용</th></tr>
    </thead>
    <tbody></tbody>
</table>
<div class="btn-area bottom">
    <span class="right">
        <button type="button" id="btnApplySort">정렬적용</button>
        <button type="button" id="btnDeptSave">저장</button>
    </span>
</div>
</body>
</html>
