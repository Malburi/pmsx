<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { getTraceMatrixApi } from '@/api/traceApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import AppStatusChip from '@/components/common/AppStatusChip.vue'
import ExcelExportButton from '@/components/common/ExcelExportButton.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import type { ApiError } from '@/types/api'
import type { TraceGap, TraceRow } from '@/types/r2'

// SCR-DL-003 추적성 매트릭스 (FR-DL-005) — 요구사항별 화면·테스트·결함·CR 연결과 누락(갭) 표시
const { t } = useI18n()

const GAPS: TraceGap[] = ['NO_SCREEN', 'NO_TEST', 'TEST_FAILED', 'OPEN_DEFECT']
const rows = ref<TraceRow[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const gapFilter = ref<TraceGap | null>(null)
const q = ref('')

const load = async (projectId: string) => {
  loading.value = true
  error.value = null
  try {
    rows.value = await getTraceMatrixApi(projectId)
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const { projectId } = useCurrentProject(load)

const resultColor = (r: string | null) => (r === 'PASS' ? 'success' : r === 'FAIL' ? 'error' : r === 'BLOCK' ? 'warning' : 'secondary')

const shown = computed(() => {
  const needle = q.value.trim().toLowerCase()
  return rows.value.filter((r) => (!gapFilter.value || r.gaps.includes(gapFilter.value))
    && (!needle || `${r.req.reqNo} ${r.req.title}`.toLowerCase().includes(needle)))
})
const gapCounts = computed(() => GAPS.map((g) => ({ g, n: rows.value.filter((r) => r.gaps.includes(g)).length })))
const coverage = computed(() => {
  const active = rows.value.filter((r) => r.req.status !== 'DROPPED')
  const n = active.length || 1
  return {
    screen: Math.round((active.filter((r) => r.screens.length).length / n) * 100),
    test: Math.round((active.filter((r) => r.tests.length).length / n) * 100),
    pass: Math.round((active.filter((r) => r.tests.length && r.tests.every((x) => x.lastResult === 'PASS')).length / n) * 100),
  }
})
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.deliverable.trace')" :subtitle="t('dl.trace.subtitle')">
      <ExcelExportButton v-if="projectId" dataset-id="trace-matrix" :file-name="t('nav.deliverable.trace')" :params="{ projectId }" />
    </AppPageHeader>
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <template v-else>
      <VAlert v-if="error" type="error" variant="tonal" density="compact" class="mb-3">{{ error }}</VAlert>
      <div class="d-flex flex-wrap align-center ga-2 mb-3">
        <VChip label variant="tonal" color="primary">{{ t('dl.trace.coverScreen', { n: coverage.screen }) }}</VChip>
        <VChip label variant="tonal" color="primary">{{ t('dl.trace.coverTest', { n: coverage.test }) }}</VChip>
        <VChip label variant="tonal" color="success">{{ t('dl.trace.coverPass', { n: coverage.pass }) }}</VChip>
        <VDivider vertical class="mx-1" />
        <VChip
          v-for="c in gapCounts"
          :key="c.g"
          label
          :color="gapFilter === c.g ? 'error' : undefined"
          :variant="gapFilter === c.g ? 'flat' : 'outlined'"
          :data-test="`gap-${c.g}`"
          @click="gapFilter = gapFilter === c.g ? null : c.g"
        >{{ t(`dl.trace.gap.${c.g}`) }} {{ c.n }}</VChip>
        <VSpacer />
        <VTextField v-model="q" :label="t('common.search')" prepend-inner-icon="ri-search-line" clearable hide-details class="search" />
      </div>
      <VCard border flat>
        <VProgressLinear v-if="loading" indeterminate color="primary" />
        <div class="wrap">
          <table class="matrix" data-test="trace-matrix">
            <thead>
              <tr>
                <th class="sticky">{{ t('dl.req.no') }}</th>
                <th>{{ t('dl.trace.colScreens') }}</th>
                <th>{{ t('dl.trace.colTests') }}</th>
                <th>{{ t('dl.trace.colDefects') }}</th>
                <th>{{ t('dl.trace.colCrs') }}</th>
                <th>{{ t('dl.trace.colGaps') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in shown" :key="r.req.reqId" :class="{ gap: r.gaps.length }">
                <td class="sticky">
                  <div class="font-mono text-caption">{{ r.req.reqNo }}</div>
                  <div class="text-body-2">{{ r.req.title }}</div>
                  <AppStatusChip :status="r.req.status" prefix="dl.reqStatus" size="x-small" />
                </td>
                <td :class="{ missing: r.gaps.includes('NO_SCREEN') }">
                  <div v-for="s in r.screens" :key="s.id" class="text-caption">{{ s.label }}</div>
                  <span v-if="!r.screens.length" class="text-caption">—</span>
                </td>
                <td :class="{ missing: r.gaps.includes('NO_TEST') }">
                  <div v-for="tc in r.tests" :key="tc.id" class="text-caption d-flex align-center ga-1">
                    <VIcon :icon="tc.lastResult === 'PASS' ? 'ri-checkbox-circle-fill' : tc.lastResult === 'FAIL' ? 'ri-close-circle-fill' : 'ri-checkbox-blank-circle-line'" size="12" :color="resultColor(tc.lastResult)" />
                    {{ tc.label }}
                  </div>
                  <span v-if="!r.tests.length" class="text-caption">—</span>
                </td>
                <td>
                  <div v-for="d in r.defects" :key="d.id" class="text-caption d-flex align-center ga-1">
                    <AppStatusChip :status="d.status" prefix="qa.defectStatus" size="x-small" /> {{ d.label }}
                  </div>
                </td>
                <td><div v-for="c in r.crs" :key="c.id" class="text-caption">{{ c.label }}</div></td>
                <td>
                  <VChip v-for="g in r.gaps" :key="g" size="x-small" color="error" variant="tonal" label class="mr-1 mb-1">{{ t(`dl.trace.gap.${g}`) }}</VChip>
                  <VIcon v-if="!r.gaps.length" icon="ri-check-line" color="success" :aria-label="t('dl.trace.ok')" />
                </td>
              </tr>
              <tr v-if="!shown.length && !loading"><td colspan="6" class="text-center text-muted pa-6">{{ t('common.noData') }}</td></tr>
            </tbody>
          </table>
        </div>
      </VCard>
    </template>
  </VContainer>
</template>

<style scoped>
.search {
  min-width: 200px;
  max-width: 280px;
}

.wrap {
  overflow-x: auto;
}

.matrix {
  border-collapse: collapse;
  width: 100%;
  min-width: 900px;
  font-size: 12.5px;
}

.matrix th,
.matrix td {
  border: 1px solid rgb(var(--v-theme-line));
  padding: 6px 8px;
  vertical-align: top;
  text-align: left;
}

.matrix th {
  color: rgb(var(--v-theme-muted));
  font-weight: 600;
  white-space: nowrap;
}

.matrix .sticky {
  position: sticky;
  left: 0;
  background: rgb(var(--v-theme-surface));
  min-width: 220px;
  z-index: 1;
}

.matrix tr.gap .sticky {
  box-shadow: inset 3px 0 0 rgb(var(--v-theme-error));
}

.matrix td.missing {
  background: rgba(var(--v-theme-error), 0.08);
}
</style>
