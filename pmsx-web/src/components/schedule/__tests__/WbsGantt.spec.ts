import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { createI18n } from 'vue-i18n'
import WbsGantt from '@/components/schedule/WbsGantt.vue'
import AllocHeatmap from '@/components/resource/AllocHeatmap.vue'
import type { WbsItem } from '@/types/r2'

const i18n = createI18n({ legacy: false, locale: 'ko', missingWarn: false, fallbackWarn: false, messages: { ko: {} } })

const item = (wbsId: string, planStart: string, planEnd: string, extra: Partial<WbsItem> = {}): WbsItem => ({
  wbsId, parentId: null, wbsNo: wbsId, name: wbsId, order: 0, planStart, planEnd, actStart: null, actEnd: null, weight: 1, progress: 40,
  milestone: false, status: 'IN_PROGRESS', assignees: [], deliverableId: null, deps: [], critical: false, baselineStart: null, baselineEnd: null, ...extra,
})

const mountGantt = (editable = true) => mount(WbsGantt, {
  global: { plugins: [i18n] },
  props: {
    rows: [item('a', '2026-11-02', '2026-11-06'), item('b', '2026-11-09', '2026-11-13', { deps: [{ predId: 'a', type: 'FS', lagDays: 0 }], critical: true })],
    range: { start: '2026-11-01', end: '2026-11-30' },
    scale: 'week',
    today: '2026-11-04',
    editable,
    parentIds: new Set<string>(),
  },
})

describe('WbsGantt', () => {
  it('막대·선후행 화살표·오늘선을 그린다', () => {
    const w = mountGantt()
    expect(w.findAll('.task-bar')).toHaveLength(2)
    expect(w.findAll('path.dep')).toHaveLength(1)
    expect(w.find('.task.crit').exists()).toBe(true)
    expect(w.find('line.today').exists()).toBe(true)
  })

  it('막대를 끌면 날짜 칸 단위로 반올림한 새 기간을 알린다', async () => {
    const w = mountGantt()
    const bar = w.findAll('.task-bar')[0]
    await bar.trigger('pointerdown', { clientX: 100, pointerId: 1 })
    const svg = w.find('svg.gantt-body')
    await svg.trigger('pointermove', { clientX: 150 }) // 50px = 약 2일(24px/일)
    await svg.trigger('pointerup')
    expect(w.emitted('change')?.[0]).toEqual(['a', '2026-11-04', '2026-11-08'])
  })

  it('편집 권한이 없으면 끌어도 바뀌지 않는다', async () => {
    const w = mountGantt(false)
    await w.findAll('.task-bar')[0].trigger('pointerdown', { clientX: 100, pointerId: 1 })
    await w.find('svg.gantt-body').trigger('pointermove', { clientX: 200 })
    await w.find('svg.gantt-body').trigger('pointerup')
    expect(w.emitted('change')).toBeUndefined()
    expect(w.emitted('select')?.[0]).toEqual(['a'])
  })
})

describe('AllocHeatmap', () => {
  it('100% 초과 칸을 과배정으로 표시한다', () => {
    const w = mount(AllocHeatmap, {
      global: { plugins: [i18n], stubs: { VIcon: true, VSpacer: true } },
      props: { rows: [{ userId: 'u1', name: '홍길동', cells: { '2026-11': 130, '2026-12': 60 } }], periods: ['2026-11', '2026-12'] },
    })
    const cells = w.findAll('td.cell')
    expect(cells[0].classes()).toContain('lv-over')
    expect(cells[1].classes()).toContain('lv-normal')
  })
})
