/**
 * 공통 스크립트 (common.js)
 *  - gfn_ajax / gfn_popup / gfn_setCombo / gfn_bindGrid 등 화면 공통 함수
 * @since 2019.01
 */

/** JSON POST 호출 */
function gfn_ajax(url, data, callback) {
    $.ajax({
        url: url,
        type: 'POST',
        contentType: 'application/json; charset=UTF-8',
        data: JSON.stringify(data || {}),
        dataType: 'json',
        success: function(res) {
            if (typeof callback === 'function') callback(res);
        },
        error: function(xhr) {
            if (xhr.status === 401) { location.href = '/login.do'; return; }
            alert('처리 중 오류가 발생했습니다. (' + xhr.status + ')');
        }
    });
}

/** 콤보 채우기 (GET) */
function gfn_setCombo(selector, url, param, allText) {
    $.get(url, param || {}, function(res) {
        var $sel = $(selector).empty();
        if (allText) $sel.append($('<option/>').val('').text(allText));
        $.each(res.list || [], function(i, item) {
            $sel.append($('<option/>').val(item.cd).text(item.cdNm));
        });
    }, 'json');
}

/** 그리드 바인딩 */
function gfn_bindGrid(selector, list, cols) {
    var $tbody = $(selector).find('tbody').empty();
    $.each(list || [], function(i, row) {
        var $tr = $('<tr/>').data('row', row).attr('data-status', 'R');
        $.each(cols, function(j, col) {
            $tr.append($('<td/>').text(row[col] == null ? '' : row[col]));
        });
        $tbody.append($tr);
    });
}

/** 그리드 행 선택 처리 */
$(document).on('click', 'table.grid tbody tr', function() {
    $(this).addClass('selected').siblings().removeClass('selected');
});

/** 신규 행 추가 */
function gfn_addRow(selector, defaults) {
    var $table = $(selector);
    var cols = $table.find('thead th').map(function() { return $(this).data('col'); }).get();
    var row = $.extend({}, defaults);
    var $tr = $('<tr/>').data('row', row).attr('data-status', 'I');
    $.each(cols, function(j, col) {
        $tr.append($('<td contenteditable="true"/>').text(row[col] || ''));
    });
    $table.find('tbody').prepend($tr);
}

function gfn_getChangedRows(selector) {
    var rows = [];
    $(selector).find('tbody tr').each(function() {
        var status = $(this).attr('data-status');
        if (status === 'I' || status === 'U') {
            rows.push($.extend({ rowStatus: status }, $(this).data('row')));
        }
    });
    return rows;
}

function gfn_getSelectedRow(selector) {
    var $tr = $(selector).find('tbody tr.selected');
    return $tr.length ? $tr.data('row') : null;
}

function gfn_getRowData(tr) {
    return $(tr).data('row');
}

/** 필수값 검증 */
function gfn_validate(rows, required) {
    for (var i = 0; i < rows.length; i++) {
        for (var j = 0; j < required.length; j++) {
            if (!rows[i][required[j]]) {
                alert('필수 입력값이 누락되었습니다. (' + required[j] + ')');
                return false;
            }
        }
    }
    return true;
}

/** 팝업 열기 - 선택 결과는 callback 으로 전달 */
function gfn_popup(url, width, height, callback) {
    window.__popupCallback = callback;
    var left = (screen.width - width) / 2, top = (screen.height - height) / 2;
    return window.open(url, 'popup', 'width=' + width + ',height=' + height + ',left=' + left + ',top=' + top);
}

/** 팝업에서 부모 콜백 호출 후 닫기 */
function gfn_closePopup(ret) {
    if (window.opener && typeof window.opener.__popupCallback === 'function') {
        window.opener.__popupCallback(ret);
    }
    window.close();
}

/** 엑셀 다운로드 (GET) */
function gfn_excel(url, param) {
    location.href = url + '?' + $.param(param || {});
}

function gfn_getParam(name) {
    var m = new RegExp('[?&]' + name + '=([^&]*)').exec(location.search);
    return m ? decodeURIComponent(m[1]) : '';
}

function gfn_today() {
    var d = new Date();
    return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2);
}

/** form → object */
$.fn.serializeObject = function() {
    var obj = {};
    $.each(this.serializeArray(), function(i, f) { obj[f.name] = f.value; });
    return obj;
};
