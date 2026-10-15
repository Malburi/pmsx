# 샘플 채용관리(RC) — ASP.NET WebForms + ASP.NET MVC 5 + ADO.NET / Dapper + MSSQL

수집기 ASP.NET 파서 검증용 가상 레거시. **소스만** 있다(빌드·실행 불필요).

| 경로 | 내용 |
|---|---|
| `src/RcWeb/Recruit/RecruitList.aspx(.cs)` | WebForms 목록: 서버 컨트롤(`asp:TextBox`·`DropDownList AutoPostBack`·`GridView BoundField`·`Button`), `OnClick`/`OnSelectedIndexChanged`/`OnRowCommand` 포스트백, `OnClientClick="return confirm(…)"`, `Response.Redirect`, `[WebMethod]` + 외부 JS `$.ajax`, 핸들러 없는 `엑셀` |
| `src/RcWeb/Recruit/RecruitEdit.aspx(.cs)` | 등록·수정: `Page_Load`, 저장(confirm → Biz → Dac → `USP_RECRUIT_SAVE`) |
| `src/RcWeb/Biz`, `Dac` | BLL → DAL(ADO.NET: `using (var cmd = new SqlCommand(sql, conn))`, 문자열 `+` 연결, `CommandType.StoredProcedure`, `CreateCommand()`+`CommandText`, `string.Format`) |
| `src/RcMvc/Controllers/ApplicantController.cs` | MVC: 관례 라우팅 `/Applicant/List`, `[HttpPost]`, `[Route("applicant/pass/{id}")]`, `RedirectToAction` |
| `src/RcMvc/Views/Applicant/*.cshtml` | Razor(`@Html.TextBoxFor`·`@Url.Action`·`@section Scripts`), jQuery `$.ajax`/`$.post("/applicant/pass/" + id)`, `window.open` 상세 팝업 |
| `src/RcMvc/Services`, `Repositories` | 인터페이스 생성자 주입 → Dapper(`Query`·`QueryFirstOrDefault`·`Execute` SQL/SP) |
| `db/ddl.sql`, `db/menu.csv` | T-SQL DDL(FK 일부), 메뉴(`/Applicant` → Index 별칭) |

```bash
cd ../../pmsx-collector
.venv/Scripts/python -m pmsx_collector run -c ../samples/hr-aspnet/collector.yaml
```
기대 결과: 화면 4(WebForms 2 · MVC 2, 캡처 없음), 요소 24, 저장 → `confirm("저장하시겠습니까?")` → 코드비하인드 → `RecruitBiz` → `RecruitDac.SaveRecruit` → `USP_RECRUIT_SAVE` → `TB_RECRUIT`(CU) → `RecruitList` 화면 이동. 합격처리 → `api:POST /applicant/pass/{id}` → `ApplicantController.Pass` → … → `TB_APPLICANT`(U)·`TB_APPLICANT_HIST`(C). 실패 0.
