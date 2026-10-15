// 지원자 관리 — 목록 렌더링 (공통)
function fn_render(list) {
    var $tb = $("#tblApplicant tbody").empty();
    $.each(list, function (i, a) {
        $("<tr>").data("id", a.ApplicantId)
            .append($("<td>").text(a.ApplicantId), $("<td>").text(a.Name), $("<td>").text(a.RecruitTitle), $("<td>").text(a.Status))
            .appendTo($tb);
    });
}
