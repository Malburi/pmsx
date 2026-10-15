import api from '@/utils/axios'
import type { ExcelDataset, ExcelTemplate, ExcelTemplateMeta, ImportPreview, ImportResult } from '@/types/common'

export type ExcelParams = Record<string, string | number | undefined>

export const getExcelDatasetsApi = () =>
  api.get<unknown, ExcelDataset[]>('/api/v1/excel/datasets')

export const exportExcelApi = (datasetId: string, params: ExcelParams = {}, templateId?: string) =>
  api.get<unknown, Blob>(`/api/v1/excel/export/${datasetId}`, {
    params: { ...params, templateId },
    responseType: 'blob',
    timeout: 300_000,
  })

export const validateExcelImportApi = (datasetId: string, file: File, params: ExcelParams = {}) => {
  const form = new FormData()
  form.append('file', file)
  Object.entries(params).forEach(([k, v]) => { if (v !== undefined) form.append(k, String(v)) })
  return api.post<unknown, ImportPreview>(`/api/v1/excel/import/${datasetId}/validate`, form, { timeout: 300_000 })
}

export const commitExcelImportApi = (datasetId: string, token: string) =>
  api.post<unknown, ImportResult>(`/api/v1/excel/import/${datasetId}/commit`, { token })

/* ── 고객사 템플릿 ────────────────────────────────────────── */
const templateForm = (meta: ExcelTemplateMeta, file?: File | null) => {
  const form = new FormData()
  form.append('meta', new Blob([JSON.stringify(meta)], { type: 'application/json' }))
  if (file) form.append('file', file)
  return form
}

export const getExcelTemplatesApi = () =>
  api.get<unknown, ExcelTemplate[]>('/api/v1/excel/templates')

export const createExcelTemplateApi = (meta: ExcelTemplateMeta, file: File) =>
  api.post<unknown, ExcelTemplate>('/api/v1/excel/templates', templateForm(meta, file))

export const updateExcelTemplateApi = (templateId: string, meta: ExcelTemplateMeta, file?: File | null) =>
  api.put<unknown, ExcelTemplate>(`/api/v1/excel/templates/${templateId}`, templateForm(meta, file))

export const deleteExcelTemplateApi = (templateId: string) =>
  api.delete<unknown, void>(`/api/v1/excel/templates/${templateId}`)
