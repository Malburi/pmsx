<%@ page contentType="text/html;charset=utf-8" %>
<html><head><title>사원 찾기</title>
<script type="text/javascript">
function onSelect() {
    opener.onPopupSave("20190012");
    self.close();
}
</script></head>
<body>
<input type="button" value="선택" onclick="onSelect();" />
</body></html>
