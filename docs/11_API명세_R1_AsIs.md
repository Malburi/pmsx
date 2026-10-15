# 11. API 명세 — R1 (As-Is 뷰어 웹 1차)

- 문서 버전: v0.1 (2026-10-15). 08·10 명세의 규칙을 따른다.
- 관련 요구사항: FR-AS-003~026, FR-CL-002~022, NFR-PRF-001·002
- **08 명세 변경**: `WorkflowStep.edge` 에 `edgeId: string` 추가(수동 보정용), `ElementSummary` 에 `manual: boolean`, `hidden: boolean` 추가.

## 1. 핫스팟 편집기·연결 보정 (FR-AS-019·020·026)
권한: PM·PL·ANALYST(본인 담당 화면)·DEV·OPERATOR·PARTNER (06 매트릭스 "핫스팟·연결 수동 보정")

| Method | Path | 요청 → 응답 |
|---|---|---|
| POST | `/screens/{screenId}/elements` | `{ elementCode, label, type, box, binding? }` → `ElementSummary` (manual=true, seqNo 자동 부여) |
| PUT | `/elements/{elementId}` | `{ label?, type?, box?, binding? }` → `ElementSummary` (manual=true 로 바뀌어 재수집 시 보존) |
| DELETE | `/elements/{elementId}` | 204. 수동 요소는 삭제, 수집 요소는 `hidden=true` (재수집해도 숨김 유지) |
| POST | `/elements/{elementId}/restore` | 숨김 해제 → `ElementSummary` |
| GET | `/systems/{systemId}/nodes?q=&type=FN\|API\|SQL\|SP\|TABLE\|SCREEN&page=&size=` | → `Page<NodeSummary>` (연결 대상 고르기용) |
| POST | `/edges` | `EdgeCreate` → `EdgeInfo` (source=manual, confidence=1) |
| PATCH | `/edges/{edgeId}` | `{ status?: 'active'\|'rejected'; confirmed?: boolean; condition?: string; crud?: string }` → `EdgeInfo` |
| DELETE | `/edges/{edgeId}` | 204 (수동 엣지만. 수집 엣지는 PATCH status=rejected) |
| POST | `/screens/{screenId}/captures` | multipart `file`(png/jpg), `width?`, `height?` → `ScreenDetail['capture']` (수동 캡처, 최신 캡처로 사용) |

```ts
interface NodeSummary { nodeId: string; nodeType: 'FN'|'API'|'SQL'|'SP'|'TABLE'|'SCREEN'; key: string; name: string; file: string | null }
interface EdgeCreate {
  from: { elementId?: string; nodeId?: string; screenId?: string }       // 하나만
  to: { nodeId?: string; newNode?: { nodeType: NodeSummary['nodeType']; name: string; file?: string } }
  kind: 'event'|'calls'|'request'|'handles'|'executes'|'accesses'|'navigates'|'binds'
  event?: string; condition?: string; crud?: string; order?: number
}
interface EdgeInfo { edgeId: string; fromId: string; toId: string; kind: string; event: string | null; source: 'static'|'dynamic'|'manual'; confidence: number; status: 'active'|'rejected'; confirmed: boolean; condition: string | null; crud: string | null }
```
- `confirmed=true` 인 엣지는 신뢰도와 관계없이 실선 표시, 재수집 시에도 confirmed 유지.
- 모든 변경은 감사 로그 UPDATE/CREATE/DELETE.

## 2. 메모·분석 상태·진척 (FR-AS-021~023)

| Method | Path | 요청 → 응답 |
|---|---|---|
| GET | `/notes?targetType=SCREEN\|ELEMENT\|NODE&targetId=` | → `Note[]` (최신순) |
| POST | `/notes` | `{ targetType, targetId, content }` (content = Markdown, 최대 20,000자) → `Note` |
| PUT / DELETE | `/notes/{noteId}` | 작성자 또는 PM·PL |
| PATCH | `/screens/{screenId}/analysis` | (08 확장) `{ status?, ownerId?, reviewerId?, comment? }` |
| GET | `/screens/{screenId}/analysis/history` | → `{ at, user: {userId,name}, fromStatus, toStatus, comment }[]` |
| POST | `/systems/{systemId}/analysis/assign` | `{ screenIds: string[], ownerId: string \| null, reviewerId?: string \| null }` → `{ updated: number }` (PM·PL) |
| GET | `/systems/{systemId}/analysis/progress` | → `AnalysisProgress` |
| GET | `/systems/{systemId}/analysis/screens?status=&ownerId=&menuId=&q=&page=&size=` | → `Page<AnalysisRow>` (진척 화면 그리드) |

```ts
interface Note { noteId: string; targetType: string; targetId: string; content: string; author: { userId: string; name: string }; createdAt: string; updatedAt: string }
interface AnalysisProgress {
  total: number; byStatus: Record<'AUTO'|'WORKING'|'REVIEW'|'DONE', number>
  byOwner: { owner: { userId: string; name: string } | null; total: number; byStatus: Record<string, number> }[]
  byMenu: { menuId: string; name: string; total: number; done: number }[]       // 1단계 메뉴 기준
  unmatchedElements: number; unconfirmedEdges: number                            // 미매칭 요소 수, 신뢰도<0.9 미확정 엣지 수
}
interface AnalysisRow { screenId: string; screenCode: string; name: string; menuPath: string[]; status: string; owner: {userId:string;name:string} | null; reviewer: {userId:string;name:string} | null; elementCount: number; unmatchedCount: number; updatedAt: string }
```

## 3. 테이블 정의서·ERD (FR-AS-017·018)

| Method | Path | 요청 → 응답 |
|---|---|---|
| GET | `/systems/{systemId}/tables?q=&subjectArea=&page=&size=` | → `Page<TableSummary>` |
| GET | `/tables/{tableId}` | → `TableDetail` (tableId = TABLE 노드 ID) |
| PUT | `/tables/{tableId}` | `{ subjectArea?, comment? }` → `TableSummary` (수동 보정 보존) |
| GET | `/systems/{systemId}/subject-areas` | → `{ name: string; tableCount: number }[]` |
| GET | `/systems/{systemId}/erd?subjectArea=&tableIds=a,b&includeInferred=true` | → `Erd` (최대 300 테이블) |
| POST | `/relations` | `{ childTableId, parentTableId, columns: [string,string][] }` → `Relation` (kind=manual) |
| PATCH | `/relations/{relationId}` | `{ decision: 'confirmed'\|'rejected'\|'pending' }` → `Relation` |

```ts
interface TableSummary { tableId: string; schema: string; name: string; comment: string | null; subjectArea: string | null; columnCount: number; usedByScreens: number }
interface TableDetail extends TableSummary {
  columns: { name: string; dataType: string; nullable: boolean; pk: boolean; fk: boolean; default: string | null; comment: string | null }[]
  indexes: { name: string; columns: string[]; unique: boolean }[]
  relations: Relation[]                                                           // 이 테이블이 자식 또는 부모인 관계
  usedBy: { screenId: string; screenName: string; elementId: string | null; elementLabel: string | null; crud: string; sqlName: string }[]
}
interface Relation { relationId: string; childTableId: string; parentTableId: string; childName: string; parentName: string; columns: [string, string][]; kind: 'fk'|'inferred'|'manual'; evidenceCount: number | null; confidence: number | null; decision: 'pending'|'confirmed'|'rejected' }
interface Erd { tables: { tableId: string; name: string; comment: string | null; subjectArea: string | null; columns: { name: string; dataType: string; pk: boolean; fk: boolean }[] }[]; relations: Relation[] }
```

## 4. 시스템 단위 분석 뷰 (FR-AS-010·014·015·016·024)

| Method | Path | 응답 |
|---|---|---|
| GET | `/systems/{systemId}/crud?by=screen\|element&menuId=` | → `CrudMatrix` (08 형식, rows가 화면 또는 요소) |
| GET | `/systems/{systemId}/navigation` | → `{ nodes: { screenId, screenCode, name, kind, status }[]; links: { fromScreenId, toScreenId, via: string \| null, kind: 'popup'\|'link'\|'tab' }[] }` |
| GET | `/nodes/{nodeId}/call-tree?direction=down\|up&depth=3` | → `CallTreeNode` (depth 최대 6, 노드 200개 상한) |
| GET | `/nodes/{nodeId}/usage` | 공통 함수 사용처 → `{ screenId, screenName, elementId, elementLabel, path: string[] }[]` |
| GET | `/runs/{runId}/diff` | → `RunDiff` |

```ts
interface CallTreeNode { nodeId: string; nodeType: string; name: string; file: string | null; edgeKind: string | null; truncated: boolean; children: CallTreeNode[] }
interface RunDiff { runId: string; prevRunId: string | null; screens: { added: Ref[]; removed: Ref[] }; elements: { added: Ref[]; removed: Ref[] }; edges: { added: number; removed: number; samples: { from: string; to: string; kind: string; change: 'added'|'removed' }[] } }
interface Ref { id: string | null; key: string; label: string }
```

## 5. 산출물 출력 (FR-AS-025)

| Method | Path | 응답 |
|---|---|---|
| GET | `/systems/{systemId}/deliverables` | → `{ type, name, formats: ('xlsx'\|'pptx')[] }[]` |
| GET | `/systems/{systemId}/deliverables/{type}?format=xlsx\|pptx&screenIds=a,b&status=DONE&templateId=` | → 파일 다운로드 (감사 로그 DOWNLOAD). 300화면 상한 |

| type | 이름 | 내용 |
|---|---|---|
| `screen-spec` | 화면정의서 | 화면당 1시트/1슬라이드: 캡처(핫스팟 번호 합성) + 요소 표 + 이벤트 표 + 메모 |
| `program-spec` | 프로그램 사양서 | 화면별 이벤트 → 핸들러 → API → 서버 함수 → SQL → 테이블(CRUD) 표 |
| `table-spec` | 테이블 정의서 | 테이블당 컬럼 표 + 관계 + 사용 화면 |
| `crud-matrix` | CRUD 매트릭스 | 화면 × 테이블 |
| `interface-list` | 인터페이스 목록 | API 목록(메서드·경로·서버 함수·호출 화면) |

- `templateId` 를 주면 10 명세 엑셀 템플릿 매핑을 따른다(xlsx). pptx는 기본 양식.

## 6. 성능 (NFR-PRF-001·002)
- 서버에 합성 데이터 생성 도구(테스트 프로필 전용): 화면 5,000 · 요소 200,000 · 노드 300,000 · 엣지 1,000,000.
- 목표: `GET /screens/{id}` ≤ 2초, `GET /elements/{id}/workflow` ≤ 1초, 메뉴 트리 ≤ 2초, 검색 ≤ 1초 (로컬 H2 및 PostgreSQL 모두 측정, 결과를 README에 기록).

## 7. 수집기 R1 (FR-CL-002·010·011·016·017·019)
- **Nexacro**(N/14/17): `.xfdl`(Form/Layout 좌표, 컴포넌트, 이벤트 속성, Dataset 정의·바인딩), `.xjs` 공통 스크립트, `transaction("svcId", "url", inDs, outDs, args, callback)`, `gfn_*` 래퍼. Nexacro 런타임 없이도 **XFDL 좌표로 와이어프레임 PNG 생성**해 캡처로 사용(`captures[].state = "wireframe"`).
- **Vue 2/3, React**: 라우터(경로↔컴포넌트), SFC/JSX 이벤트(`@click`, `onClick`), API 모듈 함수(axios 호출 URL), 컴포넌트 import 그래프. SPA 동적 캡처는 라우트 순회.
- **SQL/SP**: MyBatis `<include refid>`, `<foreach>`, `${}` 경고, PL/SQL·T-SQL SP 본문의 테이블 CRUD, SP→SP 호출.
- **harness 연계**: `_workspace/index/*.json` (생성기: `~/.claude/plugins/marketplaces/total-ito/agents/lib/build-index.mjs`) → 중간모델 변환.
- 메뉴 수집: 메뉴 SQL(DB 직접), 라우터, Nexacro 메뉴 XML/데이터셋.

## v0.2 변경 (2026-10-15, 웹·수집기 연동 결과)
- `ScreenDetail.capture` 에 `scale: number`, `state: string | null` 추가 (`"wireframe"` = 소스 좌표로 그린 와이어프레임). 화면 `sourceFile`/url 은 HTTP 경로가 아닐 수 있음(`PY::PY0101.xfdl`).
- `WorkflowStep.edge` 에 `confirmed: boolean`, `status: 'active' | 'rejected'` 추가 — 기각 엣지도 내려주고 웹이 취소선 표시.
- `CrudMatrix.rows[]` 에 `screenId?: string`, `sub?: string` 추가(by=element). by=screen 이면 `elementId` 자리에 screenId.
- 산출물 고객사 템플릿 = `ExcelTemplate.datasetId` 가 산출물 type(`screen-spec` 등)과 같은 템플릿.
- ERD `tableIds` 지정 시 선택 테이블 + 1단계 관계 테이블 포함.
- 기간 필터(from/to)는 `YYYY-MM-DD` (서버는 ISO datetime 도 허용). 파라미터 형식 오류는 400 `VALIDATION`.
- 통계(`RunSummary.stats`) 키 추가: `screensWireframe`, `warnings`.
