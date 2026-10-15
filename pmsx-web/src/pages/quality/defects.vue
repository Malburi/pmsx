<script setup lang="ts">
import type { EChartsCoreOption } from 'echarts/core'
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useTheme } from 'vuetify'
import { getDefectApi, getDefectsApi, getQualityReportApi, transitionDefectApi } from '@/api/qualityApi'
import AppChart from '@/components/common/AppChart.vue'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import AppStatusChip from '@/components/common/AppStatusChip.vue'
import ExcelExportButton from '@/components/common/ExcelExportButton.vue'
import DefectFormDialog from '@/components/quality/DefectFormDialog.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useElementWidth } from '@/composables/useElementWidth'
import { useAuthStore } from '@/stores/auth'
import type { ApiError } from '@/types/api'
import type { Page } from '@/types/common'
import type { Defect, DefectSeverity, DefectStatus, QualityReport } from '@/types/r2'
import { fitColumns } from '@/utils/columns'
import { formatDateTime } from '@/utils/format'
import { TEST_EDIT_ROLES } from '@/utils/permission'
import { allowedDefectTransitions } from '@/utils/quality'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// SCR-QA-005 결함 + 품질 리포트 (FR-QA-007~009)
const { t } = useI18n()
const route = useRoute()
const theme = useTheme()
const auth = useAuthStore()

const STATUSES: DefectStatus[] = ['NEW', 'ASSIGNED', 'FIXED', 'VERIFIED', 'CLOSED', 'REOPENED']
const SEVERITIES: DefectSeverity[] = ['CRITICAL', 'MAJOR', 'MINOR', 'TRIVIAL']
const page = ref<Page<Defect> | null>(null)
const report = ref<QualityReport | null>(null)
const options = ref({ page: 1, itemsPerPage: 20 })
const filter = reactive({ q: '', status: null as DefectStatus | null, severity: null as DefectSeverity | null })
const loading = ref(false)
const error = ref<string | null>(null)
const detail = ref<Defect | null>(null)
const form = reactive({ open: false, defect: null as Defect | null })
const trans = reactive({ open: false, to: 'ASSIGNED' as DefectStatus, comment: '', saving: false })
const showReport = ref(true)
let timer: ReturnType<typeof setTimeout> | null = null

// ─────────── 헬퍼 ───────────
const load = async () => {
  if (!projectId.value) return
  loading.value = true
  error.value = null
  try {
    page.value = await getDefectsApi(projectId.value, {
      q: filter.q.trim() || undefined, status: filter.status ?? undefined, severity: filter.severity ?? undefined,
      page: options.value.page - 1, size: options.value.itemsPerPage,
    })
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const loadReport = async () => {
  if (projectId.value) report.value = await getQualityReportApi(projectId.value).catch(() => null)
}
const { projectId } = useCurrentProject(() => { void load(); void loadReport() })

const open = async (id: string) => { detail.value = await getDefectApi(id).catch(() => null) }
const onOptions = (o: { page: number; itemsPerPage: number }) => { options.value = { page: o.page, itemsPerPage: o.itemsPerPage }; void load() }
const onRowClick = (_e: Event, p: { item: Defect }) => { detail.value = p.item }

const openTrans = (to: DefectStatus) => Object.assign(trans, { open: true, to, comment: '', saving: false })

const runTrans = async () => {
  if (!detail.value) return
  trans.saving = true
  try {
    detail.value = await transitionDefectApi(detail.value.defectId, trans.to, trans.comment.trim() || undefined)
    trans.open = false
    showSuccessToast('qa.defect.transitioned')
    void load()
    void loadReport()
  } catch (e) {
    showErrorToast((e as ApiError).code === 'CONFLICT' ? 'qa.defect.notAllowed' : 'common.actionFailed')
  } finally {
    trans.saving = false
  }
}

const onSaved = (d: Defect) => {
  detail.value = d
  void load()
  void loadReport()
}

const color = (name: string) => theme.current.value.colors[name] as string

// ─────────── computed ───────────
const canEdit = computed(() => auth.hasAnyRole(TEST_EDIT_ROLES))
const nexts = computed(() => (detail.value ? allowedDefectTransitions(detail.value.status) : []))
const statusItems = computed(() => STATUSES.map((s) => ({ title: t(`qa.defectStatus.${s}`), value: s })))
const severityItems = computed(() => SEVERITIES.map((s) => ({ title: t(`qa.severity.${s}`), value: s })))
// 2단 화면에서 목록 자리가 좁으면 우선순위 낮은 열을 숨긴다 (마지막 열 잘림 방지)
const listCard = ref<HTMLElement | { $el: HTMLElement } | null>(null)
const listWidth = useElementWidth(listCard, 700)
const allHeaders = computed(() => [
  { title: t('qa.col.no'), key: 'defectNo', width: 90 },
  { title: t('qa.col.title'), key: 'title', minWidth: 200 },
  { title: t('qa.col.severity'), key: 'severity', width: 80 },
  { title: t('qa.col.status'), key: 'status', width: 90 },
  { title: t('qa.col.assignee'), key: 'assignee', width: 90, hideBelow: 620 },
  { title: t('qa.col.created'), key: 'createdAt', width: 130, hideBelow: 700 },
])
const headers = computed(() => fitColumns(allHeaders.value, listWidth.value))
const trendOption = computed<EChartsCoreOption>(() => {
  const d = report.value?.defectTrend ?? []
  return {
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0, icon: 'circle' },
    grid: { left: 8, right: 12, top: 12, bottom: 36, containLabel: true },
    xAxis: { type: 'category', data: d.map((x) => x.date.slice(5)), axisLine: { lineStyle: { color: color('line') } } },
    yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: color('line') } } },
    series: [
      { name: t('qa.report.opened'), type: 'bar', stack: 'flow', barMaxWidth: 10, itemStyle: { color: color('error') }, data: d.map((x) => x.opened) },
      { name: t('qa.report.closed'), type: 'bar', stack: 'flow2', barMaxWidth: 10, itemStyle: { color: color('success') }, data: d.map((x) => x.closed) },
      { name: t('qa.report.open'), type: 'line', smooth: true, showSymbol: false, lineStyle: { color: color('primary'), width: 2 }, itemStyle: { color: color('primary') }, data: d.map((x) => x.open) },
    ],
  }
})
const severityOption = computed<EChartsCoreOption>(() => ({
  tooltip: { trigger: 'item', formatter: '{b}: {c}' },
  legend: { bottom: 0, icon: 'circle' },
  series: [{
    type: 'pie', radius: ['45%', '70%'], center: ['50%', '42%'], label: { show: false },
    data: SEVERITIES.map((s) => ({ name: t(`qa.severity.${s}`), value: report.value?.bySeverity[s] ?? 0, itemStyle: { color: color(s === 'CRITICAL' ? 'error' : s === 'MAJOR' ? 'warning' : s === 'MINOR' ? 'info' : 'secondary') } })),
  }],
}))
const testOption = computed<EChartsCoreOption>(() => {
  const tp = report.value?.testProgress ?? []
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { bottom: 0, icon: 'circle' },
    grid: { left: 8, right: 12, top: 12, bottom: 36, containLabel: true },
    xAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: color('line') } } },
    yAxis: { type: 'category', data: tp.map((x) => x.name) },
    series: (['pass', 'fail', 'block', 'notRun'] as const).map((k) => ({
      name: t(`qa.report.${k}`), type: 'bar', stack: 'r', barMaxWidth: 16,
      itemStyle: { color: color(k === 'pass' ? 'success' : k === 'fail' ? 'error' : k === 'block' ? 'warning' : 'line') },
      data: tp.map((x) => x[k]),
    })),
  }
})

// ─────────── watch ───────────
watch(() => [filter.status, filter.severity], () => { options.value.page = 1; void load() })
watch(() => filter.q, () => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => { options.value.page = 1; void load() }, 300)
})
watch(() => route.query.id, (id) => { if (typeof id === 'string') void open(id) }, { immediate: true })
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.quality.defect')" :subtitle="t('qa.defect.subtitle')">
      <template v-if="projectId">
        <VBtn size="small" variant="text" :prepend-icon="showReport ? 'ri-arrow-up-s-line' : 'ri-bar-chart-2-line'" @click="showReport = !showReport">{{ t('qa.report.title') }}</VBtn>
        <ExcelExportButton dataset-id="defects" :file-name="t('nav.quality.defect')" :params="{ projectId }" />
        <VBtn v-if="canEdit" color="primary" size="small" prepend-icon="ri-add-line" data-test="defect-add" @click="Object.assign(form, { open: true, defect: null })">{{ t('qa.defect.add') }}</VBtn>
      </template>
    </AppPageHeader>
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <template v-else>
      <VRow v-if="report && showReport" class="mb-1" data-test="quality-report">
        <VCol cols="12" md="5">
          <VCard border flat :title="t('qa.report.trend')"><AppChart :option="trendOption" :height="220" :label="t('qa.report.trend')" class="px-2" /></VCard>
        </VCol>
        <VCol cols="12" sm="6" md="3">
          <VCard border flat :title="t('qa.report.severity')">
            <AppChart :option="severityOption" :height="190" :label="t('qa.report.severity')" />
            <div class="text-caption text-center pb-2">{{ t('qa.report.reopen', { n: Math.round(report.reopenRate * 100) }) }}</div>
          </VCard>
        </VCol>
        <VCol cols="12" sm="6" md="4">
          <VCard border flat :title="t('qa.report.tests')"><AppChart :option="testOption" :height="220" :label="t('qa.report.tests')" class="px-2" /></VCard>
        </VCol>
      </VRow>
      <VRow>
        <VCol cols="12" lg="7">
          <VCard ref="listCard" border flat>
            <VCardText class="pb-0">
              <VRow dense>
                <VCol cols="12" sm="5"><VTextField v-model="filter.q" :label="t('common.search')" prepend-inner-icon="ri-search-line" clearable hide-details /></VCol>
                <VCol cols="6" sm="4"><VSelect v-model="filter.status" :items="statusItems" :label="t('qa.col.status')" clearable hide-details /></VCol>
                <VCol cols="6" sm="3"><VSelect v-model="filter.severity" :items="severityItems" :label="t('qa.col.severity')" clearable hide-details /></VCol>
              </VRow>
            </VCardText>
            <VAlert v-if="error" type="error" variant="tonal" density="compact" class="ma-3">{{ error }}</VAlert>
            <VDataTableServer
              :headers="headers"
              :items="page?.content ?? []"
              :items-length="page?.totalElements ?? 0"
              :loading="loading"
              :page="options.page"
              :items-per-page="options.itemsPerPage"
              item-value="defectId"
              density="compact"
              hover
              :row-props="({ item }) => ({ class: item.defectId === detail?.defectId ? 'row-selected' : '', tabindex: 0 })"
              data-test="defect-table"
              @update:options="onOptions"
              @click:row="onRowClick"
            >
              <template #item.defectNo="{ item }"><span class="font-mono">{{ item.defectNo }}</span></template>
              <template #item.title="{ item }">
                {{ item.title }}
                <VIcon v-if="item.elementId" icon="ri-cursor-line" size="13" class="ml-1" :title="t('qa.fromHotspot')" />
              </template>
              <template #item.severity="{ item }"><AppStatusChip :status="item.severity" prefix="qa.severity" size="x-small" /></template>
              <template #item.status="{ item }"><AppStatusChip :status="item.status" prefix="qa.defectStatus" size="x-small" /></template>
              <template #item.assignee="{ item }">{{ item.assignee?.name ?? '-' }}</template>
              <template #item.createdAt="{ item }"><span class="text-caption">{{ formatDateTime(item.createdAt) }}</span></template>
            </VDataTableServer>
          </VCard>
        </VCol>
        <VCol cols="12" lg="5">
          <VCard v-if="detail" border flat data-test="defect-detail">
            <VCardTitle class="d-flex align-center ga-2">
              <span class="font-mono text-body-2">{{ detail.defectNo }}</span>
              <AppStatusChip :status="detail.status" prefix="qa.defectStatus" size="x-small" />
              <AppStatusChip :status="detail.severity" prefix="qa.severity" size="x-small" />
              <VSpacer />
              <VBtn v-if="canEdit" icon variant="text" size="small" :aria-label="t('common.edit')" @click="Object.assign(form, { open: true, defect: detail })"><VIcon icon="ri-edit-line" /></VBtn>
            </VCardTitle>
            <VCardText>
              <div class="text-subtitle-1 font-weight-medium mb-1">{{ detail.title }}</div>
              <div class="text-caption text-muted mb-3">
                {{ t('qa.col.assignee') }} {{ detail.assignee?.name ?? '-' }} · {{ t('qa.defect.reporter') }} {{ detail.reporter.name }} · {{ formatDateTime(detail.createdAt) }}
              </div>
              <p class="text-body-2 pre mb-3">{{ detail.description }}</p>
              <div v-if="detail.screenId || detail.caseId" class="d-flex flex-wrap ga-2 mb-3">
                <VChip v-if="detail.screenId" size="small" prepend-icon="ri-layout-masonry-line" variant="outlined">{{ detail.screenId }}</VChip>
                <VChip v-if="detail.elementId" size="small" prepend-icon="ri-cursor-line" variant="outlined">{{ t('qa.fromHotspot') }}</VChip>
                <VChip v-if="detail.caseId" size="small" prepend-icon="ri-test-tube-line" variant="outlined">{{ detail.caseId }}</VChip>
              </div>
              <div class="text-subtitle-2 mb-2">{{ t('qa.defect.transitions') }}</div>
              <div class="flow mb-2" :aria-label="t('qa.defect.flow')">
                <span v-for="s in ['NEW', 'ASSIGNED', 'FIXED', 'VERIFIED', 'CLOSED']" :key="s" :class="['step', { on: detail.status === s }]">{{ t(`qa.defectStatus.${s}`) }}</span>
              </div>
              <div v-if="canEdit" class="d-flex flex-wrap ga-2">
                <VBtn
                  v-for="s in nexts"
                  :key="s"
                  size="small"
                  variant="tonal"
                  :color="s === 'REOPENED' ? 'error' : s === 'CLOSED' ? 'success' : 'primary'"
                  :data-test="`defect-to-${s}`"
                  @click="openTrans(s)"
                >{{ t(`qa.defectAction.${s}`) }}</VBtn>
                <span v-if="!nexts.length" class="text-body-2 text-muted">{{ t('qa.defect.final') }}</span>
              </div>
            </VCardText>
          </VCard>
          <VAlert v-else type="info" variant="tonal">{{ t('qa.defect.pick') }}</VAlert>
        </VCol>
      </VRow>
    </template>
    <DefectFormDialog v-model="form.open" :defect="form.defect" @saved="onSaved" />
    <VDialog v-model="trans.open" max-width="420">
      <VCard :title="t(`qa.defectAction.${trans.to}`)">
        <VCardText><VTextarea v-model="trans.comment" :label="t('dl.comment')" rows="3" /></VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="trans.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :loading="trans.saving" data-test="defect-trans-run" @click="runTrans">{{ t(`qa.defectAction.${trans.to}`) }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>
  </VContainer>
</template>

<style scoped>
.pre {
  white-space: pre-wrap;
}

.flow {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.step {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 10px;
  border: 1px solid rgb(var(--v-theme-line));
  color: rgb(var(--v-theme-muted));
}

.step.on {
  background: rgb(var(--v-theme-primary));
  border-color: rgb(var(--v-theme-primary));
  color: rgb(var(--v-theme-on-primary));
  font-weight: 600;
}

:deep(.row-selected) {
  background: rgba(var(--v-theme-primary), 0.08);
}
</style>
