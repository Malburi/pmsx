# 10. API 명세 — R0 후반 (공통 기능 · 설치 패키지)

- 문서 버전: v0.1 (2026-10-15). 08 명세의 응답/오류/인증 규칙을 그대로 따른다.
- 목록 조회 페이지 형식: `Page<T> = { content: T[]; page: number; size: number; totalElements: number }` (page는 0부터, 기본 size 20, 최대 200)
- 관련 요구사항: FR-CM-001~006·010·011, NFR-ENV-001·002, NFR-SEC-003·005

## 1. 공통코드·용어 (FR-CM-004)

| Method | Path | 요청 / 응답 | 권한 |
|---|---|---|---|
| GET | `/codes/groups` | → `CodeGroup[]` | 로그인 |
| POST | `/codes/groups` | `CodeGroup` → `CodeGroup` (409 중복) | SYS_ADMIN |
| PUT | `/codes/groups/{groupCode}` | `CodeGroup` → `CodeGroup` | SYS_ADMIN |
| DELETE | `/codes/groups/{groupCode}` | 204 (하위 코드 포함 삭제) | SYS_ADMIN |
| GET | `/codes/groups/{groupCode}/codes` | → `Code[]` (정렬순) | 로그인 |
| PUT | `/codes/groups/{groupCode}/codes` | `Code[]` 일괄 저장(없는 코드는 삭제) → `Code[]` | SYS_ADMIN |
| GET | `/codes?groups=A,B` | → `Record<groupCode, Code[]>` (사용 중인 코드만, 화면 콤보용) | 로그인 |
| GET/POST/PUT/DELETE | `/terms`, `/terms/{termId}` | `Term` CRUD, GET은 `?q=&page=&size=` → `Page<Term>` | 조회 로그인 / 변경 SYS_ADMIN |

```ts
interface CodeGroup { groupCode: string; name: string; description: string | null; system: boolean }   // system=true 는 삭제 불가
interface Code { code: string; name: string; sortOrder: number; useYn: boolean; attr1: string | null; attr2: string | null }
interface Term { termId: string; term: string; abbr: string | null; definition: string; domain: string | null }
```
- 시드 그룹(system=true): `PROJECT_TYPE`(SI/SM), `ANALYSIS_STATUS`, `ELEMENT_TYPE`, `UI_TECH`, `DB_TYPE`, `ORG_TYPE`(자사/협력사/고객), `GRADE`(특/고/중/초)

## 2. 첨부파일 (FR-CM-005)

| Method | Path | 요청 / 응답 |
|---|---|---|
| POST | `/files` | multipart: `file`, `targetType`, `targetId` → `FileMeta` (201) |
| GET | `/attachments?targetType=&targetId=` | → `FileMeta[]` (최신 버전만, `versions` 포함) |
| GET | `/files/{fileId}` | 다운로드 (`Content-Disposition` UTF-8 파일명). **감사 로그 DOWNLOAD** |
| GET | `/files/{fileId}/preview` | 이미지/PDF만 inline, 그 외 415 |
| DELETE | `/attachments/{attachId}` | 204 (논리 삭제) |

```ts
interface FileMeta { fileId: string; attachId: string; name: string; mime: string; size: number; version: number; uploadedAt: string; uploadedBy: { userId: string; name: string }; versions: { fileId: string; version: number; uploadedAt: string }[] }
```
- 같은 target에 같은 파일명을 올리면 새 버전. 크기 제한 기본 50MB(`pmsx.file.max-size`), 확장자 허용 목록(`pmsx.file.allowed-ext`), 오류 `FILE_TOO_LARGE`(413) / `FILE_TYPE_NOT_ALLOWED`(400).
- 저장소는 기존 `./data/files`. 파일명은 저장 시 ULID로 바꾸고 원래 이름은 DB에.

## 3. 엑셀 엔진 (FR-CM-006, NFR-UX-001)
서버에 **데이터셋(datasetId)** 을 등록해 두고, 내보내기·가져오기·고객사 템플릿 매핑을 같은 정의로 처리한다.

| Method | Path | 요청 / 응답 |
|---|---|---|
| GET | `/excel/datasets` | → `ExcelDataset[]` |
| GET | `/excel/export/{datasetId}?templateId=&...params` | → xlsx 다운로드 (params는 데이터셋별, 예: `systemId`). 감사 로그 DOWNLOAD |
| POST | `/excel/import/{datasetId}/validate` | multipart `file` + params → `ImportPreview` |
| POST | `/excel/import/{datasetId}/commit` | `{ token }` → `{ inserted: number; updated: number; skipped: number }` (토큰 10분 유효, **오류 행은 건너뛰고 정상 행만 반영**) |
| GET/POST/PUT/DELETE | `/excel/templates`, `/excel/templates/{templateId}` | `ExcelTemplate` CRUD. POST/PUT은 multipart(`meta` JSON + `file` xlsx) |

```ts
interface ExcelDataset { datasetId: string; name: string; importable: boolean; params: string[]; columns: { field: string; header: string; required: boolean; type: 'string'|'number'|'date'|'code'; codeGroup?: string }[] }
interface ImportPreview { token: string; validCount: number; errorCount: number; rows: { rowNo: number; values: Record<string, string>; errors: Record<string, string> }[] }   // rows 최대 500행 미리보기
interface ExcelTemplate { templateId: string; name: string; datasetId: string; fileId: string; mapping: { sheet: string; startRow: number; columns: Record<string, string>; cells?: Record<string, string> } }   // columns: field → 열 문자('B'), cells: 'projectName' → 'C3'
```
- R0 데이터셋: `codes`(가져오기 가능, params `groupCode`), `terms`(가져오기 가능), `users`(내보내기), `audit-logs`(내보내기), `as-elements`(내보내기, params `systemId` — 화면 컴포넌트 목록), `as-screens`(내보내기, params `systemId`)
- 템플릿 셀 매핑의 공통 값: `projectName`, `systemName`, `exportedAt`, `exportedBy`

## 4. 사용자·역할·프로젝트 멤버 (FR-CM-002·003)

| Method | Path | 요청 / 응답 | 권한 |
|---|---|---|---|
| GET | `/admin/users?q=&page=&size=` | → `Page<AdminUser>` | SYS_ADMIN |
| POST | `/admin/users` | `{ loginId, name, email?, orgType, authType, roles, password? }` → `AdminUser` (LOCAL이면 password 필수) | SYS_ADMIN |
| PUT | `/admin/users/{userId}` | 같은 형식(loginId 제외) → `AdminUser` | SYS_ADMIN |
| POST | `/admin/users/{userId}/reset-password` | `{ newPassword }` → 204 | SYS_ADMIN |
| POST | `/admin/users/{userId}/lock` · `/unlock` | 204 | SYS_ADMIN |
| GET | `/admin/roles` | → `{ roleId, scope, name }[]` | 로그인 |
| GET | `/projects/{projectId}/members` | → `Member[]` | 프로젝트 멤버 |
| POST | `/projects/{projectId}/members` | `{ userId, roleId, inDate?, outDate? }` → `Member` | PM, SYS_ADMIN |
| PUT/DELETE | `/projects/{projectId}/members/{userId}` | 역할·기간 변경 / 제외 | PM, SYS_ADMIN |
| POST | `/auth/change-password` | `{ currentPassword, newPassword }` → 204 | 본인 |
| GET | `/users/lookup?q=` | → `{ userId, loginId, name, orgType }[]` (최대 20, 잠금 제외) — 멤버 추가·담당자 지정용 | 로그인 (v0.2 추가) |

```ts
interface AdminUser { userId: string; loginId: string; name: string; email: string | null; orgType: string; authType: 'LOCAL'|'LDAP'; roles: string[]; locked: boolean; lastLoginAt: string | null }
interface Member { userId: string; loginId: string; name: string; orgType: string; roleId: string; inDate: string | null; outDate: string | null }
```
- 비밀번호 정책(NFR-SEC-005): 9자 이상, 영문·숫자·특수문자 중 3종, 직전 비밀번호 재사용 금지. 5회 실패 시 잠금(`AUTH_LOCKED` 423). 오류 `PASSWORD_POLICY`(400).
- **LDAP/AD**(FR-CM-001): `pmsx.auth.ldap.enabled=true` 와 url/baseDn/userSearchFilter/bindDn/bindPassword 설정. `authType=LDAP` 로 **미리 등록된** 사용자만 LDAP로 인증(자동 가입 없음).

## 5. 감사 로그 (FR-CM-010)

| Method | Path | 요청 / 응답 | 권한 |
|---|---|---|---|
| GET | `/admin/audit-logs?from=&to=&userId=&action=&q=&page=&size=` | → `Page<AuditLog>` | SYS_ADMIN, PMO, PM(자기 프로젝트) |
| POST | `/audit/access` | `{ path: string }` → 204 (웹 라우터 가드가 화면 진입 시 호출, 개인정보 화면 조회 추적용) | 로그인 |

```ts
interface AuditLog { logId: string; at: string; userId: string | null; loginId: string | null; action: AuditAction; target: string | null; detail: string | null; ip: string | null }
type AuditAction = 'LOGIN'|'LOGIN_FAIL'|'LOGOUT'|'ACCESS'|'VIEW_SOURCE'|'DOWNLOAD'|'UPLOAD'|'CREATE'|'UPDATE'|'DELETE'|'LICENSE'
```
- 서버 자동 기록: 로그인 성공/실패, 업로드, 다운로드(첨부·엑셀·캡처 원본·리포트), 소스 스니펫 조회(`GET /nodes/{id}` 중 snippet 있는 경우 → VIEW_SOURCE), 관리 기능 변경.

## 6. 라이선스·시스템 정보 (FR-CM-011)

| Method | Path | 요청 / 응답 | 권한 |
|---|---|---|---|
| GET | `/system/info` | → `SystemInfo` | 로그인 (license 상세는 SYS_ADMIN만, 그 외는 `valid`만) |
| GET | `/system/license-status` | → `{ valid: boolean; reason: string \| null; expiresAt: string \| null }` | **인증 불필요** (로그인 화면 배너용) |
| POST | `/system/license` | `{ key: string }` → `SystemInfo['license']` | SYS_ADMIN |

```ts
interface SystemInfo {
  version: string; buildTime: string; profile: string; db: string; javaVersion: string
  users: { total: number; active: number }
  license: { customer: string; issuedAt: string; expiresAt: string; maxUsers: number; features: string[]; valid: boolean; reason: string | null } | null
}
```
- **키 형식**: `base64url(JSON payload) + "." + base64url(Ed25519 서명)`. payload = `{ customer, issuedAt, expiresAt, maxUsers, features[] }`. 서버에는 공개키만 포함, 발급 도구(`deploy/license-tool`)는 개인키로 서명. 완전 오프라인 검증.
- 라이선스 무효/만료/사용자 수 초과 시: 로그인 화면 배너, **SYS_ADMIN만 로그인 가능**(다른 사용자는 403 `LICENSE_INVALID`). `local` 프로필에서는 검사 생략.

## 7. 설치 패키지 (NFR-ENV-001·002)
- 웹 빌드 결과를 서버 jar의 정적 리소스로 포함 → **단일 jar** 가 웹+API를 제공 (SPA 경로는 index.html로 fallback, `/api/**` 제외).
- 배포 산출물 `deploy/out/pmsx-<버전>-offline.zip`:
  - `pmsx-server.jar`, `jre/`(jlink로 만든 JRE 21, Windows용; Linux는 별도 빌드 옵션), `config/application-prod.yml`(템플릿), `bin/start.bat|stop.bat|start.sh|stop.sh`, `nginx/pmsx.conf`(TLS 리버스 프록시 예시), `docker/`(Dockerfile, docker-compose.yml + PostgreSQL), `docs/설치가이드.md`, `license-tool/`
- 외부 네트워크 호출 0건: 웹 번들·서버 설정에 외부 URL이 없어야 한다(빌드 스크립트가 grep으로 검사).
