// 첨부파일 클라이언트 검증 (file-upload §1) — 서버 기본값(docs/10 §2)과 맞춘다
export const MAX_FILE_SIZE = 50 * 1024 * 1024

export const ALLOWED_EXT = [
  'pdf', 'png', 'jpg', 'jpeg', 'gif', 'svg', 'txt', 'csv', 'zip',
  'xlsx', 'xls', 'docx', 'doc', 'pptx', 'ppt', 'hwp', 'hwpx', 'md', 'json', 'xml', 'sql',
]

export const fileExt = (name: string) => (name.includes('.') ? name.split('.').pop()!.toLowerCase() : '')

export type FileCheck = 'ok' | 'tooLarge' | 'typeNotAllowed'

export const checkFile = (file: { name: string; size: number }): FileCheck => {
  if (file.size > MAX_FILE_SIZE) return 'tooLarge'
  if (!ALLOWED_EXT.includes(fileExt(file.name))) return 'typeNotAllowed'
  return 'ok'
}

export const isPreviewable = (mime: string) => mime.startsWith('image/') || mime === 'application/pdf'

export const formatBytes = (n: number): string => {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}
