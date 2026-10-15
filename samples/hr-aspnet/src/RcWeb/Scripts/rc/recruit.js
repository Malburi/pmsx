// 채용공고 목록 — 페이지 메서드(WebMethod) 호출
$(function () {
    $("#btnCount").on("click", fn_loadCount);
    fn_loadCount();
});

function fn_loadCount() {
    $.ajax({
        url: "/Recruit/RecruitList.aspx/GetOpenCount",
        type: "POST",
        contentType: "application/json; charset=utf-8",
        success: function (res) { $("#spnCount").text(res.d); }
    });
}
