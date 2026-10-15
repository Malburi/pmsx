/**
 * 발령관리 (HR0103)
 */
$(document).ready(function() {
    gfn_setCombo('#cboApptType', '/cmm/code/list.do', { grpCd: 'APPT' }, '전체');
    gfn_setCombo('#cboToDept', '/cmm/code/dept.do', { useYn: 'Y' });

    $('#btnSearch').on('click', fn_search);
    $('#btnRegist').on('click', fn_regist);
    $('#btnConfirm').on('click', fn_confirmAppt);

    fn_search();
});

function fn_search() {
    gfn_ajax('/hr/appoint/list.do', $('#frmSearch').serializeObject(), function(res) {
        gfn_bindGrid('#grdAppt', res.list, ['apptNo', 'empNm', 'apptTypeNm', 'fromDeptNm', 'toDeptNm', 'apptDt', 'status']);
    });
}

function fn_regist() {
    var param = {
        empNo: $('#txtApptEmpNo').val(),
        apptType: $('#cboApptType').val(),
        toDeptCd: $('#cboToDept').val(),
        apptDt: $('#calApptDt').val()
    };
    if (!param.empNo || !param.apptDt) {
        alert('사원번호와 발령일을 입력하세요.');
        return;
    }
    if (!confirm('발령을 등록하시겠습니까?')) return;
    gfn_ajax('/hr/appoint/save.do', param, function() {
        alert('등록되었습니다.');
        fn_search();
    });
}

/* 확정 시 SP_APPT_CONFIRM 이 사원 부서를 변경한다 */
function fn_confirmAppt() {
    var row = gfn_getSelectedRow('#grdAppt');
    if (!row) {
        alert('확정할 발령을 선택하세요.');
        return;
    }
    if (!confirm('발령을 확정하면 사원 부서가 변경됩니다. 확정하시겠습니까?')) return;
    gfn_ajax('/hr/appoint/confirm.do', { apptNo: row.apptNo }, function(res) {
        alert(res.result === 'OK' ? '확정되었습니다.' : '확정 실패: ' + res.result);
        fn_search();
    });
}
