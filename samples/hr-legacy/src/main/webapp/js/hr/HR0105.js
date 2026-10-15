/**
 * 교육이력관리 (HR0105)
 * 2024.03 신규 - 화면 모듈 객체 패턴, 위임 이벤트, 함수 표현식 사용
 */
var HR0105 = {
    init: function() {
        gfn_setCombo('#cboEduType', '/cmm/code/list.do?grpCd=EDU', {}, '전체');
        this.search();
    },

    search: function() {
        gfn_ajax('/hr/edu/list.do', $('#frmEdu').serializeObject(), function(res) {
            gfn_bindGrid('#grdEdu', res.list, ['empNo', 'empNm', 'eduTypeNm', 'eduNm', 'eduHours', 'instNm']);
        });
    },

    save() {
        var rows = gfn_getChangedRows('#grdEdu');
        if (rows.length == 0) { alert('변경된 내용이 없습니다.'); return; }
        if (!confirm('저장하시겠습니까?')) return;
        gfn_ajax('/hr/edu/save.do', { rows: rows }, HR0105.search);
    },

    openDetail: function() {
        var row = gfn_getRowData(this);
        gfn_popup('/hr/emp/HR0102.do?empNo=' + row.empNo, 700, 500);
    }
};

/* 검색조건 초기화 */
var fn_reset = function() {
    $('#frmEdu')[0].reset();
    HR0105.search();
};

/* 교육 통계 (연도별 이수시간) */
const fn_eduStat = () => {
    gfn_ajax('/hr/edu/summary.do', { eduYear: $('input[name="eduYear"]').val() }, function(res) {
        alert('총 이수시간: ' + res.totalHours + '시간');
    });
};

let fn_eduExcel = function() {
    gfn_excel('/hr/edu/excel.do', $('#frmEdu').serializeObject());
};

$(function() {
    HR0105.init();

    $(document).on('click', '#btnEduSearch', HR0105.search);         // 위임 바인딩
    $('.btn-excel').on('click', fn_eduExcel);                         // class 선택자
    $('button[data-act="reset"]').click(fn_reset);                    // 태그+속성 선택자
    $('#btnEduStat').on('click', fn_eduStat);                         // 화살표 함수
    $('#btnEduSave').on('click', HR0105.save);                        // 객체 메서드(축약형)
    $('input[name="eduYear"]').on('change', HR0105.search);           // 속성 선택자 + change
    $('#cboEduType').change(HR0105.search);                           // change 이벤트
    $('#grdEdu').on('dblclick', 'tbody tr', HR0105.openDetail);       // 그리드 행 더블클릭
});
