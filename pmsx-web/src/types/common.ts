// docs/10_API명세_R0후반_공통.md 의 타입을 그대로 옮긴 것. 명세가 바뀌면 이 파일을 먼저 고친다.

/** 목록 조회 페이지 형식 (page 는 0부터) */
export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
}

export interface PageQuery {
  page?: number
  size?: number
}

/* ── 1. 공통코드·용어 ─────────────────────────────────────── */
export interface CodeGroup {
  groupCode: string
  name: string
  description: string | null
  system: boolean
}

export interface Code {
  code: string
  name: string
  sortOrder: number
  useYn: boolean
  attr1: string | null
  attr2: string | null
}

export interface Term {
  termId: string
  term: string
  abbr: string | null
  definition: string
  domain: string | null
}

export type TermPayload = Omit<Term, 'termId'>

/* ── 2. 첨부파일 ─────────────────────────────────────────── */
export interface FileVersion {
  fileId: string
  version: number
  uploadedAt: string
}

export interface FileMeta {
  fileId: string
  attachId: string
  name: string
  mime: string
  size: number
  version: number
  uploadedAt: string
  uploadedBy: { userId: string; name: string }
  versions: FileVersion[]
}

/* ── 3. 엑셀 엔진 ────────────────────────────────────────── */
export type ExcelColumnType = 'string' | 'number' | 'date' | 'code'

export interface ExcelColumn {
  field: string
  header: string
  required: boolean
  type: ExcelColumnType
  codeGroup?: string
}

export interface ExcelDataset {
  datasetId: string
  name: string
  importable: boolean
  params: string[]
  columns: ExcelColumn[]
}

export interface ImportPreviewRow {
  rowNo: number
  values: Record<string, string>
  errors: Record<string, string>
}

export interface ImportPreview {
  token: string
  validCount: number
  errorCount: number
  rows: ImportPreviewRow[]
}

export interface ImportResult {
  inserted: number
  updated: number
  /** 오류로 건너뛴 행 수 (10 명세 v0.2) */
  skipped?: number
}

export interface ExcelMapping {
  sheet: string
  startRow: number
  /** field → 열 문자 ('B') */
  columns: Record<string, string>
  /** 공통 값(projectName 등) → 셀 ('C3') */
  cells?: Record<string, string>
}

export interface ExcelTemplate {
  templateId: string
  name: string
  datasetId: string
  fileId: string
  mapping: ExcelMapping
}

export type ExcelTemplateMeta = Omit<ExcelTemplate, 'templateId' | 'fileId'>

/** 템플릿 셀 매핑에 쓸 수 있는 공통 값 */
export const TEMPLATE_COMMON_CELLS = ['projectName', 'systemName', 'exportedAt', 'exportedBy'] as const

/* ── 4. 사용자·역할·프로젝트 멤버 ─────────────────────────── */
export type AuthType = 'LOCAL' | 'LDAP'

export interface AdminUser {
  userId: string
  loginId: string
  name: string
  email: string | null
  orgType: string
  authType: AuthType
  roles: string[]
  locked: boolean
  lastLoginAt: string | null
}

export interface AdminUserCreate {
  loginId: string
  name: string
  email?: string
  orgType: string
  authType: AuthType
  roles: string[]
  password?: string
}

export type AdminUserUpdate = Omit<AdminUserCreate, 'loginId'>

export interface Role {
  roleId: string
  scope: 'SYSTEM' | 'PROJECT'
  name: string
}

export interface Member {
  userId: string
  loginId: string
  name: string
  orgType: string
  roleId: string
  inDate: string | null
  outDate: string | null
}

export interface MemberPayload {
  userId: string
  roleId: string
  inDate?: string | null
  outDate?: string | null
}

export interface ChangePasswordPayload {
  currentPassword: string
  newPassword: string
}

/* ── 5. 감사 로그 ────────────────────────────────────────── */
export type AuditAction =
  | 'LOGIN' | 'LOGIN_FAIL' | 'LOGOUT' | 'ACCESS' | 'VIEW_SOURCE' | 'DOWNLOAD'
  | 'UPLOAD' | 'CREATE' | 'UPDATE' | 'DELETE' | 'LICENSE'

export const AUDIT_ACTIONS: AuditAction[] = [
  'LOGIN', 'LOGIN_FAIL', 'LOGOUT', 'ACCESS', 'VIEW_SOURCE', 'DOWNLOAD', 'UPLOAD', 'CREATE', 'UPDATE', 'DELETE', 'LICENSE',
]

export interface AuditLog {
  logId: string
  at: string
  userId: string | null
  loginId: string | null
  action: AuditAction
  target: string | null
  detail: string | null
  ip: string | null
}

export interface AuditLogQuery extends PageQuery {
  /** YYYY-MM-DD (기간 필터는 날짜 형식으로 보낸다) */
  from?: string
  /** YYYY-MM-DD, 그날 끝까지 포함 */
  to?: string
  userId?: string
  action?: AuditAction
  q?: string
}

/* ── 6. 라이선스·시스템 정보 ──────────────────────────────── */
export interface LicenseInfo {
  customer: string
  issuedAt: string
  expiresAt: string
  maxUsers: number
  features: string[]
  valid: boolean
  reason: string | null
}

export interface SystemInfo {
  version: string
  buildTime: string
  profile: string
  db: string
  javaVersion: string
  users: { total: number; active: number }
  /** SYS_ADMIN 이 아니면 `valid` 만 내려온다 (명세 §6) */
  license: (Partial<LicenseInfo> & { valid: boolean }) | null
}

export interface LicenseStatus {
  valid: boolean
  reason: string | null
  expiresAt: string | null
}

/* ── 10 명세 v0.2: 사용자 조회 (멤버 추가·담당자 지정용) ───── */
export interface UserLookup {
  userId: string
  loginId: string
  name: string
  orgType: string
}
