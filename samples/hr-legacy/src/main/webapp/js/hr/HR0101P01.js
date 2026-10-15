/**
 * 사원검색 팝업 (HR0101P01)
 */
$(document).ready(function() {
    $('#btnPopSearch').on('click', fn_popSearch);
    $('#btnSelect').on('click', fn_select);
    $('#btnClose').on('click', function() { window.close(); });
    $('#grdPopEmp').on('dblclick', 'tbody tr', fn_select);
});

function fn_popSearch() {
    gfn_ajax('/hr/emp/popList.do', { empNm: $('#txtKeyword').val() }, function(res) {
        gfn_bindGrid('#grdPopEmp', res.list, ['empNo', 'empNm', 'deptNm']);
    });
}

function fn_select() {
    var row = gfn_getSelectedRow('#grdPopEmp');
    if (!row) {
        alert('사원을 선택하세요.');
        return;
    }
    gfn_closePopup(row);
}
