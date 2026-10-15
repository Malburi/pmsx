import api from '@/utils/axios'
import type { RunDiffDetail, RunSummary } from '@/types/api'

export const uploadRunApi = (systemId: string, file: File, onProgress?: (ratio: number) => void) => {
  const form = new FormData()
  form.append('file', file)
  return api.post<unknown, RunSummary>(`/api/v1/systems/${systemId}/runs`, form, {
    timeout: 300_000, // 대용량 패키지 업로드
    onUploadProgress: (e) => { if (onProgress && e.total) onProgress(e.loaded / e.total) },
  })
}

export const getRunsApi = (systemId: string) =>
  api.get<unknown, RunSummary[]>(`/api/v1/systems/${systemId}/runs`)

// 리포트는 인증이 필요해 blob 으로 받아 새 탭에서 연다.
export const getRunReportApi = (runId: string) =>
  api.get<unknown, Blob>(`/api/v1/runs/${runId}/report`, { responseType: 'blob' })

export const getRunDiffApi = (runId: string) =>
  api.get<unknown, RunDiffDetail>(`/api/v1/runs/${runId}/diff`)
