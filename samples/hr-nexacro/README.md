# 샘플 급여시스템(PY) — Nexacro N/14 혼합

수집기 Nexacro 파서 검증용 가상 레거시. Nexacro 런타임 없이 **정적 분석 + XFDL 좌표 와이어프레임**으로 수집한다.

| 경로 | 내용 |
|---|---|
| `nexacro/PY/PY0101.xfdl` | 급여계산 (N 형식: Div 안 검색조건, Grid, `gfn_transaction`·`transaction`, 확인창, 팝업, 콜백) |
| `nexacro/PY/PY0101P01.xfdl` | 사원선택 팝업 (**Nexacro 14 형식**: `position="absolute …"`, `function` 선언, 타입 표기) |
| `nexacro/PY/PY0102.xfdl` | 급여명세 (Tab 2개 + 각 Grid, 핸들러 없는 `인쇄` 버튼) |
| `nexacro/PY/PY0103.xfdl` | 수당관리 (`right/bottom` 좌표, 공통코드 조회, 이력 팝업 — 팝업 소스 PY0103P01 은 일부러 없음) |
| `nexacro/Lib/lib_comm.xjs` | 공통 스크립트 (`include "Lib::lib_comm.xjs"`) |
| `nexacro/Base/gds_menu.xml` | 메뉴 Dataset export (세율관리 PY0104 는 소스 없음) |
| `src/main/java`, `src/main/resources/mapper` | Nexacro UI Adapter 컨트롤러(`NexacroResult`) → Service(필드·생성자·`@RequiredArgsConstructor` 주입) → MyBatis (`<include>`·`<choose>`·`<foreach>`·`${}`·SP 호출) |
| `db/ddl.sql`, `db/procedures.sql` | Oracle DDL(FK 일부만), `SP_PAY_CALC` → `SP_PAY_TAX` 호출, 정의 없는 `PKG_PAY_LOG.WRITE_LOG` |

```bash
cd ../../pmsx-collector
.venv/Scripts/python -m pmsx_collector run -c ../samples/hr-nexacro/collector.yaml
# 또는 dist/pmsx-collector.exe run -c ../samples/hr-nexacro/collector.yaml
```
기대 결과: 화면 4(와이어프레임 4), 버튼 핸들러 16/17, `btn_calc` → `/py/calc/calculate.do` → … → `SP_PAY_CALC` → `SP_PAY_TAX` → `TB_TAX_RATE`.
의도된 실패 2(PY0103P01 팝업 소스 없음, 메뉴 PY0104 소스 없음), 경고 2(`${sortCol}`, 외부 패키지 프로시저).
