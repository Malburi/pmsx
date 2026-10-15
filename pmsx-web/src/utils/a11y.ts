// 접근성 보조 (accessibility-a11y). 클릭으로 상세를 여는 표의 행을 키보드로도 열 수 있게 한다.
// - 행에 tabindex=0 을 준다 (row-props 에 focusableRow 또는 tabindex: 0 포함)
// - 포커스된 행에서 Enter·Space 를 누르면 그 행을 클릭한 것과 같게 처리한다 (installRowKeyboard)

export const focusableRow = { tabindex: 0 }

/** Enter·Space 로 표 행을 여는지 판단 (단위 테스트용으로 분리) */
export const isRowActivationKey = (e: Pick<KeyboardEvent, 'key' | 'target'>): HTMLElement | null => {
  if (e.key !== 'Enter' && e.key !== ' ') return null
  const el = e.target as HTMLElement | null
  if (!el || el.tagName !== 'TR' || el.getAttribute('tabindex') !== '0') return null
  return el.closest('table') ? el : null
}

export const installRowKeyboard = (doc: Document = document) => {
  doc.addEventListener('keydown', (e) => {
    const row = isRowActivationKey(e)
    if (!row) return
    e.preventDefault()
    row.click()
  })
}
