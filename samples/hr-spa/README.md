# 샘플 교육관리시스템(ED) — Vue 3 SPA + React + Spring REST

수집기 Vue/React 파서와 SPA 동적 캡처 검증용.

| 경로 | 내용 |
|---|---|
| `web/` | Vite + Vue 3 앱 (vue-router 중첩·lazy 라우트, `<script setup>` / Options API 대화상자, axios 인스턴스 `baseURL: '/api'`, API 모듈(이름·default 객체 내보내기), 템플릿 리터럴·문자열 결합 URL, `fetch`, `router.push`) |
| `web/dist/` | 빌드 결과 (mock 서버가 서빙) |
| `react/` | React(JSX) 화면 — **정적 분석만** (`createBrowserRouter` 객체 라우트, `useState`/`useEffect`, `setOpen(true)` 대화상자) |
| `src/main/java` | `@RestController` + `@GetMapping("/courses/{courseId}")` 등 REST (메서드별 매핑) |
| `src/main/resources/mapper/ed` | MyBatis — 다른 namespace 의 `<sql>` 조각 `<include refid="ed.common.pagingHead"/>` |
| `mock_server.py` | dist 서빙(SPA fallback) + `/api/**` JSON, 쓰기 요청 도달 시 `writes.log` 기록 |

```bash
cd web && npm install && npm run build && cd ..     # dist 가 없을 때만
python mock_server.py                                # :8092
cd ../../pmsx-collector
.venv/Scripts/python -m pmsx_collector run -c ../samples/hr-spa/collector.yaml
```
기대 결과: 화면 6(라우트 4 + 대화상자 2, 캡처 3 — React `/cert` 는 `capture.excludeUrls`), 메뉴는 라우트 meta 로 생성,
`수강신청` 클릭의 `POST /api/edu/applies` 차단(쓰기 0건), 관측 경로 `/api/edu/courses/C2026-001` → `{courseId}` 로 합침.
