var LIST_PATH = "?method=list";
var SAVE_PATH = "?method=save";
var frm;

function onInit() {
    frm = document.forms[0];
}

function onSearch() {
    frm.action = LIST_PATH;
    frm.submit();
}

function onSave() {
    if (confirm("저장하시겠습니까?")) {
        frm.action = SAVE_PATH + "&cmd=I";
        frm.submit();
    }
}

function onEdit(no) {
    location.href = "/hr/emp/EmpAction.do?method=edit&empNo=" + no;
}

function onPop() {
    var url = "?method=pop";
    popupWindow(url, "EMP_POP", "width=500");
}

function onPopupSave(empNo) {
    frm.empNo.value = empNo;
    frm.submit();
}
