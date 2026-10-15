<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { SrSla } from '@/types/r3'
import { statusColorOf } from '@/utils/quality'
import { liveRemainingMinutes, liveSlaState, slaRemainingLabel } from '@/utils/sm'

// SLA 상태 칩 + 남은 시간 카운트다운 (FR-SM-005). now 는 부모가 useNow 로 한 번만 돌려 넘긴다.
const props = withDefaults(defineProps<{
  sla: SrSla | null
  /** 이 SR 을 서버에서 받은 시각(ms) */
  fetchedAt: number
  now: number
  size?: 'x-small' | 'small'
  /** true 면 남은 시간 문구를 같이 보여 준다 */
  showRemaining?: boolean
}>(), { size: 'small', showRemaining: true })

const { t } = useI18n()

const remaining = computed(() => (props.sla ? liveRemainingMinutes(props.sla, props.fetchedAt, props.now) : 0))
const state = computed(() => (props.sla ? liveSlaState(props.sla, remaining.value) : null))
const text = computed(() => {
  if (!props.sla || !state.value) return ''
  if (state.value === 'MET') return t('sm.sla.met')
  if (state.value === 'PAUSED') return t('sm.sla.paused')
  return slaRemainingLabel(remaining.value, t)
})
</script>

<template>
  <span v-if="!sla" class="text-caption text-muted">-</span>
  <span v-else class="d-inline-flex align-center ga-1 sla-badge" :title="t('sm.sla.dueTitle', { due: sla.resolveDue.slice(0, 16).replace('T', ' '), policy: sla.policyName })">
    <VChip :color="statusColorOf(state)" :size="size" variant="tonal" label class="font-weight-medium" data-test="sla-state">
      <VIcon v-if="state === 'BREACHED'" icon="ri-alarm-warning-line" size="13" start />
      <VIcon v-else-if="state === 'DUE_SOON'" icon="ri-timer-flash-line" size="13" start />
      {{ t(`sm.slaState.${state}`) }}
    </VChip>
    <span v-if="showRemaining" :class="['text-caption font-mono', { 'text-error font-weight-bold': state === 'BREACHED', 'text-warning': state === 'DUE_SOON' }]" data-test="sla-remaining">{{ text }}</span>
  </span>
</template>

<style scoped>
.sla-badge {
  white-space: nowrap;
}
</style>
