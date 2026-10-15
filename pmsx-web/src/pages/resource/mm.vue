<route lang="yaml">
meta:
  roles: [PMO, PM, PL]
</route>

<script setup lang="ts">
import type { EChartsCoreOption } from 'echarts/core'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useTheme } from 'vuetify'
import { getMmSummaryApi } from '@/api/resourceApi'
import AppChart from '@/components/common/AppChart.vue'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import ExcelExportButton from '@/components/common/ExcelExportButton.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import type { ApiError } from '@/types/api'
import type { MmSummaryRow } from '@/types/r2'
import { monthRange, today } from '@/utils/date'
import { sumMm } from '@/utils/quality'

// SCR-RS-005 MM 실적·정산 (FR-RS-007·008)
const { t } = useI18n()
const theme = useTheme()

const rows = ref<MmSummaryRow[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const settleMonth = ref(today().slice(0, 7))

// ─────────── 헬퍼 ───────────
const load = async (projectId: string) => {
  loading.value = true
  error.value = null
  try {
    rows.value = await getMmSummaryApi(projectId, range.value.from, range.value.to)
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const { projectId, project } = useCurrentProject(load)

const color = (name: string) => theme.current.value.colors[name] as string
const monthLabel = (m: string) => `${m.slice(2, 4)}.${m.slice(5, 7)}`
const round2 = (n: number) => Math.round(n * 100) / 100
const rate = (r: MmSummaryRow) => {
  const pastPlan = sumMm(Object.fromEntries(Object.entries(r.plan).filter(([m]) => m in r.actual)))
  return pastPlan ? Math.round((sumMm(r.actual) / pastPlan) * 100) : null
}

// ─────────── computed ───────────
const range = computed(() => ({ from: (project.value?.startDate ?? today()).slice(0, 7), to: (project.value?.endDate ?? today()).slice(0, 7) }))
const months = computed(() => monthRange(range.value.from, range.value.to))
const monthItems = computed(() => months.value.map((m) => ({ title: m, value: m })))
const totals = computed(() => ({
  plan: round2(rows.value.reduce((s, r) => s + sumMm(r.plan), 0)),
  actual: round2(rows.value.reduce((s, r) => s + sumMm(r.actual), 0)),
}))
const partners = computed(() => {
  const by = new Map<string, { company: string; people: { name: string; grade: string | null; mm: number }[] }>()
  rows.value.filter((r) => r.orgType === 'PARTNER').forEach((r) => {
    const g = by.get(r.company) ?? { company: r.company, people: [] }
    g.people.push({ name: r.name, grade: r.grade, mm: r.actual[settleMonth.value] ?? 0 })
    by.set(r.company, g)
  })
  return [...by.values()]
})
const option = computed<EChartsCoreOption>(() => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
  legend: { bottom: 0, icon: 'circle' },
  grid: { left: 8, right: 16, top: 12, bottom: 36, containLabel: true },
  xAxis: { type: 'category', data: months.value.map(monthLabel), axisLine: { lineStyle: { color: color('line') } } },
  yAxis: { type: 'value', splitLine: { lineStyle: { color: color('line') } } },
  series: [
    { name: t('rs.mm.plan'), type: 'bar', barMaxWidth: 18, itemStyle: { color: color('secondary') }, data: months.value.map((m) => round2(rows.value.reduce((s, r) => s + (r.plan[m] ?? 0), 0))) },
    { name: t('rs.mm.actual'), type: 'bar', barMaxWidth: 18, itemStyle: { color: color('primary') }, data: months.value.map((m) => (rows.value.some((r) => m in r.actual) ? round2(rows.value.reduce((s, r) => s + (r.actual[m] ?? 0), 0)) : null)) },
  ],
}))

// ─────────── watch ───────────
// 새로고침 직후에는 프로젝트 기간을 모른 채 먼저 읽으므로, 기간이 정해지면 다시 읽는다
watch(() => `${range.value.from}~${range.value.to}`, () => { if (projectId.value) void load(projectId.value) })
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.resource.mm')" :subtitle="t('rs.mm.subtitle')" />
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <template v-else>
      <VAlert v-if="error" type="error" variant="tonal" density="compact" class="mb-3">{{ error }}</VAlert>
      <VProgressLinear v-if="loading" indeterminate color="primary" class="mb-2" />
      <VRow>
        <VCol cols="12" md="8">
          <VCard border flat :title="t('rs.mm.monthly')">
            <template #append>
              <span class="text-body-2">{{ t('rs.mm.totals', { plan: totals.plan, actual: totals.actual }) }}</span>
            </template>
            <AppChart :option="option" :height="240" :label="t('rs.mm.monthly')" class="px-2" />
          </VCard>
        </VCol>
        <VCol cols="12" md="4">
          <VCard border flat :title="t('rs.mm.settlement')">
            <VCardText>
              <div class="d-flex align-center ga-2 mb-3">
                <VSelect v-model="settleMonth" :items="monthItems" :label="t('rs.mm.month')" hide-details />
                <ExcelExportButton dataset-id="mm-settlement" :file-name="t('rs.mm.settleFile', { m: settleMonth })" :params="{ projectId, month: settleMonth }" />
              </div>
              <div v-for="g in partners" :key="g.company" class="mb-3">
                <div class="d-flex text-body-2 font-weight-medium"><span>{{ g.company }}</span><VSpacer /><span>{{ round2(g.people.reduce((s, p) => s + p.mm, 0)) }} MM</span></div>
                <div v-for="p in g.people" :key="p.name" class="d-flex text-caption text-muted"><span>{{ p.name }} ({{ p.grade }})</span><VSpacer /><span>{{ p.mm }}</span></div>
              </div>
              <div v-if="!partners.length" class="text-body-2 text-muted">{{ t('common.noData') }}</div>
            </VCardText>
          </VCard>
        </VCol>
      </VRow>
      <VCard border flat class="mt-3">
        <div class="grid-wrap">
          <table class="mm-table" data-test="mm-table">
            <thead>
              <tr>
                <th class="sticky" rowspan="2">{{ t('rs.pool.colName') }}</th>
                <th rowspan="2">{{ t('rs.pool.colCompany') }}</th>
                <th v-for="m in months" :key="m" colspan="2">{{ monthLabel(m) }}</th>
                <th colspan="2">{{ t('rs.plan.sum') }}</th>
                <th rowspan="2">{{ t('rs.mm.rate') }}</th>
              </tr>
              <tr>
                <template v-for="m in months" :key="`h-${m}`"><th class="sub">{{ t('rs.mm.p') }}</th><th class="sub">{{ t('rs.mm.a') }}</th></template>
                <th class="sub">{{ t('rs.mm.p') }}</th><th class="sub">{{ t('rs.mm.a') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in rows" :key="r.userId">
                <td class="sticky">{{ r.name }} <span class="text-caption text-muted">{{ r.grade }}</span></td>
                <td class="text-caption">{{ r.company }}</td>
                <template v-for="m in months" :key="`${r.userId}-${m}`">
                  <td class="p">{{ r.plan[m] || '' }}</td>
                  <td :class="['a', { low: m in r.actual && (r.actual[m] ?? 0) < (r.plan[m] ?? 0) * 0.9 }]">{{ m in r.actual ? r.actual[m] : '' }}</td>
                </template>
                <td class="p font-weight-bold">{{ sumMm(r.plan) }}</td>
                <td class="a font-weight-bold">{{ sumMm(r.actual) }}</td>
                <td :class="{ 'text-error': (rate(r) ?? 100) < 90 }">{{ rate(r) === null ? '-' : `${rate(r)}%` }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="text-caption text-muted pa-3">{{ t('rs.mm.hint') }}</div>
      </VCard>
    </template>
  </VContainer>
</template>

<style scoped>
.grid-wrap {
  overflow-x: auto;
}

.mm-table {
  border-collapse: collapse;
  font-size: 12px;
  min-width: 100%;
}

.mm-table th,
.mm-table td {
  border: 1px solid rgb(var(--v-theme-line));
  padding: 4px 6px;
  text-align: center;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.mm-table th {
  color: rgb(var(--v-theme-muted));
  font-weight: 600;
}

.mm-table .sub {
  font-weight: 400;
  font-size: 11px;
}

.mm-table td.a {
  background: rgba(var(--v-theme-primary), 0.05);
}

.mm-table td.a.low {
  color: rgb(var(--v-theme-error));
  font-weight: 700;
}

.sticky {
  position: sticky;
  left: 0;
  background: rgb(var(--v-theme-surface));
  text-align: left !important;
  z-index: 1;
}
</style>
