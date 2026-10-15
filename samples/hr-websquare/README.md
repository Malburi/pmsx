# 샘플 복리후생(BW) — WebSquare 5 + Spring + MyBatis + Oracle

수집기 WebSquare 파서 검증용 가상 레거시. WebSquare 엔진 없이 **정적 분석 + 화면 XML 좌표 와이어프레임**으로 수집한다.

| 경로 | 내용 |
|---|---|
| `ui/BW/BW0101.xml` | 복지포인트 신청: `xf:group` 검색조건(좌표 누적), `w2:gridView`(header/gridBody), `xf:submission` 3개(`ev:submitdone` 콜백), `$p.executeSubmission` / 공통 래퍼 `gcm.executeSubmission`, `confirm`, `$p.openPopup`(사원검색 + **소스 없는 BW0103P01** — 의도된 실패), 핸들러 없는 `인쇄` |
| `ui/BW/BW0101P01.xml` | 사원검색 팝업 |
| `ui/BW/BW0102.xml` | 기준관리: `w2:tabControl` + `w2:content`(탭 기준 좌표), `$p.ajax({action})` → SP 호출 |
| `ui/cm/js/gcm.js` | 공통 `gcm.*` (`gcm.loadCode` → `$p.ajax`) |
| `src/main/java`, `mapper` | Spring(`@RequestMapping` 클래스+메서드, `@RestController`+`@PostMapping`, `@RequiredArgsConstructor`, SqlSession 문자열 호출) → MyBatis(MERGE, `{ call SP }`) |
| `db/ddl.sql`, `db/menu.csv` | Oracle DDL(FK 일부), 메뉴(정산 BW0104 는 소스 없음 — 의도된 실패) |

```bash
cd ../../pmsx-collector
.venv/Scripts/python -m pmsx_collector run -c ../samples/hr-websquare/collector.yaml
```
기대 결과: 화면 3(와이어프레임 3), 요소 23, 버튼→핸들러 12/13, 저장 → `api:POST /bw/req/save.do`(confirm) → Controller → ServiceImpl → `insertReq`/`updateReq`/`insertReqHist` → `TB_WELFARE_REQ`(C/U)·`TB_WELFARE_REQ_HIST`(C), 콜백 → 재조회. 실패 2(의도).
