/**
 * WebSquare 프로젝트 공통 함수 (gcm.*) — 모든 화면이 config 로 로드한다고 가정.
 */
var gcm = {};

/** 서브미션 실행 래퍼 (로딩바·세션 체크). 수집기 설정 websquare.submissionCallers 로 서브미션 id 인자를 해석한다. */
gcm.executeSubmission = function(sbmId, opts) {
    gcm.showLoading();
    $p.executeSubmission(sbmId);
};

/** 공통코드 조회 → 셀렉트박스 채우기 */
gcm.loadCode = function(grpCd, compId) {
    $p.ajax({
        action: "/bw/cmm/code/list.do",
        method: "post",
        requestData: { GRP_CD: grpCd },
        success: function(res) { $p.getComponentById(compId).setNodeSet(res.list, "CODE_NM", "CODE"); }
    });
};

gcm.excelDownload = function(grid, fileName) {
    grid.advancedExcelDownload({ fileName: fileName + ".xlsx" });
};

gcm.alert = function(msg) {
    $p.alert(msg);
};

gcm.showLoading = function() {
};
