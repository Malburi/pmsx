# 샘플 근태(AT) — XPlatform + MiPlatform 혼합 + Spring + MyBatis + Oracle

수집기 MiPlatform/XPlatform 파서 검증용 가상 레거시. 전용 런타임 없이 **정적 분석 + 화면 좌표 와이어프레임**으로 수집한다.

| 경로 | 내용 |
|---|---|
| `xp/default_typedef.xml` | Services(`AT` 폼 폴더, `lib` 스크립트, `svc` 서버) — 자동 인식 |
| `xp/AT/AT0101.xfdl` | **XPlatform** 근태신청: Form 바로 아래 컴포넌트(`position="absolute l t r b"`), Div 안 검색조건, Grid Formats, `include "lib::comLib.xjs"`, `transaction()`·`gfn_transaction`, `confirm`, `gfn_openPopup` → 팝업 |
| `xp/AT/AT0101P01.xfdl` | 사원선택 팝업 |
| `xp/AT/AT0102.xml` | **MiPlatform** 근태승인: `<Window><Form>` 대문자 속성(`Left/Top/OnClick`), `Datasets/colinfo`, `Edit BindDataset+Column`, `Tab/Contents/TabPage`, `contents/format` 그리드, `#include`, `Transaction()`·`Confirm()`·`Dialog()`, `OnLoadCompleted` |
| `src/main/java`, `mapper` | `PlatformData` 컨트롤러 → Service / 컨트롤러가 Mapper 직접 호출(레거시 패턴) / SqlSession 문자열 호출 |
| `db/ddl.sql`, `db/menu.csv` | Oracle DDL(FK 없음 → 추정 관계), 메뉴(폼 URL `AT::AT0101.xfdl`) |

```bash
cd ../../pmsx-collector
.venv/Scripts/python -m pmsx_collector run -c ../samples/hr-xplatform/collector.yaml
```
기대 결과: 화면 3(와이어프레임 3, uiTech xplatform 2 · miplatform 1), 요소 20, 버튼→핸들러 10/10, 승인 버튼(탭 안) → `confirm` → `api:POST /at/appr/approve.do` → `TB_ATT_APPR`(U), 콜백 → 재조회. 실패 0.
