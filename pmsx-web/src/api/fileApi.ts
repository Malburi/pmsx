import api from '@/utils/axios'
import type { FileMeta } from '@/types/common'

export const uploadFileApi = (file: File, targetType: string, targetId: string, onProgress?: (ratio: number) => void) => {
  const form = new FormData()
  form.append('file', file)
  form.append('targetType', targetType)
  form.append('targetId', targetId)
  return api.post<unknown, FileMeta>('/api/v1/files', form, {
    timeout: 300_000,
    onUploadProgress: (e) => { if (onProgress && e.total) onProgress(e.loaded / e.total) },
  })
}

export const getAttachmentsApi = (targetType: string, targetId: string) =>
  api.get<unknown, FileMeta[]>('/api/v1/attachments', { params: { targetType, targetId } })

// 인증 헤더가 필요해 링크로 직접 받을 수 없다 → blob 으로 받아 저장한다
export const downloadFileApi = (fileId: string) =>
  api.get<unknown, Blob>(`/api/v1/files/${fileId}`, { responseType: 'blob', timeout: 300_000 })

export const previewFileApi = (fileId: string) =>
  api.get<unknown, Blob>(`/api/v1/files/${fileId}/preview`, { responseType: 'blob' })

export const deleteAttachmentApi = (attachId: string) =>
  api.delete<unknown, void>(`/api/v1/attachments/${attachId}`)
