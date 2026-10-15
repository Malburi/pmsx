// docs/10 공통 기능 mock 경로 — 공통코드·용어·첨부·엑셀·사용자·멤버·감사로그·라이선스
import type {
  AdminUser, AdminUserCreate, Code, CodeGroup, ExcelTemplate, ExcelTemplateMeta, FileMeta, ImportPreview, LicenseInfo, Member, MemberPayload, Term,
} from '@/types/common'
import { HR0101_CAPTURE_SVG } from '../fixtures/capture'
import {
  MOCK_ADMIN_USERS, MOCK_ATTACHMENTS, MOCK_AUDIT_LOGS, MOCK_CODE_GROUPS, MOCK_CODES, MOCK_DATASETS, MOCK_LICENSE_STATUS,
  MOCK_MEMBERS, MOCK_ROLES, MOCK_SYSTEM_INFO, MOCK_TEMPLATES, MOCK_TERMS,
} from '../fixtures/admin'
import { err, mockId, paginate, tokenUser, type Route } from '../shared'

// 로그인 화면 라이선스 배너 확인용: sessionStorage 에 이 키를 넣으면 라이선스 무효 상태가 된다
export const MOCK_LICENSE_INVALID_KEY = 'pmsx-mock-license-invalid'

// mock 상태 (새로고침 시 초기화)
const groups: CodeGroup[] = structuredClone(MOCK_CODE_GROUPS)
const codes: Record<string, Code[]> = structuredClone(MOCK_CODES)
const terms: Term[] = structuredClone(MOCK_TERMS)
const users: AdminUser[] = structuredClone(MOCK_ADMIN_USERS)
const members: Record<string, Member[]> = structuredClone(MOCK_MEMBERS)
const templates: ExcelTemplate[] = structuredClone(MOCK_TEMPLATES)
const attachments: Record<string, FileMeta[]> = structuredClone(MOCK_ATTACHMENTS)
const systemInfo = structuredClone(MOCK_SYSTEM_INFO)
const importTokens = new Map<string, { datasetId: string; valid: number }>()

const PDF_SAMPLE = '%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n'
  + '3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 420 200]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj\n'
  + '4 0 obj<</Length 52>>stream\nBT /F1 18 Tf 40 120 Td (PMS-X mock attachment) Tj ET\nendstream endobj\n'
  + '5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF'

const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'

const PASSWORD_POLICY = (v: string) =>
  !!v && v.length >= 9 && [/[A-Za-z]/, /[0-9]/, /[^A-Za-z0-9]/].filter((re) => re.test(v)).length >= 3

const isLicenseInvalid = () => !!sessionStorage.getItem(MOCK_LICENSE_INVALID_KEY)

const findFile = (fileId: string): FileMeta | null => {
  for (const list of Object.values(attachments)) {
    const hit = list.find((f) => f.fileId === fileId || f.versions.some((v) => v.fileId === fileId))
    if (hit) return hit
  }
  return null
}

const formField = (body: unknown, key: string) => (body instanceof FormData ? body.get(key) : null)

const fileFromBody = (body: unknown): File | null => {
  const f = formField(body, 'file')
  return f instanceof File ? f : null
}

const readMeta = async (body: unknown): Promise<ExcelTemplateMeta | null> => {
  const m = formField(body, 'meta')
  if (m instanceof Blob) return JSON.parse(await m.text()) as ExcelTemplateMeta
  if (typeof m === 'string') return JSON.parse(m) as ExcelTemplateMeta
  return null
}

// 엑셀 가져오기 검증 결과 — 데이터셋 컬럼으로 6행을 만들고 2행에 오류를 넣는다
const buildPreview = (datasetId: string): ImportPreview | null => {
  const ds = MOCK_DATASETS.find((d) => d.datasetId === datasetId)
  if (!ds || !ds.importable) return null
  // codes·terms 외 데이터셋(R2: wbs·requirements 등)은 컬럼명으로 예시 값을 만들고 3행만 필수값을 비운다
  const generic = (field: string, header: string, i: number) => (i === 2 && ds.columns.find((c) => c.field === field)?.required ? '' : `${header} ${i + 1}`)
  const sample: Record<string, string[]> = !['codes', 'terms'].includes(datasetId)
    ? Object.fromEntries(ds.columns.map((c) => [c.field, Array.from({ length: 6 }, (_, i) => generic(c.field, c.header, i))]))
    : datasetId === 'codes'
    ? { code: ['RPT', 'MTG', '', 'EST', 'test-1', 'PLN'], name: ['보고서', '회의록', '설계서', '견적서', '테스트', '계획서'], sortOrder: ['5', '6', '7', '8', 'x', '10'], useYn: ['Y', 'Y', 'Y', 'Y', 'Y', 'N'], attr1: ['', '', '', '', '', ''], attr2: ['', '', '', '', '', ''] }
    : { term: ['배치', '인터페이스', '컷오버', '', '이행', '형상관리'], abbr: ['BATCH', 'IF', 'CUTOVER', 'TEMP', 'MIG', 'SCM'], domain: ['공통', '공통', 'PM', 'PM', 'PM', '공통'], definition: ['정해진 시간에 일괄 처리하는 작업.', '시스템 간 데이터 연계.', '중복 정의 행', '정의 누락', '데이터 이관 작업.', '소스·산출물 버전 관리.'] }
  const rows = Array.from({ length: 6 }, (_, i) => {
    const values = Object.fromEntries(ds.columns.map((c) => [c.field, sample[c.field]?.[i] ?? '']))
    const errors: Record<string, string> = {}
    ds.columns.forEach((c) => { if (c.required && !values[c.field]) errors[c.field] = `${c.header}은(는) 필수입니다.` })
    if (datasetId === 'codes' && values.code && !/^[A-Z0-9_]{1,30}$/.test(values.code)) errors.code = '영문 대문자·숫자·_ 만 쓸 수 있습니다.'
    if (datasetId === 'codes' && values.sortOrder && Number.isNaN(Number(values.sortOrder))) errors.sortOrder = '숫자가 아닙니다.'
    if (datasetId === 'terms' && values.term === '컷오버') errors.term = '이미 등록된 용어입니다.'
    return { rowNo: i + 2, values, errors }
  })
  const errorCount = rows.filter((r) => Object.keys(r.errors).length).length
  const token = mockId('imp')
  importTokens.set(token, { datasetId, valid: rows.length - errorCount })
  return { token, validCount: rows.length - errorCount, errorCount, rows }
}

const decodeLicense = (key: string): LicenseInfo | null => {
  const [payload, sig] = key.split('.')
  if (!payload || !sig) return null
  try {
    const json = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/')))
    const expired = new Date(json.expiresAt).getTime() < Date.now()
    return {
      customer: String(json.customer ?? ''), issuedAt: String(json.issuedAt ?? ''), expiresAt: String(json.expiresAt ?? ''),
      maxUsers: Number(json.maxUsers ?? 0), features: Array.isArray(json.features) ? json.features : [],
      valid: !expired, reason: expired ? '만료된 라이선스입니다.' : null,
    }
  } catch {
    return null
  }
}

export const commonRoutes: Route[] = [
  /* ── 공통코드 ── */
  ['get', /^\/api\/v1\/codes\/groups$/, () => [200, groups]],
  ['post', /^\/api\/v1\/codes\/groups$/, ({ body }) => {
    const g = body as CodeGroup
    if (groups.some((x) => x.groupCode === g.groupCode)) return err(409, 'CONFLICT', '이미 있는 그룹 코드입니다.')
    const created = { ...g, system: false }
    groups.push(created)
    codes[g.groupCode] = []
    return [201, created]
  }],
  ['put', /^\/api\/v1\/codes\/groups\/([^/]+)\/codes$/, ({ params, body }) => {
    const g = decodeURIComponent(params[0])
    if (!groups.some((x) => x.groupCode === g)) return err(404, 'NOT_FOUND', '그룹이 없습니다.')
    codes[g] = [...(body as Code[])].sort((a, b) => a.sortOrder - b.sortOrder)
    return [200, codes[g]]
  }],
  ['get', /^\/api\/v1\/codes\/groups\/([^/]+)\/codes$/, ({ params }) => [200, codes[decodeURIComponent(params[0])] ?? []]],
  ['put', /^\/api\/v1\/codes\/groups\/([^/]+)$/, ({ params, body }) => {
    const g = groups.find((x) => x.groupCode === decodeURIComponent(params[0]))
    if (!g) return err(404, 'NOT_FOUND', '그룹이 없습니다.')
    Object.assign(g, { name: body.name, description: body.description })
    return [200, g]
  }],
  ['delete', /^\/api\/v1\/codes\/groups\/([^/]+)$/, ({ params }) => {
    const code = decodeURIComponent(params[0])
    const i = groups.findIndex((x) => x.groupCode === code)
    if (i < 0) return err(404, 'NOT_FOUND', '그룹이 없습니다.')
    if (groups[i].system) return err(400, 'VALIDATION', '시스템 그룹은 삭제할 수 없습니다.')
    groups.splice(i, 1)
    delete codes[code]
    return [204, null]
  }],
  ['get', /^\/api\/v1\/codes$/, ({ config }) => {
    const want = String(config.params?.groups ?? '').split(',').filter(Boolean)
    return [200, Object.fromEntries(want.map((g) => [g, (codes[g] ?? []).filter((c) => c.useYn)]))]
  }],

  /* ── 용어 ── */
  ['get', /^\/api\/v1\/terms$/, ({ config }) => {
    const q = String(config.params?.q ?? '').toLowerCase()
    const list = q ? terms.filter((x) => `${x.term} ${x.abbr ?? ''} ${x.definition}`.toLowerCase().includes(q)) : terms
    return [200, paginate(list, config.params)]
  }],
  ['post', /^\/api\/v1\/terms$/, ({ body }) => {
    if (terms.some((x) => x.term === body.term)) return err(409, 'CONFLICT', '이미 등록된 용어입니다.')
    const created: Term = { ...(body as Omit<Term, 'termId'>), termId: mockId('term') }
    terms.unshift(created)
    return [201, created]
  }],
  ['put', /^\/api\/v1\/terms\/([^/]+)$/, ({ params, body }) => {
    const x = terms.find((v) => v.termId === params[0])
    if (!x) return err(404, 'NOT_FOUND', '용어가 없습니다.')
    Object.assign(x, body)
    return [200, x]
  }],
  ['delete', /^\/api\/v1\/terms\/([^/]+)$/, ({ params }) => {
    const i = terms.findIndex((v) => v.termId === params[0])
    if (i >= 0) terms.splice(i, 1)
    return [204, null]
  }],

  /* ── 첨부 ── */
  ['post', /^\/api\/v1\/files$/, ({ body, config }) => {
    const file = fileFromBody(body)
    const targetType = String(formField(body, 'targetType') ?? '')
    const targetId = String(formField(body, 'targetId') ?? '')
    if (!file) return err(400, 'VALIDATION', '파일이 없습니다.')
    if (file.size > 50 * 1024 * 1024) return err(413, 'FILE_TOO_LARGE', '파일이 너무 큽니다.')
    if (/\.(exe|bat|cmd|sh|js)$/i.test(file.name)) return err(400, 'FILE_TYPE_NOT_ALLOWED', '허용되지 않는 형식입니다.')
    const key = `${targetType}:${targetId}`
    const list = attachments[key] ?? (attachments[key] = [])
    const now = new Date().toISOString()
    const fileId = mockId('file')
    const login = tokenUser(config)
    const by = { userId: `u-${login}`, name: users.find((u) => u.loginId === login)?.name ?? login }
    const prev = list.find((f) => f.name === file.name)
    if (prev) {
      prev.version += 1
      Object.assign(prev, { fileId, size: file.size, uploadedAt: now, uploadedBy: by })
      prev.versions.unshift({ fileId, version: prev.version, uploadedAt: now })
      return [201, prev]
    }
    const meta: FileMeta = {
      fileId, attachId: mockId('att'), name: file.name, mime: file.type || 'application/octet-stream', size: file.size, version: 1,
      uploadedAt: now, uploadedBy: by, versions: [{ fileId, version: 1, uploadedAt: now }],
    }
    list.unshift(meta)
    return [201, meta]
  }],
  ['get', /^\/api\/v1\/attachments$/, ({ config }) => [200, attachments[`${config.params?.targetType}:${config.params?.targetId}`] ?? []]],
  ['delete', /^\/api\/v1\/attachments\/([^/]+)$/, ({ params }) => {
    Object.values(attachments).forEach((list) => {
      const i = list.findIndex((f) => f.attachId === params[0])
      if (i >= 0) list.splice(i, 1)
    })
    return [204, null]
  }],
  ['get', /^\/api\/v1\/files\/([^/]+)\/preview$/, ({ params }) => {
    const f = findFile(params[0])
    if (!f) return err(404, 'NOT_FOUND', '파일이 없습니다.')
    if (f.mime === 'application/pdf') return [200, new Blob([PDF_SAMPLE], { type: 'application/pdf' })]
    if (f.mime.startsWith('image/')) return [200, new Blob([HR0101_CAPTURE_SVG], { type: 'image/svg+xml' })]
    return err(415, 'UNSUPPORTED', '미리보기를 지원하지 않는 형식입니다.')
  }],
  ['get', /^\/api\/v1\/files\/([^/]+)$/, ({ params }) => [200, new Blob([`mock file ${params[0]}`], { type: 'application/octet-stream' })]],

  /* ── 엑셀 ── */
  ['get', /^\/api\/v1\/excel\/datasets$/, () => [200, MOCK_DATASETS]],
  ['get', /^\/api\/v1\/excel\/export\/([^/]+)$/, ({ params }) =>
    MOCK_DATASETS.some((d) => d.datasetId === params[0])
      ? [200, new Blob([`mock xlsx: ${params[0]}`], { type: XLSX_MIME })]
      : err(404, 'NOT_FOUND', '데이터셋이 없습니다.')],
  ['post', /^\/api\/v1\/excel\/import\/([^/]+)\/validate$/, ({ params, body }) => {
    if (!fileFromBody(body)) return err(400, 'VALIDATION', '파일이 없습니다.')
    const preview = buildPreview(params[0])
    return preview ? [200, preview] : err(400, 'VALIDATION', '가져오기를 지원하지 않는 데이터셋입니다.')
  }],
  ['post', /^\/api\/v1\/excel\/import\/([^/]+)\/commit$/, ({ params, body }) => {
    const tk = importTokens.get(body?.token)
    if (!tk || tk.datasetId !== params[0]) return err(404, 'NOT_FOUND', '검증 결과가 만료되었습니다.')
    importTokens.delete(body.token)
    return [200, { inserted: Math.max(0, tk.valid - 1), updated: Math.min(1, tk.valid) }]
  }],
  ['get', /^\/api\/v1\/excel\/templates$/, () => [200, templates]],
  ['post', /^\/api\/v1\/excel\/templates$/, async ({ body }) => {
    const meta = await readMeta(body)
    if (!meta || !fileFromBody(body)) return err(400, 'VALIDATION', '양식 파일과 매핑 정보가 필요합니다.')
    const created: ExcelTemplate = { ...meta, templateId: mockId('tpl'), fileId: mockId('file') }
    templates.push(created)
    return [201, created]
  }],
  ['put', /^\/api\/v1\/excel\/templates\/([^/]+)$/, async ({ params, body }) => {
    const tp = templates.find((x) => x.templateId === params[0])
    const meta = await readMeta(body)
    if (!tp || !meta) return err(404, 'NOT_FOUND', '템플릿이 없습니다.')
    Object.assign(tp, meta, fileFromBody(body) ? { fileId: mockId('file') } : {})
    return [200, tp]
  }],
  ['delete', /^\/api\/v1\/excel\/templates\/([^/]+)$/, ({ params }) => {
    const i = templates.findIndex((x) => x.templateId === params[0])
    if (i >= 0) templates.splice(i, 1)
    return [204, null]
  }],

  /* ── 사용자·역할 ── */
  ['get', /^\/api\/v1\/admin\/users$/, ({ config }) => {
    if (tokenUser(config) !== 'admin') return err(403, 'FORBIDDEN', '권한이 없습니다.')
    const q = String(config.params?.q ?? '').toLowerCase()
    const list = q ? users.filter((u) => `${u.loginId} ${u.name} ${u.email ?? ''}`.toLowerCase().includes(q)) : users
    return [200, paginate(list, config.params)]
  }],
  ['post', /^\/api\/v1\/admin\/users$/, ({ body }) => {
    const p = body as AdminUserCreate
    if (users.some((u) => u.loginId === p.loginId)) return err(409, 'CONFLICT', '이미 사용 중인 아이디입니다.')
    if (p.authType === 'LOCAL' && !PASSWORD_POLICY(p.password ?? '')) return err(400, 'PASSWORD_POLICY', '비밀번호 정책을 만족하지 않습니다.')
    const created: AdminUser = {
      userId: mockId('u'), loginId: p.loginId, name: p.name, email: p.email ?? null, orgType: p.orgType, authType: p.authType,
      roles: p.roles, locked: false, lastLoginAt: null,
    }
    users.push(created)
    return [201, created]
  }],
  ['put', /^\/api\/v1\/admin\/users\/([^/]+)$/, ({ params, body }) => {
    const u = users.find((x) => x.userId === params[0])
    if (!u) return err(404, 'NOT_FOUND', '사용자가 없습니다.')
    Object.assign(u, { name: body.name, email: body.email ?? null, orgType: body.orgType, roles: body.roles })
    return [200, u]
  }],
  ['post', /^\/api\/v1\/admin\/users\/([^/]+)\/reset-password$/, ({ body }) =>
    PASSWORD_POLICY(body?.newPassword ?? '') ? [204, null] : err(400, 'PASSWORD_POLICY', '비밀번호 정책을 만족하지 않습니다.')],
  ['post', /^\/api\/v1\/admin\/users\/([^/]+)\/(lock|unlock)$/, ({ params }) => {
    const u = users.find((x) => x.userId === params[0])
    if (!u) return err(404, 'NOT_FOUND', '사용자가 없습니다.')
    u.locked = params[1] === 'lock'
    return [204, null]
  }],
  ['get', /^\/api\/v1\/admin\/roles$/, () => [200, MOCK_ROLES]],
  ['post', /^\/api\/v1\/auth\/change-password$/, ({ body }) => {
    if (body?.currentPassword !== 'pmsx1234!') return err(401, 'AUTH_INVALID', '현재 비밀번호가 올바르지 않습니다.')
    if (!PASSWORD_POLICY(body?.newPassword ?? '')) return err(400, 'PASSWORD_POLICY', '비밀번호 정책을 만족하지 않습니다.')
    return [204, null]
  }],

  /* ── 프로젝트 멤버 ── */
  ['get', /^\/api\/v1\/projects\/([^/]+)\/members$/, ({ params }) => [200, members[params[0]] ?? []]],
  ['post', /^\/api\/v1\/projects\/([^/]+)\/members$/, ({ params, body }) => {
    const p = body as MemberPayload
    const list = members[params[0]] ?? (members[params[0]] = [])
    if (list.some((m) => m.userId === p.userId)) return err(409, 'CONFLICT', '이미 참여 중입니다.')
    const u = users.find((x) => x.userId === p.userId)
    if (!u) return err(404, 'NOT_FOUND', '사용자가 없습니다.')
    const created: Member = { userId: u.userId, loginId: u.loginId, name: u.name, orgType: u.orgType, roleId: p.roleId, inDate: p.inDate ?? null, outDate: p.outDate ?? null }
    list.push(created)
    return [201, created]
  }],
  ['put', /^\/api\/v1\/projects\/([^/]+)\/members\/([^/]+)$/, ({ params, body }) => {
    const m = (members[params[0]] ?? []).find((x) => x.userId === params[1])
    if (!m) return err(404, 'NOT_FOUND', '참여 인원이 아닙니다.')
    Object.assign(m, { roleId: body.roleId, inDate: body.inDate ?? null, outDate: body.outDate ?? null })
    return [200, m]
  }],
  ['delete', /^\/api\/v1\/projects\/([^/]+)\/members\/([^/]+)$/, ({ params }) => {
    const list = members[params[0]] ?? []
    const i = list.findIndex((x) => x.userId === params[1])
    if (i >= 0) list.splice(i, 1)
    return [204, null]
  }],

  /* ── 감사 로그 ── */
  ['get', /^\/api\/v1\/admin\/audit-logs$/, ({ config }) => {
    const p = config.params ?? {}
    const q = String(p.q ?? '').toLowerCase()
    // 기간은 YYYY-MM-DD 날짜 — 종료일은 그날 끝까지 포함 (서버와 같은 해석)
    const from = p.from ? new Date(`${p.from}T00:00:00+09:00`).getTime() : -Infinity
    const to = p.to ? new Date(`${p.to}T23:59:59.999+09:00`).getTime() : Infinity
    const list = MOCK_AUDIT_LOGS.filter((l) => {
      const at = new Date(l.at).getTime()
      return at >= from && at <= to && (!p.action || l.action === p.action)
        && (!q || `${l.loginId ?? ''} ${l.target ?? ''} ${l.detail ?? ''}`.toLowerCase().includes(q))
    })
    return [200, paginate(list, p)]
  }],
  ['post', /^\/api\/v1\/audit\/access$/, () => [204, null]],

  /* ── 라이선스·시스템 ── */
  ['get', /^\/api\/v1\/system\/license-status$/, () =>
    [200, isLicenseInvalid() ? { valid: false, reason: '라이선스 기간이 만료되었습니다.', expiresAt: '2026-09-30T23:59:59+09:00' } : MOCK_LICENSE_STATUS]],
  ['get', /^\/api\/v1\/system\/info$/, ({ config }) => {
    const info = structuredClone(systemInfo)
    if (tokenUser(config) !== 'admin' && info.license) info.license = { valid: info.license.valid }
    return [200, info]
  }],
  ['post', /^\/api\/v1\/system\/license$/, ({ body }) => {
    const lic = decodeLicense(String(body?.key ?? '').trim())
    if (!lic) return err(400, 'LICENSE_INVALID', '서명을 확인할 수 없는 키입니다.')
    systemInfo.license = lic
    sessionStorage.removeItem(MOCK_LICENSE_INVALID_KEY)
    return [200, lic]
  }],
]
