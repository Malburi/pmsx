<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { compareBaselineApi, getBaselinesApi } from '@/api/scheduleApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import type { ApiError } from '@/types/api'
import type { Baseline, BaselineCompareRow } from '@/types/r2'
import { diffDays } from '@/utils/date'
import { formatDateTime } from '@/utils/format'
import { ganttRange } from '@/utils/gantt'

// SCR-PJ-003 베이스라인 비교 (FR-PJ-005)
const { t } = useI18n()

const baselines = ref<Baseline[]>([])
const baselineId = ref<string | null>(null)
const rows = ref<BaselineCompareRow[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const onlyChanged = ref(false)

// ─────────── 헬퍼 ───────────
const loadBaselines = async (projectId: string) => {
  error.value = null
  try {
    baselines.value = await getBaselinesApi(projectId)
    baselineId.value = baselines.value[baselines.value.length - 1]?.baselineId ?? null
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  }
}
const { projectId } = useCurrentProject(loadBaselines)

const loadCompare = async (id: string) => {
  loading.value = true
  try {
    rows.value = await compareBaselineApi(id)
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}

const varClass = (v: number | null) => (v === null ? 'text-muted' : v > 0 ? 'text-error font-weight-bold' : v < 0 ? 'text-success' : '')
const varText = (v: number | null) => (v === null ? '-' : v > 0 ? `+${v}` : String(v))

// ─────────── computed ───────────
const items = computed(() => baselines.value.map((b) => ({ title: `${b.name} (${formatDateTime(b.createdAt)})`, value: b.baselineId })))
const shown = computed(() => (onlyChanged.value ? rows.value.filter((r) => (r.startVarDays ?? 0) !== 0 || (r.endVarDays ?? 0) !== 0) : rows.value))
const range = computed(() => ganttRange(rows.value.flatMap((r) => [
  { planStart: r.planStart, planEnd: r.planEnd }, { planStart: r.baseStart, planEnd: r.baseEnd },
]), '2026-10-15', 3))
const totalDays = computed(() => Math.max(1, diffDays(range.value.start, range.value.end) + 1))
// 표시 범위 대비 위치·길이(%) — 표 셀 폭에 맞춰 늘어난다
const bar = (s: string | null, e: string | null) => {
  if (!s || !e) return null
  const left = (diffDays(range.value.start, s) / totalDays.value) * 100
  const width = ((diffDays(s, e) + 1) / totalDays.value) * 100
  return { left: `${left}%`, width: `${Math.max(0.5, width)}%` }
}
const summary = computed(() => ({
  delayed: rows.value.filter((r) => (r.endVarDays ?? 0) > 0).length,
  early: rows.value.filter((r) => (r.endVarDays ?? 0) < 0).length,
  maxDelay: Math.max(0, ...rows.value.map((r) => r.endVarDays ?? 0)),
}))
const headers = computed(() => [
  { title: t('pj.wbs.colNo'), key: 'wbsNo', width: 70 },
  { title: t('pj.wbs.colName'), key: 'name', minWidth: 180 },
  { title: t('pj.baseline.base'), key: 'base', sortable: false, width: 190 },
  { title: t('pj.baseline.current'), key: 'current', sortable: false, width: 190 },
  { title: t('pj.baseline.startVar'), key: 'startVarDays', align: 'end' as const, width: 90 },
  { title: t('pj.baseline.endVar'), key: 'endVarDays', align: 'end' as const, width: 90 },
  { title: t('pj.baseline.timeline'), key: 'timeline', sortable: false, minWidth: 220 },
])

// ─────────── watch ───────────
watch(baselineId, (id) => { if (id) void loadCompare(id); else rows.value = [] })
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.schedule.baseline')" :subtitle="t('pj.baseline.subtitle')">
      <VSelect v-model="baselineId" :items="items" :label="t('pj.baseline.select')" hide-details class="bl-select" data-test="baseline-select" />
    </AppPageHeader>
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <template v-else>
      <VAlert v-if="error" type="error" variant="tonal" density="compact" class="mb-3">{{ error }}</VAlert>
      <VAlert v-if="!baselines.length && !error" type="info" variant="tonal" class="mb-3">{{ t('pj.baseline.none') }}</VAlert>
      <div v-if="rows.length" class="d-flex flex-wrap ga-3 mb-3">
        <VChip color="error" variant="tonal" label>{{ t('pj.baseline.delayedN', { n: summary.delayed }) }}</VChip>
        <VChip color="success" variant="tonal" label>{{ t('pj.baseline.earlyN', { n: summary.early }) }}</VChip>
        <VChip variant="tonal" label>{{ t('pj.baseline.maxDelay', { n: summary.maxDelay }) }}</VChip>
        <VSpacer />
        <VCheckbox v-model="onlyChanged" :label="t('pj.baseline.onlyChanged')" density="compact" hide-details />
      </div>
      <VCard border flat>
        <VDataTable :headers="headers" :items="shown" :loading="loading" density="compact" :items-per-page="50" item-value="wbsId">
          <template #item.wbsNo="{ item }"><span class="font-mono">{{ item.wbsNo }}</span></template>
          <template #item.base="{ item }"><span class="text-caption font-mono">{{ item.baseStart ?? '-' }} ~ {{ item.baseEnd ?? '-' }}</span></template>
          <template #item.current="{ item }"><span class="text-caption font-mono">{{ item.planStart ?? '-' }} ~ {{ item.planEnd ?? '-' }}</span></template>
          <template #item.startVarDays="{ item }"><span :class="varClass(item.startVarDays)">{{ varText(item.startVarDays) }}</span></template>
          <template #item.endVarDays="{ item }"><span :class="varClass(item.endVarDays)">{{ varText(item.endVarDays) }}</span></template>
          <template #item.timeline="{ item }">
            <div class="timeline" :aria-label="`${item.name} ${t('pj.baseline.timeline')}`">
              <div v-if="bar(item.baseStart, item.baseEnd)" class="tl base" :style="bar(item.baseStart, item.baseEnd) ?? {}" />
              <div v-if="bar(item.planStart, item.planEnd)" class="tl cur" :class="{ late: (item.endVarDays ?? 0) > 0 }" :style="bar(item.planStart, item.planEnd) ?? {}" />
            </div>
          </template>
        </VDataTable>
      </VCard>
      <div class="d-flex ga-4 text-caption text-muted mt-2">
        <span><i class="lg base" />{{ t('pj.baseline.base') }}</span>
        <span><i class="lg cur" />{{ t('pj.baseline.current') }}</span>
        <span><i class="lg late" />{{ t('pj.baseline.late') }}</span>
      </div>
    </template>
  </VContainer>
</template>

<style scoped>
.bl-select {
  min-width: 280px;
}

.timeline {
  position: relative;
  height: 20px;
  background: rgba(var(--v-theme-on-surface), 0.03);
  border-radius: 3px;
}

.tl {
  position: absolute;
  border-radius: 2px;
}

.tl.base {
  top: 12px;
  height: 5px;
  background: rgba(var(--v-theme-on-surface), 0.3);
}

.tl.cur {
  top: 3px;
  height: 8px;
  background: rgb(var(--v-theme-primary));
}

.tl.cur.late {
  background: rgb(var(--v-theme-error));
}

.lg {
  display: inline-block;
  width: 16px;
  height: 6px;
  border-radius: 2px;
  margin-right: 4px;
  vertical-align: middle;
}

.lg.base { background: rgba(var(--v-theme-on-surface), 0.3); }
.lg.cur { background: rgb(var(--v-theme-primary)); }
.lg.late { background: rgb(var(--v-theme-error)); }
</style>
