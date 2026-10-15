// blob 응답을 파일로 저장한다 (인증이 필요한 다운로드는 링크로 직접 받을 수 없다)
export const saveBlob = (blob: Blob, fileName: string) => {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
  // 일부 브라우저는 click 직후 해제하면 저장이 취소되므로 잠시 뒤 해제
  setTimeout(() => URL.revokeObjectURL(url), 1_000)
}

const pad = (n: number) => String(n).padStart(2, '0')

/** 내보내기 파일명: <이름>_yyyyMMdd_HHmm.xlsx */
export const excelFileName = (name: string, now = new Date()) =>
  `${name.replace(/[\/:*?"<>|]/g, '_')}_${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}_${pad(now.getHours())}${pad(now.getMinutes())}.xlsx`
