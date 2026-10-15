// docs/08_API명세_R0.md 의 타입을 그대로 옮긴 것. 명세가 바뀌면 이 파일을 먼저 고친다.

export interface ApiError {
  code: string
  message: string
  fields?: Record<string, string>
}

/* ── 1. 인증 ─────────────────────────────────────────────── */
export interface User {
  userId: string
  loginId: string
  name: string
  roles: string[]
}

export interface LoginPayload {
  loginId: string
  password: string
}

export interface LoginResponse {
  accessToken: string
  user: User
}

export interface RefreshResponse {
  accessToken: string
}

/* ── 2. 프로젝트·시스템 ──────────────────────────────────── */
export interface Project {
  projectId: string
  type: 'SI' | 'SM'
  name: string
  customer: string
  startDate: string
  endDate: string
  /** docs/12 v0.2 — 수정 폼 초기값 */
  contractMm?: number | null
  pmUserId?: string | null
  pmName?: string | null
  /** docs/13 §7 — SI 에서 인수인계된 SM 프로젝트의 원 SI 프로젝트 */
  originProjectId?: string | null
}

export interface SystemCreate {
  code: string
  name: string
  uiTech: string[]
  serverTech: string[]
  dbType: string
  testUrl?: string
}

export interface SystemSummary extends SystemCreate {
  systemId: string
  projectId: string
  screenCount: number
  doneCount: number
  lastRun: { runId: string; seq: number; collectedAt: string } | null
}

/* ── 3. 수집 결과 ────────────────────────────────────────── */
export interface RunDiff {
  screensAdded: number
  screensRemoved: number
  elementsAdded: number
  elementsRemoved: number
  edgesAdded: number
  edgesRemoved: number
}

export interface RunSummary {
  runId: string
  systemId: string
  seq: number
  collectedAt: string
  uploadedAt: string
  stats: Record<string, number>
  diff: RunDiff
}

/* ── 4. As-Is 뷰어 ───────────────────────────────────────── */
export type AnalysisStatus = 'AUTO' | 'WORKING' | 'REVIEW' | 'DONE'
export type ElementType =
  | 'button' | 'input' | 'combo' | 'check' | 'radio' | 'date' | 'grid'
  | 'tab' | 'tree' | 'popup' | 'link' | 'file' | 'other'
export type EdgeSource = 'static' | 'dynamic' | 'manual'
export type Lane = 'UI' | 'FRONT' | 'API' | 'SERVER' | 'SQL' | 'DB' | 'POST'

export interface MenuNode {
  menuId: string
  name: string
  order: number
  screen: { screenId: string; screenCode: string; kind: string; status: AnalysisStatus } | null
  children: MenuNode[]
}

export interface UserRef {
  userId: string
  name: string
}

export interface ScreenAnalysis {
  status: AnalysisStatus
  owner: UserRef | null
  reviewer: UserRef | null
  updatedAt: string
}

export interface Box {
  x: number
  y: number
  w: number
  h: number
}

export interface ElementSummary {
  elementId: string
  seqNo: number
  elementCode: string
  label: string
  type: ElementType
  box: Box | null
  binding: string | null
  matched: 'full' | 'static-only' | 'dynamic-only'
  danger: boolean
  events: { event: string; handler: string }[]
  /** 분석자가 편집한 요소 (재수집 시 보존) — 11 명세 */
  manual: boolean
  /** 숨긴 수집 요소 — 11 명세 */
  hidden: boolean
}

export interface ScreenDetail {
  screenId: string
  screenCode: string
  name: string
  kind: string
  uiTech: string
  sourceFile: string | null
  menuPath: string[]
  analysis: ScreenAnalysis
  capture: {
    captureId: string
    width: number
    height: number
    collectedAt: string
    runSeq: number
    /** 'wireframe' = 런타임 없이 소스 좌표로 그린 화면 (Nexacro XFDL 등), 'rendered' = 서버 없이 JSP 를 HTML 로 변환해 그린 화면. 없으면 실제 캡처 */
    state?: string | null
  } | null
  elements: ElementSummary[]
}

export interface WorkflowEdge {
  /** 수동 보정용 엣지 ID — 11 명세 변경점 */
  edgeId: string
  kind: string
  source: EdgeSource
  confidence: number
  condition: string | null
  evidence: string | null
  /** 사람이 확정한 연결은 신뢰도와 관계없이 실선 — 11 명세 */
  confirmed?: boolean
  status?: EdgeStatus
}

export interface WorkflowStep {
  nodeId: string | null
  lane: Lane
  title: string
  subtitle: string | null
  depth: number
  crud: string | null
  columns: string[] | null
  hasSource: boolean
  edge: WorkflowEdge | null
}

export interface Workflow {
  elementId: string
  events: { event: string; steps: WorkflowStep[] }[]
}

export interface NodeDetail {
  nodeId: string
  nodeType: 'FN' | 'API' | 'SQL' | 'SP' | 'TABLE' | 'SCREEN'
  key: string
  name: string
  lang: string | null
  file: string | null
  lineStart: number | null
  lineEnd: number | null
  snippet: string | null
  usedBy: { screenId: string; screenName: string; elementId: string; elementLabel: string }[]
}

export interface CrudMatrix {
  tables: { nodeId: string; name: string }[]
  /**
   * 시스템 단위(by=screen)일 때 elementId 자리에 화면 ID 가 온다.
   * by=element 이면 요소가 속한 screenId 가 필요하다 — 서버에 추가 요청(명세 보완 제안)
   */
  rows: { elementId: string; seqNo: number; label: string; cells: Record<string, string>; screenId?: string; sub?: string }[]
}

export interface SearchHit {
  kind: 'SCREEN' | 'ELEMENT' | 'NODE'
  id: string
  label: string
  sub: string
  screenId: string | null
  elementId: string | null
}

export interface AnalysisPatch {
  status?: AnalysisStatus
  ownerId?: string | null
  reviewerId?: string | null
  comment?: string
}

/* ══ docs/11_API명세_R1_AsIs.md ══════════════════════════════ */
export type NodeType = 'FN' | 'API' | 'SQL' | 'SP' | 'TABLE' | 'SCREEN'
export type EdgeKind = 'event' | 'calls' | 'request' | 'handles' | 'executes' | 'accesses' | 'navigates' | 'binds'
export type EdgeStatus = 'active' | 'rejected'

/* ── 1. 핫스팟 편집기·연결 보정 ─────────────────────────────── */
export interface ElementPayload {
  elementCode: string
  label: string
  type: ElementType
  box: Box
  binding?: string | null
}

export type ElementUpdate = Partial<Pick<ElementPayload, 'label' | 'type' | 'box' | 'binding'>>

export interface NodeSummary {
  nodeId: string
  nodeType: NodeType
  key: string
  name: string
  file: string | null
}

export interface EdgeCreate {
  from: { elementId?: string; nodeId?: string; screenId?: string }
  to: { nodeId?: string; newNode?: { nodeType: NodeType; name: string; file?: string } }
  kind: EdgeKind
  event?: string
  condition?: string
  crud?: string
  order?: number
}

export interface EdgeInfo {
  edgeId: string
  fromId: string
  toId: string
  kind: string
  event: string | null
  source: EdgeSource
  confidence: number
  status: EdgeStatus
  confirmed: boolean
  condition: string | null
  crud: string | null
}

export interface EdgePatch {
  status?: EdgeStatus
  confirmed?: boolean
  condition?: string
  crud?: string
}

export type CaptureInfo = NonNullable<ScreenDetail['capture']>

/* ── 2. 메모·분석 상태·진척 ─────────────────────────────────── */
export type NoteTargetType = 'SCREEN' | 'ELEMENT' | 'NODE'

export interface Note {
  noteId: string
  targetType: NoteTargetType
  targetId: string
  content: string
  author: UserRef
  createdAt: string
  updatedAt: string
}

export interface AnalysisHistoryItem {
  at: string
  user: UserRef
  fromStatus: AnalysisStatus | null
  toStatus: AnalysisStatus
  comment: string | null
}

export interface AnalysisAssign {
  screenIds: string[]
  ownerId: string | null
  reviewerId?: string | null
}

export interface AnalysisProgress {
  total: number
  byStatus: Record<AnalysisStatus, number>
  byOwner: { owner: UserRef | null; total: number; byStatus: Record<string, number> }[]
  byMenu: { menuId: string; name: string; total: number; done: number }[]
  unmatchedElements: number
  unconfirmedEdges: number
}

export interface AnalysisRow {
  screenId: string
  screenCode: string
  name: string
  menuPath: string[]
  status: AnalysisStatus
  owner: UserRef | null
  reviewer: UserRef | null
  elementCount: number
  unmatchedCount: number
  updatedAt: string
}

export interface AnalysisScreenQuery {
  status?: AnalysisStatus
  ownerId?: string
  menuId?: string
  q?: string
  page?: number
  size?: number
}

/* ── 3. 테이블 정의서·ERD ───────────────────────────────────── */
export type RelationKind = 'fk' | 'inferred' | 'manual'
export type RelationDecision = 'pending' | 'confirmed' | 'rejected'

export interface TableSummary {
  tableId: string
  schema: string
  name: string
  comment: string | null
  subjectArea: string | null
  columnCount: number
  usedByScreens: number
}

export interface TableColumn {
  name: string
  dataType: string
  nullable: boolean
  pk: boolean
  fk: boolean
  default: string | null
  comment: string | null
}

export interface Relation {
  relationId: string
  childTableId: string
  parentTableId: string
  childName: string
  parentName: string
  columns: [string, string][]
  kind: RelationKind
  evidenceCount: number | null
  confidence: number | null
  decision: RelationDecision
}

export interface TableDetail extends TableSummary {
  columns: TableColumn[]
  indexes: { name: string; columns: string[]; unique: boolean }[]
  relations: Relation[]
  usedBy: { screenId: string; screenName: string; elementId: string | null; elementLabel: string | null; crud: string; sqlName: string }[]
}

export interface ErdTable {
  tableId: string
  name: string
  comment: string | null
  subjectArea: string | null
  columns: { name: string; dataType: string; pk: boolean; fk: boolean }[]
}

export interface Erd {
  tables: ErdTable[]
  relations: Relation[]
}

export interface RelationCreate {
  childTableId: string
  parentTableId: string
  columns: [string, string][]
}

/* ── 4. 시스템 단위 분석 뷰 ─────────────────────────────────── */
export interface NavigationMap {
  nodes: { screenId: string; screenCode: string; name: string; kind: string; status: AnalysisStatus }[]
  links: { fromScreenId: string; toScreenId: string; via: string | null; kind: 'popup' | 'link' | 'tab' }[]
}

export interface CallTreeNode {
  nodeId: string
  nodeType: string
  name: string
  file: string | null
  edgeKind: string | null
  truncated: boolean
  children: CallTreeNode[]
}

export interface NodeUsage {
  screenId: string
  screenName: string
  elementId: string
  elementLabel: string
  path: string[]
}

export interface DiffRef {
  id: string | null
  key: string
  label: string
}

export interface RunDiffDetail {
  runId: string
  prevRunId: string | null
  screens: { added: DiffRef[]; removed: DiffRef[] }
  elements: { added: DiffRef[]; removed: DiffRef[] }
  edges: { added: number; removed: number; samples: { from: string; to: string; kind: string; change: 'added' | 'removed' }[] }
}

/* ── 5. 산출물 출력 ─────────────────────────────────────────── */
export type DeliverableFormat = 'xlsx' | 'pptx'

export interface DeliverableType {
  type: string
  name: string
  formats: DeliverableFormat[]
}

export interface DeliverableQuery {
  format: DeliverableFormat
  screenIds?: string
  status?: AnalysisStatus
  templateId?: string
}
