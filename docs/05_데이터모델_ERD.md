# 05. 데이터 모델 (핵심 ERD)

- 문서 버전: v0.1 (2026-10-14) — 논리 모델 초안. 물리 컬럼·인덱스는 R0/R1 착수 시 확정.
- 테이블 접두어 = 모듈 코드: `CM_` 공통 · `PJ_` 프로젝트 · `RS_` 인력 · `AS_` As-Is · `DL_` 산출물·추적성 · `QA_` 품질 · `SM_` 운영
- 공통 컬럼(모든 테이블): `CREATED_AT, CREATED_BY, UPDATED_AT, UPDATED_BY` (표에서는 생략)
- 멀티 DB(PostgreSQL·Oracle·Tibero·MSSQL) 호환: 표준 타입만 사용, 시퀀스 대신 앱에서 ID 생성(ULID/Snowflake), JSON은 CLOB/TEXT로 저장.

## 1. 공통·프로젝트

```mermaid
erDiagram
  CM_USER ||--o{ PJ_MEMBER : "참여"
  PJ_PROJECT ||--o{ PJ_MEMBER : ""
  CM_ROLE ||--o{ PJ_MEMBER : "프로젝트 역할"
  CM_USER }o--o{ CM_ROLE : "시스템 역할"
  PJ_PROJECT ||--o{ AS_SYSTEM : "분석 대상"
  PJ_PROJECT |o--o| PJ_PROJECT : "SI→SM 인수인계(origin)"
  CM_ATTACH }o--|| CM_FILE : ""

  CM_USER {
    string USER_ID PK
    string LOGIN_ID
    string NAME
    string ORG_TYPE "자사/협력사/고객"
    string AUTH_TYPE "LOCAL/LDAP"
  }
  CM_ROLE {
    string ROLE_ID PK
    string SCOPE "SYSTEM/PROJECT"
    string NAME
  }
  PJ_PROJECT {
    string PROJECT_ID PK
    string TYPE "SI/SM"
    string NAME
    string CUSTOMER
    date START_DT
    date END_DT
    decimal CONTRACT_MM
    string ORIGIN_PROJECT_ID FK
  }
  PJ_MEMBER {
    string PROJECT_ID PK
    string USER_ID PK
    string ROLE_ID
    date IN_DT
    date OUT_DT
  }
  CM_FILE {
    string FILE_ID PK
    string PATH
    string MIME
    long SIZE
    string SHA256
  }
  CM_ATTACH {
    string TARGET_TYPE
    string TARGET_ID
    string FILE_ID FK
    int VERSION
  }
```

## 2. As-Is 화면 분석 (핵심)

```mermaid
erDiagram
  AS_SYSTEM ||--o{ AS_COLLECT_RUN : "수집 회차"
  AS_SYSTEM ||--o{ AS_MENU : ""
  AS_MENU |o--o{ AS_MENU : "상위"
  AS_MENU }o--o| AS_SCREEN : ""
  AS_SYSTEM ||--o{ AS_SCREEN : ""
  AS_SCREEN ||--o{ AS_CAPTURE : ""
  AS_SCREEN ||--o{ AS_ELEMENT : ""
  AS_SCREEN ||--|| AS_ANALYSIS : "분석 상태"
  AS_SYSTEM ||--o{ AS_NODE : "함수/API/SQL/테이블"
  AS_NODE ||--o{ AS_EDGE : "from"
  AS_NODE ||--o{ AS_EDGE : "to"
  AS_TABLE ||--o{ AS_COLUMN : ""
  AS_TABLE ||--o{ AS_RELATION : ""
  AS_NOTE }o--|| AS_SCREEN : "메모 대상(화면/요소/노드)"

  AS_SYSTEM {
    string SYSTEM_ID PK
    string PROJECT_ID FK
    string CODE
    string NAME
    string UI_TECH
    string SERVER_TECH
    string DB_TYPE
  }
  AS_COLLECT_RUN {
    string RUN_ID PK
    string SYSTEM_ID FK
    int SEQ
    datetime COLLECTED_AT
    string PACKAGE_FILE_ID
    clob STATS_JSON
  }
  AS_MENU {
    string MENU_KEY PK
    string PARENT_KEY
    string NAME
    int SORT_ORD
    string SCREEN_KEY
  }
  AS_SCREEN {
    string SCREEN_KEY PK
    string SYSTEM_ID FK
    string SCREEN_ID
    string NAME
    string KIND "page/popup/tab"
    string SOURCE_FILE
    string LAST_RUN_ID
  }
  AS_CAPTURE {
    string CAPTURE_ID PK
    string SCREEN_KEY FK
    string RUN_ID
    string FILE_ID
    int WIDTH
    int HEIGHT
    string STATE
  }
  AS_ELEMENT {
    string ELEMENT_KEY PK
    string SCREEN_KEY FK
    int SEQ_NO "배지 번호"
    string ELEMENT_ID
    string LABEL
    string TYPE
    int X
    int Y
    int W
    int H
    string BINDING
    string MATCHED
    char MANUAL_YN
  }
  AS_NODE {
    string NODE_KEY PK
    string SYSTEM_ID FK
    string NODE_TYPE "FN/API/SQL/SP/TABLE/SCREEN"
    string NAME
    string FILE
    int LINE_START
    clob SNIPPET
  }
  AS_EDGE {
    string EDGE_ID PK
    string FROM_KEY "ELEMENT_KEY 또는 NODE_KEY"
    string TO_KEY
    string KIND
    string EVENT
    int ORD
    string COND
    string CRUD
    string COLUMNS
    string SOURCE "static/dynamic/manual"
    decimal CONFIDENCE
    string STATUS "active/rejected"
    string RUN_ID
  }
  AS_TABLE {
    string TABLE_KEY PK
    string SYSTEM_ID FK
    string SCHEMA_NM
    string TABLE_NM
    string COMMENT_TXT
    string SUBJECT_AREA
  }
  AS_COLUMN {
    string TABLE_KEY PK
    string COLUMN_NM PK
    string DATA_TYPE
    char PK_YN
    char NULL_YN
    string COMMENT_TXT
  }
  AS_RELATION {
    string REL_ID PK
    string CHILD_TABLE_KEY
    string PARENT_TABLE_KEY
    string COLUMN_PAIRS
    string KIND "fk/inferred"
    int EVIDENCE_CNT
    decimal CONFIDENCE
    string DECISION "pending/confirmed/rejected"
  }
  AS_ANALYSIS {
    string SCREEN_KEY PK
    string STATUS "AUTO/WORKING/REVIEW/DONE"
    string OWNER_ID
    string REVIEWER_ID
    datetime CONFIRMED_AT
  }
  AS_NOTE {
    string NOTE_ID PK
    string TARGET_KEY
    clob CONTENT
    string AUTHOR_ID
  }
```

**설계 포인트**
- 수집 결과의 안정 키(`scr:`, `el:`, `fn:` …)는 시스템 안에서만 유일하므로, 물리 PK는 **대리 ID(ULID)** 로 두고 `(SYSTEM_ID, KEY)` 유니크 제약을 건다 (위 그림의 `*_KEY PK` 표기는 논리 식별자). 재수집 시 키 기준 upsert, 수동 보정(`MANUAL_YN='Y'`, `SOURCE='manual'`)은 덮어쓰지 않음. → [08 API 명세](08_API명세_R0.md) §0
- 재수집에서 사라진 객체는 삭제하지 않고 `LAST_RUN_ID`가 갱신되지 않은 것으로 판별 → 회차 비교(FR-AS-024).
- 워크플로우 조회 = `AS_EDGE` 재귀 탐색 (PostgreSQL/Oracle 모두 재귀 CTE 지원). 깊이 제한·공통 함수 접기는 앱에서 처리.
- 대규모(연결선 100만 건) 대비: `AS_EDGE(FROM_KEY)`, `AS_EDGE(TO_KEY)` 인덱스, 시스템 단위 파티셔닝 검토.

## 3. 일정·인력

```mermaid
erDiagram
  PJ_PROJECT ||--o{ PJ_WBS : ""
  PJ_WBS |o--o{ PJ_WBS : "상위"
  PJ_WBS ||--o{ PJ_WBS_DEP : "선후행"
  PJ_PROJECT ||--o{ PJ_BASELINE : ""
  PJ_BASELINE ||--o{ PJ_BASELINE_ITEM : ""
  PJ_WBS ||--o{ RS_ASSIGN : "배정"
  CM_USER ||--o{ RS_ASSIGN : ""
  CM_USER ||--o{ RS_PLAN_MM : "월별 투입계획"
  CM_USER ||--o{ RS_TIMESHEET : "작업일지"
  CM_USER ||--o{ RS_SKILL : ""

  PJ_WBS {
    string WBS_ID PK
    string PROJECT_ID FK
    string PARENT_ID
    string WBS_NO
    string NAME
    date PLAN_START
    date PLAN_END
    date ACT_START
    date ACT_END
    decimal WEIGHT
    decimal PROGRESS
    char MILESTONE_YN
    string DELIVERABLE_ID
  }
  PJ_WBS_DEP {
    string PRED_ID PK
    string SUCC_ID PK
    string TYPE "FS/SS/FF/SF"
    int LAG_DAYS
  }
  RS_ASSIGN {
    string ASSIGN_ID PK
    string TARGET_TYPE "WBS/SCREEN/SR"
    string TARGET_ID
    string USER_ID
    decimal ALLOC_PCT
  }
  RS_PLAN_MM {
    string PROJECT_ID PK
    string USER_ID PK
    string YM PK
    decimal PLAN_MM
  }
  RS_TIMESHEET {
    string TS_ID PK
    string USER_ID
    date WORK_DT
    string TARGET_TYPE
    string TARGET_ID
    decimal HOURS
    string STATUS
  }
```
- `RS_ASSIGN`·`RS_TIMESHEET`의 `TARGET_TYPE`으로 WBS 태스크·화면 분석(`AS_SCREEN`)·SR을 같은 구조로 배정·공수 집계.

## 4. 품질·추적성·운영

```mermaid
erDiagram
  DL_TRACE_LINK }o--|| DL_REQUIREMENT : "예: 요구사항→화면"
  DL_REQUIREMENT ||--o{ QA_TEST_CASE : ""
  QA_TEST_CASE ||--o{ QA_TEST_RUN : ""
  QA_TEST_RUN ||--o{ QA_DEFECT : ""
  SM_SR ||--o{ SM_SLA_EVENT : ""
  SM_SLA_POLICY ||--o{ SM_SR : ""
  SM_SR }o--o| SM_KB : "KB 전환"
  SM_INCIDENT ||--o{ SM_SR : "관련"

  DL_TRACE_LINK {
    string LINK_ID PK
    string FROM_TYPE
    string FROM_ID
    string TO_TYPE
    string TO_ID
    string LINK_TYPE
  }
  DL_REQUIREMENT {
    string REQ_ID PK
    string PROJECT_ID
    string REQ_NO
    string TITLE
    string PRIORITY
    string STATUS
  }
  QA_DEFECT {
    string DEFECT_ID PK
    string SEVERITY
    string STATUS
    string TARGET_ELEMENT_KEY "핫스팟에서 등록 시"
  }
  SM_SR {
    string SR_ID PK
    string PROJECT_ID
    string TYPE
    string PRIORITY
    string STATUS
    string TARGET_SCREEN_KEY
    string TARGET_ELEMENT_KEY
    string POLICY_ID
    datetime RESPONSE_DUE
    datetime RESOLVE_DUE
  }
  SM_SLA_EVENT {
    string SR_ID
    string EVENT "START/PAUSE/RESUME/STOP"
    datetime AT
  }
```
- 이슈·리스크·CR·회의록·장애·변경·운영자산·KB 등 나머지 테이블은 일반 대장 구조로 R2/R3 상세 설계에서 추가.
- **`DL_TRACE_LINK`가 추적성의 단일 근간**: `FROM_TYPE/TO_TYPE` ∈ {REQ, SCREEN, ELEMENT, NODE, TABLE, TC, DEFECT, CR, SR, RELEASE}. 영향도 분석(FR-AS-028)은 `AS_EDGE`(코드 연결) + `DL_TRACE_LINK`(업무 연결)를 합쳐 탐색.

## 5. 구현 반영 내역 (물리 모델 변경 이력)

| 릴리스 | 추가/변경 | 비고 |
|---|---|---|
| R0 | `AS_EDGE.MODEL_SEQ` | 수집 결과 내 순서 보존(워크플로우 단계 정렬) |
| R0 후반 | `CM_PASSWORD_HISTORY`, `CM_CODE_GROUP`, `CM_CODE`, `CM_TERM`, `CM_ATTACH`, `CM_ATTACH_VERSION`, `CM_EXCEL_TEMPLATE`, `CM_AUDIT_LOG`(LOG_AT·PROJECT_ID), `CM_LICENSE` | 공통 기능 (10 명세) |
| R0 후반 | `CM_USER` + `EMAIL, LOCKED, FAIL_COUNT, LAST_LOGIN_AT, PASSWORD_CHANGED_AT` / `AS_CAPTURE` + `SCALE` | 비밀번호 정책·잠금, 스키마 v0.2 |
| R1 | `AS_ANALYSIS_HIST` | 분석 상태 변경 이력 |
| R1 | `AS_ELEMENT` + `HIDDEN, ORIGIN, UPDATED_AT/BY` · `AS_NODE` + `MANUAL_YN` · `AS_EDGE` + `CONFIRMED, EDITED, UPDATED_AT/BY` · `AS_CAPTURE` + `MANUAL, UPLOADED_BY, STATE`(RUN_ID NULL 허용) · `AS_NOTE` + `TARGET_TYPE, TARGET_ID, UPDATED_AT` · `AS_TABLE` + `COMMENT_MANUAL` · `AS_RELATION` + `CREATED_BY, UPDATED_AT/BY`(KIND manual) · `AS_COLLECT_RUN` + `DIFF_DETAIL_JSON` | 핫스팟 편집·수동 보정 보존·회차 diff (조회 인덱스 8개 추가) |
| R1 | Flyway 폴더 `db/migration/{common,h2,postgresql,oracle,sqlserver}` | 벤더별 마이그레이션 분리 (Oracle·MSSQL은 방언 계층 미구현으로 기동 차단) |
| R2 | PJ: `PJ_METHODOLOGY, PJ_SEQ, PJ_MEMBER_SCOPE, PJ_MEMBER_CHECK, PJ_WBS, PJ_WBS_DEP, PJ_BASELINE, PJ_BASELINE_ITEM, PJ_WEEKLY_REPORT` · RS: `RS_SKILL, RS_MM_PLAN, RS_ASSIGN, RS_TIMESHEET, RS_TIMESHEET_ENTRY` · DL: `DL_DELIVERABLE, DL_REQUIREMENT, DL_REQUIREMENT_HIST, DL_TRACE_LINK, DL_AUDIT_CHECK` · QA: `QA_ISSUE, QA_COMMENT, QA_RISK, QA_DECISION, QA_CR, QA_TEST_SCENARIO, QA_TEST_CASE, QA_TEST_ROUND, QA_TEST_RESULT, QA_DEFECT, QA_MEETING, QA_ACTION_ITEM` · CM: `CM_STATUS_HIST, CM_NOTIFICATION, CM_NOTIFY_SETTING` | 12 명세. `CM_USER` + `GRADE, COMPANY, PHONE`, `PJ_PROJECT` + `METHODOLOGY_ID`. 코드 그룹 `HOLIDAY, ONBOARD_ITEM, AUDIT_ITEM` (V4__r2_pm.sql) |
| R3 | `SM_SLA_POLICY, SM_SR, SM_SLA_EVENT, SM_SR_COMMENT, SM_INCIDENT, SM_INCIDENT_TIMELINE, SM_PROBLEM, SM_CHANGE, SM_CHANGE_CAB, SM_ROUTINE_JOB, SM_ROUTINE_CHECK, SM_OPS_ASSET, SM_KB, SM_MONTHLY_REPORT` | 13 명세 (V5__r3_sm.sql). 인덱스 `IX_AS_ANALYSIS_STATUS`, `IX_AS_ELEMENT_SYSTEM_SCREEN`. 인수인계 복사본 ID = 새 10자 접두어 + 원본 ULID 뒤 16자 |

실제 DDL의 기준은 `pmsx-server/src/main/resources/db/migration` (Flyway)이다.
