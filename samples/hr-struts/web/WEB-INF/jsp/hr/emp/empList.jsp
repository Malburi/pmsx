<%@ page contentType="text/html;charset=euc-kr" %>
<%@ include file="/WEB-INF/jsp/common/incHead.jspf" %>
<html>
<head>
<script type="text/javascript" src="<%= JS_PATH %>hr/emp_list.js"></script>
</head>
<body onload="onInit();">
<form name="form1" action="?method=list" method="post">
<table summary="사원 목록">
  <caption>사원 목록</caption>
  <tr><td><input type="text" name="empNm" onkeydown="onCheckKey();" /></td></tr>
</table>
<input type="image" src="/img/btn_search.gif" alt="조회" onclick="onSearch();return false;" />
<img src="/img/btn_save.gif" alt="저장" onclick="onSave();" />
<a href="javascript:onEdit('1');">수정</a>
<button id="btnPop" onclick="onPop();">사원 찾기</button>
</form>
</body>
</html>
