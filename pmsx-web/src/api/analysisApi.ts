import api from '@/utils/axios'
import type {
  AnalysisAssign, AnalysisHistoryItem, AnalysisProgress, AnalysisRow, AnalysisScreenQuery, Note, NoteTargetType,
} from '@/types/api'
import type { Page } from '@/types/common'

/* ── 메모 (docs/11 §2) ───────────────────────────────────── */
export const getNotesApi = (targetType: NoteTargetType, targetId: string) =>
  api.get<unknown, Note[]>('/api/v1/notes', { params: { targetType, targetId } })

export const createNoteApi = (payload: { targetType: NoteTargetType; targetId: string; content: string }) =>
  api.post<unknown, Note>('/api/v1/notes', payload)

export const updateNoteApi = (noteId: string, content: string) =>
  api.put<unknown, Note>(`/api/v1/notes/${noteId}`, { content })

export const deleteNoteApi = (noteId: string) =>
  api.delete<unknown, void>(`/api/v1/notes/${noteId}`)

/* ── 분석 상태·진척 ──────────────────────────────────────── */
export const getAnalysisHistoryApi = (screenId: string) =>
  api.get<unknown, AnalysisHistoryItem[]>(`/api/v1/screens/${screenId}/analysis/history`)

export const assignAnalysisApi = (systemId: string, payload: AnalysisAssign) =>
  api.post<unknown, { updated: number }>(`/api/v1/systems/${systemId}/analysis/assign`, payload)

export const getAnalysisProgressApi = (systemId: string) =>
  api.get<unknown, AnalysisProgress>(`/api/v1/systems/${systemId}/analysis/progress`)

export const getAnalysisScreensApi = (systemId: string, query: AnalysisScreenQuery) =>
  api.get<unknown, Page<AnalysisRow>>(`/api/v1/systems/${systemId}/analysis/screens`, { params: query })
