# 샘플 인사 데스크톱(HW) — .NET 7 WinForms + ADO.NET / Dapper / EF 스타일 + MSSQL

수집기 WinForms 파서·.NET 데이터 접근 파서·**데스크톱 캡처(UI Automation)** 검증용 가상 레거시. **DB 에 접속하지 않는다** — 모든 데이터는 메모리 목업(`AppConfig.UseMock = true`), ADO.NET·Dapper·EF 는 `Data/Stubs.cs` 의 같은 모양 스텁이라 NuGet 패키지 없이 빌드된다.

| 경로 | 내용 |
|---|---|
| `src/HrWin/Forms/MainForm*.cs` | 메인 창 `MenuStrip`(인사관리 › 사원관리·부서관리 / 근태관리 › 근태현황·근태신청). 근태신청은 화면 없이 `MessageBox` 만 띄움(의도된 실패) |
| `src/HrWin/Forms/EmpForm*.cs` | 사원관리: GroupBox 검색조건, `DataGridView`(열 4개), 저장(`MessageBox YesNo` → `IEmpService` → `EmpDao` → `USP_EMP_SAVE`), 삭제(Dapper `Execute`), 사원찾기(`ShowDialog` 팝업), 핸들러 없는 `엑셀` 버튼 |
| `src/HrWin/Forms/DeptForm*.cs` | 부서관리: `TabControl` 2탭(두 번째 탭 요소는 캡처 시 숨음 → 좌표 보정 검증), EF `DbSet.Add` / `Find`+대입+`SaveChanges` |
| `src/HrWin/Forms/AttendForm*.cs` | 근태현황: Panel 검색조건, `StringBuilder` 로 조립한 SQL + Dapper `Query`, 월마감(`OKCancel` → Dapper `commandType: StoredProcedure` → `USP_ATTEND_CLOSE` → `EXEC USP_WRITE_LOG`) |
| `src/HrWin/Forms/EmpSearchPopup*.cs` | 사원검색 팝업 (`FormBorderStyle.FixedDialog`) |
| `src/HrWin/Services`, `Data` | 서비스(인터페이스 필드 + 생성자 주입) → DAO(ADO.NET `SqlCommand` 텍스트·`CommandText = const`·저장 프로시저) |
| `db/ddl.sql` | T-SQL DDL(`GO` 배치, `sp_addextendedproperty` 코멘트), FK 일부 누락(추정 관계), 프로시저 3개 |

```bash
cd src/HrWin && dotnet build -c Release          # bin/Release/net7.0-windows/HrWin.exe
cd ../../../../pmsx-collector
.venv/Scripts/python -m pmsx_collector run -c ../samples/hr-winforms/collector.yaml   # 정적 분석 + 와이어프레임
# 데스크톱 캡처까지: collector.yaml 의 capture.enabled: true (앱을 띄워 메뉴 순회·캡처 후 종료)
```
기대 결과(캡처 켬): 화면 5(캡처 5), 요소 32(실제 화면 매칭 25), 버튼→핸들러 17/18, 클릭 8, 저장 버튼 → `confirm("저장하시겠습니까?")` → `EmpService.SaveEmp` → `EmpDao.SaveEmp` → `USP_EMP_SAVE` → `TB_EMP`(CU)·`TB_EMP_HIST`(C).
의도된 실패 2: 메뉴 '근태신청' 화면 없음(정적) + 캡처 시 메시지 상자.
