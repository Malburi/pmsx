/**
 * 사원관리 (HR0101)
 * @since 2019.03.02
 * 2021.07.12 입사일 기간 검색 추가
 */
$(document).ready(function() {
    fn_initCombo();

    $('#btnEmpPop').on('click', fn_openEmpPopup);
    $('#btnExcel').click(function() { fn_excel(); });
    $('#btnNew').on('click', fn_new);
    $('#btnSave').on('click', fn_save);
    $('#btnDelete').on('click', fn_delete);
    $('#txtEmpNm').on('keydown', function(e) {
        if (e.keyCode == 13) fn_search();
    });
    $('#grdEmp').on('dblclick', 'tbody tr', fn_grdDblClick);

    fn_search();
});

function fn_initCombo() {
    gfn_setCombo('#cboDept', '/cmm/code/dept.do', { useYn: 'Y' }, '전체');
}

function fn_search() {
    var param = $('#frmSearch').serializeObject();
    gfn_ajax('/hr/emp/list.do', param, function(res) {
        gfn_bindGrid('#grdEmp', res.list, ['empNo', 'empNm', 'deptNm', 'posNm', 'hireDt']);
        $('#totCnt').text(res.list.length);
    });
}

function fn_new() {
    gfn_addRow('#grdEmp', { useYn: 'Y', hireDt: gfn_today() });
}

function fn_save() {
    var rows = gfn_getChangedRows('#grdEmp');
    if (rows.length == 0) {
        alert('변경된 내용이 없습니다.');
        return;
    }
    if (!gfn_validate(rows, ['empNm', 'deptCd', 'hireDt'])) return;
    if (!confirm('저장하시겠습니까?')) return;

    gfn_ajax('/hr/emp/save.do', { rows: rows }, function() {
        alert('저장되었습니다.');
        fn_search();
    });
}

function fn_delete() {
    var row = gfn_getSelectedRow('#grdEmp');
    if (!row) {
        alert('삭제할 사원을 선택하세요.');
        return;
    }
    if (!confirm('삭제하시겠습니까?')) return;
    gfn_ajax('/hr/emp/delete.do', { empNo: row.empNo }, fn_search);
}

function fn_excel() {
    gfn_excel('/hr/emp/excel.do', $('#frmSearch').serializeObject());
}

function fn_openEmpPopup() {
    gfn_popup('/hr/emp/HR0101P01.do', 600, 450, function(ret) {
        $('#txtEmpNm').val(ret.empNm);
        fn_search();
    });
}

function fn_grdDblClick() {
    var row = gfn_getRowData(this);
    gfn_popup('/hr/emp/HR0102.do?empNo=' + row.empNo, 700, 500);
}
