import api from '@/utils/axios'
import type { Code, CodeGroup, Page, PageQuery, Term, TermPayload } from '@/types/common'

const groupPath = (groupCode: string) => `/api/v1/codes/groups/${encodeURIComponent(groupCode)}`

/* ── 공통코드 ─────────────────────────────────────────────── */
export const getCodeGroupsApi = () =>
  api.get<unknown, CodeGroup[]>('/api/v1/codes/groups')

export const createCodeGroupApi = (payload: CodeGroup) =>
  api.post<unknown, CodeGroup>('/api/v1/codes/groups', payload)

export const updateCodeGroupApi = (groupCode: string, payload: CodeGroup) =>
  api.put<unknown, CodeGroup>(groupPath(groupCode), payload)

export const deleteCodeGroupApi = (groupCode: string) =>
  api.delete<unknown, void>(groupPath(groupCode))

export const getCodesApi = (groupCode: string) =>
  api.get<unknown, Code[]>(`${groupPath(groupCode)}/codes`)

// 일괄 저장 — 목록에 없는 코드는 서버에서 삭제된다
export const saveCodesApi = (groupCode: string, codes: Code[]) =>
  api.put<unknown, Code[]>(`${groupPath(groupCode)}/codes`, codes)

// 화면 콤보용 (사용 중인 코드만)
export const getCodeMapApi = (groups: string[]) =>
  api.get<unknown, Record<string, Code[]>>('/api/v1/codes', { params: { groups: groups.join(',') } })

/* ── 용어 ─────────────────────────────────────────────────── */
export const getTermsApi = (query: PageQuery & { q?: string }) =>
  api.get<unknown, Page<Term>>('/api/v1/terms', { params: query })

export const createTermApi = (payload: TermPayload) =>
  api.post<unknown, Term>('/api/v1/terms', payload)

export const updateTermApi = (termId: string, payload: TermPayload) =>
  api.put<unknown, Term>(`/api/v1/terms/${termId}`, payload)

export const deleteTermApi = (termId: string) =>
  api.delete<unknown, void>(`/api/v1/terms/${termId}`)
