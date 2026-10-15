import { describe, expect, it } from 'vitest'
import { isRowActivationKey } from '@/utils/a11y'
import { fitColumns, isColumnHidden } from '@/utils/columns'

describe('fitColumns', () => {
  const headers = [
    { key: 'no', title: '번호', width: 90 },
    { key: 'title', title: '제목' },
    { key: 'priority', title: '우선순위', width: 76, hideBelow: 860 },
    { key: 'assignee', title: '담당자', width: 84, hideBelow: 800 },
  ]

  it('넓으면 모든 열을 보여 주고 hideBelow 속성은 지운다', () => {
    const out = fitColumns(headers, 1000)
    expect(out.map((h) => h.key)).toEqual(['no', 'title', 'priority', 'assignee'])
    expect(out.every((h) => !('hideBelow' in h))).toBe(true)
  })

  it('좁으면 기준보다 좁은 열을 숨긴다', () => {
    expect(fitColumns(headers, 820).map((h) => h.key)).toEqual(['no', 'title', 'assignee'])
    expect(fitColumns(headers, 600).map((h) => h.key)).toEqual(['no', 'title'])
  })

  it('isColumnHidden 은 숨김 여부를 알려 준다', () => {
    expect(isColumnHidden(headers, 820, 'priority')).toBe(true)
    expect(isColumnHidden(headers, 900, 'priority')).toBe(false)
    expect(isColumnHidden(headers, 100, 'title')).toBe(false)
  })
})

describe('isRowActivationKey', () => {
  const makeRow = (tabindex: string | null) => {
    const table = document.createElement('table')
    const tbody = document.createElement('tbody')
    const tr = document.createElement('tr')
    if (tabindex !== null) tr.setAttribute('tabindex', tabindex)
    tbody.appendChild(tr)
    table.appendChild(tbody)
    return tr
  }

  it('포커스 가능한 표 행에서 Enter·Space 만 반응한다', () => {
    const tr = makeRow('0')
    expect(isRowActivationKey({ key: 'Enter', target: tr })).toBe(tr)
    expect(isRowActivationKey({ key: ' ', target: tr })).toBe(tr)
    expect(isRowActivationKey({ key: 'a', target: tr })).toBeNull()
  })

  it('tabindex 없는 행이나 행이 아닌 요소는 무시한다', () => {
    expect(isRowActivationKey({ key: 'Enter', target: makeRow(null) })).toBeNull()
    expect(isRowActivationKey({ key: 'Enter', target: document.createElement('button') })).toBeNull()
  })
})
