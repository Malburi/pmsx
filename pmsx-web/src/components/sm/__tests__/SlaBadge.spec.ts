import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { createI18n } from 'vue-i18n'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import SlaBadge from '@/components/sm/SlaBadge.vue'
import koR3 from '@/locales/ko.r3.json'
import type { SrSla } from '@/types/r3'

const vuetify = createVuetify({ components, directives })
const i18n = createI18n({ legacy: false, locale: 'ko', missingWarn: false, fallbackWarn: false, messages: { ko: koR3 } })

const sla = (over: Partial<SrSla> = {}): SrSla => ({
  policyId: 'p', policyName: 'P2 높음', responseDue: '2026-10-15T10:00:00+09:00', resolveDue: '2026-10-15T18:00:00+09:00',
  respondedAt: null, resolvedAt: null, pausedMinutes: 0, responseBreached: false, resolveBreached: false,
  remainingMinutes: 40, state: 'DUE_SOON', ...over,
})

const mountBadge = (s: SrSla, elapsedMin: number) => mount(SlaBadge, {
  global: { plugins: [vuetify, i18n] },
  props: { sla: s, fetchedAt: 0, now: elapsedMin * 60_000 },
})

describe('SlaBadge', () => {
  it('남은 시간을 줄여 가며 보여 준다', () => {
    const w = mountBadge(sla(), 10)
    expect(w.get('[data-test="sla-state"]').text()).toContain('임박')
    expect(w.get('[data-test="sla-remaining"]').text()).toBe('30분 남음')
  })

  it('기한을 넘기면 위반·초과 시간으로 바뀐다', () => {
    const w = mountBadge(sla(), 55)
    expect(w.get('[data-test="sla-state"]').text()).toContain('위반')
    expect(w.get('[data-test="sla-remaining"]').text()).toBe('15분 초과')
  })

  it('보류 중이면 시간이 멈춘다', () => {
    const w = mountBadge(sla({ state: 'PAUSED' }), 120)
    expect(w.get('[data-test="sla-state"]').text()).toContain('일시정지')
    expect(w.get('[data-test="sla-remaining"]').text()).toContain('보류 중')
  })
})
