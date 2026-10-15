// 와이어프레임 캡처 mock — Nexacro 런타임 없이 XFDL 좌표로 그린 화면(captures[].state = "wireframe")
import type { ElementSummary, MenuNode, ScreenDetail } from '@/types/api'

const W = 1000
const H = 600

const box = (x: number, y: number, w: number, h: number, label: string, kind: 'btn' | 'edit' | 'grid' | 'static') => {
  const fill = kind === 'grid' ? '#f3f4f6' : kind === 'btn' ? '#e5e7eb' : '#ffffff'
  const text = kind === 'static' ? `<text x="${x}" y="${y + h / 2 + 4}" font-size="12" fill="#374151">${label}</text>`
    : `<rect x="${x}" y="${y}" width="${w}" height="${h}" fill="${fill}" stroke="#6b7280" stroke-dasharray="${kind === 'grid' ? '0' : '0'}"/>`
      + `<text x="${x + 6}" y="${y + 15}" font-size="11" fill="#4b5563">${label}</text>`
  return text
}

export const PY0101_WIREFRAME_SVG = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" font-family="'Malgun Gothic', sans-serif">
<rect width="${W}" height="${H}" fill="#fafafa"/>
<rect x="0" y="0" width="${W}" height="28" fill="#d1d5db"/><text x="10" y="19" font-size="13" font-weight="700" fill="#111827">PY0101 월급여계산 (wireframe · PY0101.xfdl)</text>
${box(20, 44, 60, 24, '급여년월', 'static')}${box(90, 44, 120, 24, 'edtPayYm', 'edit')}
${box(230, 44, 40, 24, '부서', 'static')}${box(272, 44, 150, 24, 'cboDept (Combo)', 'edit')}
${box(820, 44, 70, 24, 'btnSearch', 'btn')}${box(900, 44, 80, 24, 'btnCalc', 'btn')}
${box(20, 84, 960, 420, 'grdPay (Grid · binddataset=ds_pay)', 'grid')}
${box(820, 520, 70, 26, 'btnSave', 'btn')}${box(900, 520, 80, 26, 'btnExcel', 'btn')}
</svg>`

const el = (seqNo: number, code: string, label: string, type: ElementSummary['type'], b: [number, number, number, number], handler: string | null, binding: string | null = null): ElementSummary => ({
  elementId: `el-py0101-${code}`, seqNo, elementCode: code, label, type,
  box: { x: b[0], y: b[1], w: b[2], h: b[3] }, binding, matched: 'static-only', danger: code === 'btnCalc',
  events: handler ? [{ event: 'onclick', handler }] : [], manual: false, hidden: false,
})

export const PY0101_DETAIL: ScreenDetail = {
  screenId: 'scr-py0101', screenCode: 'PY0101', name: '월급여계산', kind: 'page', uiTech: 'nexacro',
  // 화면 위치가 HTTP 경로가 아니다 (Nexacro 폼 ID) — 링크로 쓰지 않는다
  sourceFile: 'PY::PY0101.xfdl',
  menuPath: ['급여시스템', '급여계산', '월급여계산'],
  analysis: { status: 'AUTO', owner: null, reviewer: null, updatedAt: '2026-10-15T09:00:00+09:00' },
  capture: { captureId: 'cap-py0101-wf', width: W, height: H, collectedAt: '2026-10-15T09:00:00+09:00', runSeq: 1, state: 'wireframe' },
  elements: [
    el(1, 'edtPayYm', '급여년월', 'input', [90, 44, 120, 24], null, 'ds_search.PAY_YM'),
    el(2, 'cboDept', '부서', 'combo', [272, 44, 150, 24], null, 'ds_search.DEPT_CD'),
    el(3, 'btnSearch', '조회', 'button', [820, 44, 70, 24], 'fn_search'),
    el(4, 'btnCalc', '급여계산', 'button', [900, 44, 80, 24], 'fn_calc'),
    el(5, 'grdPay', '급여 목록', 'grid', [20, 84, 960, 420], null, 'ds_pay'),
    el(6, 'btnSave', '저장', 'button', [820, 520, 70, 26], 'fn_save'),
    el(7, 'btnExcel', '엑셀', 'button', [900, 520, 80, 26], 'fn_excel'),
  ],
}

export const PY_MENU: MenuNode[] = [
  {
    menuId: 'm-py-01', name: '급여계산', order: 1, screen: null, children: [
      { menuId: 'm-py-0101', name: '월급여계산', order: 1, screen: { screenId: 'scr-py0101', screenCode: 'PY0101', kind: 'page', status: 'AUTO' }, children: [] },
    ],
  },
]
