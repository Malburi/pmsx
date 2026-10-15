import api from '@/utils/axios'
import type { Erd, Relation, RelationCreate, RelationDecision, TableDetail, TableSummary } from '@/types/api'
import type { Page } from '@/types/common'

/* ── 테이블 정의서·ERD (docs/11 §3) ──────────────────────── */
export const getTablesApi = (systemId: string, query: { q?: string; subjectArea?: string; page?: number; size?: number }) =>
  api.get<unknown, Page<TableSummary>>(`/api/v1/systems/${systemId}/tables`, { params: query })

export const getTableApi = (tableId: string) =>
  api.get<unknown, TableDetail>(`/api/v1/tables/${tableId}`)

export const updateTableApi = (tableId: string, payload: { subjectArea?: string | null; comment?: string | null }) =>
  api.put<unknown, TableSummary>(`/api/v1/tables/${tableId}`, payload)

export const getSubjectAreasApi = (systemId: string) =>
  api.get<unknown, { name: string; tableCount: number }[]>(`/api/v1/systems/${systemId}/subject-areas`)

export const getErdApi = (systemId: string, query: { subjectArea?: string; tableIds?: string; includeInferred?: boolean }) =>
  api.get<unknown, Erd>(`/api/v1/systems/${systemId}/erd`, { params: query, timeout: 60_000 })

export const createRelationApi = (payload: RelationCreate) =>
  api.post<unknown, Relation>('/api/v1/relations', payload)

export const decideRelationApi = (relationId: string, decision: RelationDecision) =>
  api.patch<unknown, Relation>(`/api/v1/relations/${relationId}`, { decision })
