<script setup lang="ts">
import type { EChartsCoreOption } from 'echarts/core'
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useTheme } from 'vuetify'
import { assignAnalysisApi, getAnalysisProgressApi, getAnalysisScreensApi } from '@/api/analysisApi'
import AsisStatusChip from '@/components/asis/AsisStatusChip.vue'
import AsisSystemSelect from '@/components/asis/AsisSystemSelect.vue'
import AppChart from '@/components/common/AppChart.vue'
import UserLookupField from '@/components/common/UserLookupField.vue'
import { useApi } from '@/composables/useApi'
import { useAuthStore } from '@/stores/auth'
import type { AnalysisProgress, AnalysisRow, AnalysisStatus, ApiError } from '@/types/api'
import type { Page } from '@/types/common'
import { focusableRow } from '@/utils/a11y'
import { STATUS_ORDER, statusColor } from '@/utils/asis'
import { formatDateTime } from '@/utils/format'
import { ASSIGN_ROLES } from '@/utils/permission'
import { showSuccessToast } from '@/utils/toast'

// SCR-AS-002 분석 진척 현황 (FR-AS-022·023)
const { t } = useI18n()
const router = useRouter()
const theme = useTheme()
const auth = useAuthStore()

const systemId = ref<string | null>(null)
const filter = reactive({ status: null as AnalysisStatus | null, ownerId: null as string | null, menuId: null as string | null, q: '' })
const options = ref({ page: 1, itemsPerPage: 20 })
const rows = ref<Page<AnalysisRow> | null>(null)
const rowsLoading = ref(false)
const rowsError = ref<string | null>(null)
const selected = ref<string[]>([])
const assign = reactive({ open: false, ownerId: null as string | null, reviewerId: null as string | null, saving: false, error: null as string | null })
const { data: progress, loading: progressLoading, error: progressError, execute: loadProgress } = useApi<AnalysisProgress>(
  (id: string) => getAnalysisProgressApi(id),
)
let qTimer: ReturnType<typeof setTimeout> | null = null

// ─────────── 헬퍼 ───────────
const color = (name: string) => theme.current.value.colors[name] as string

const loadRows = async () => {
  if (!systemId.value) return
  rowsLoading.value = true
  rowsError.value = null
  try {
    rows.value = await getAnalysisScreensApi(systemId.value, {
      status: filter.status ?? undefined,
      ownerId: filter.ownerId ?? undefined,
      menuId: filter.menuId ?? undefined,
      q: filter.q.trim() || undefined,
      page: options.value.page - 1,
      size: options.value.itemsPerPage,
    })
  } catch (e) {
    rowsError.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    rowsLoading.value = false
  }
}

const reloadAll = () => {
  if (!systemId.value) return
  selected.value = []
  void loadProgress(systemId.value)
  void loadRows()
}

const onOptions = (o: { page: number; itemsPerPage: number }) => {
  options.value = { page: o.page, itemsPerPage: o.itemsPerPage }
  void loadRows()
}

const openViewer = (row: AnalysisRow) => {
  void router.push(`/asis/viewer/${systemId.value}/${row.screenId}`)
}

const openAssign = () => {
  Object.assign(assign, { open: true, ownerId: null, reviewerId: null, saving: false, error: null })
}

const saveAssign = async () => {
  if (!systemId.value || !selected.value.length) return
  assign.saving = true
  assign.error = null
  try {
    await assignAnalysisApi(systemId.value, { screenIds: selected.value, ownerId: assign.ownerId, reviewerId: assign.reviewerId })
    assign.open = false
    showSuccessToast('asis.progress.assigned')
    reloadAll()
  } catch (e) {
    const err = e as ApiError
    assign.error = err.code === 'FORBIDDEN' ? t('common.forbiddenAction') : (err.message ?? t('common.saveFailed'))
  } finally {
    assign.saving = false
  }
}

const onRowClick = (_e: Event, payload: { item: AnalysisRow }) => openViewer(payload.item)

// 차트에서 상태·메뉴를 누르면 아래 그리드를 그 조건으로 거른다
const onStatusChart = (p: { dataIndex: number }) => {
  filter.status = STATUS_ORDER[p.dataIndex] ?? null
}

const onMenuChart = (p: { dataIndex: number }) => {
  filter.menuId = progress.value?.byMenu[p.dataIndex]?.menuId ?? null
}

// ─────────── computed ───────────
const canAssign = computed(() => auth.hasAnyRole(ASSIGN_ROLES))
const doneRate = computed(() => {
  const p = progress.value
  return p && p.total ? Math.round((p.byStatus.DONE / p.total) * 100) : 0
})
const statusItems = computed(() => STATUS_ORDER.map((s) => ({ title: t(`asis.status.${s}`), value: s })))
const menuItems = computed(() => (progress.value?.byMenu ?? []).map((m) => ({ title: m.name, value: m.menuId })))
const headers = computed(() => [
  { title: t('asis.progress.colScreen'), key: 'name', sortable: false, minWidth: 180 },
  { title: t('asis.progress.colMenu'), key: 'menuPath', sortable: false, minWidth: 180 },
  { title: t('asis.progress.colStatus'), key: 'status', sortable: false, width: 110 },
  { title: t('asis.progress.colOwner'), key: 'owner', sortable: false, width: 110 },
  { title: t('asis.progress.colReviewer'), key: 'reviewer', sortable: false, width: 110 },
  { title: t('asis.progress.colElements'), key: 'elementCount', sortable: false, align: 'end' as const, width: 70 },
  { title: t('asis.progress.colUnmatched'), key: 'unmatchedCount', sortable: false, align: 'end' as const, width: 80 },
  { title: t('asis.progress.colUpdated'), key: 'updatedAt', sortable: false, width: 140 },
])

const statusOption = computed<EChartsCoreOption>(() => {
  const p = progress.value
  return {
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 0, icon: 'circle' },
    series: [{
      type: 'pie',
      radius: ['48%', '72%'],
      center: ['50%', '44%'],
      label: { show: false },
      data: STATUS_ORDER.map((s) => ({ name: t(`asis.status.${s}`), value: p?.byStatus[s] ?? 0, itemStyle: { color: color(statusColor(s)) } })),
    }],
  }
})

const ownerOption = computed<EChartsCoreOption>(() => {
  const list = progress.value?.byOwner ?? []
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { bottom: 0, icon: 'circle' },
    grid: { left: 8, right: 16, top: 8, bottom: 36, containLabel: true },
    xAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: color('line') } } },
    yAxis: { type: 'category', data: list.map((o) => o.owner?.name ?? t('asis.progress.unassigned')), axisLine: { lineStyle: { color: color('line') } } },
    series: STATUS_ORDER.map((s) => ({
      name: t(`asis.status.${s}`),
      type: 'bar',
      stack: 'total',
      barMaxWidth: 18,
      itemStyle: { color: color(statusColor(s)) },
      data: list.map((o) => o.byStatus[s] ?? 0),
    })),
  }
})

const menuOption = computed<EChartsCoreOption>(() => {
  const list = progress.value?.byMenu ?? []
  return {
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { bottom: 0, icon: 'circle' },
    grid: { left: 8, right: 16, top: 8, bottom: 36, containLabel: true },
    xAxis: { type: 'category', data: list.map((m) => m.name), axisLabel: { interval: 0, overflow: 'truncate', width: 72 }, axisLine: { lineStyle: { color: color('line') } } },
    yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: color('line') } } },
    series: [
      { name: t('asis.progress.total'), type: 'bar', barGap: '-100%', barMaxWidth: 26, itemStyle: { color: color('line') }, data: list.map((m) => m.total) },
      { name: t('asis.status.DONE'), type: 'bar', barMaxWidth: 26, itemStyle: { color: color('status-done') }, data: list.map((m) => m.done) },
    ],
  }
})

// ─────────── watch ───────────
watch(systemId, () => {
  Object.assign(filter, { status: null, ownerId: null, menuId: null, q: '' })
  options.value.page = 1
  reloadAll()
})

watch(() => [filter.status, filter.ownerId, filter.menuId], () => {
  options.value.page = 1
  void loadRows()
})

watch(() => filter.q, () => {
  if (qTimer) clearTimeout(qTimer)
  qTimer = setTimeout(() => { options.value.page = 1; void loadRows() }, 300)
})
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <div class="d-flex flex-wrap align-center ga-3 mb-4">
      <div class="flex-grow-1">
        <h1 class="pmsx-page-title">{{ t('nav.asis.progress') }}</h1>
        <div class="text-body-2 text-muted">{{ t('asis.progress.subtitle') }}</div>
      </div>
      <AsisSystemSelect v-model="systemId" />
    </div>

    <VAlert v-if="!systemId" type="info" variant="tonal">{{ t('asis.selectSystemFirst') }}</VAlert>
    <template v-else>
      <VProgressLinear v-if="progressLoading" indeterminate color="primary" class="mb-2" />
      <VAlert v-if="progressError" type="error" variant="tonal" density="compact" class="mb-3">
        {{ progressError.message }}
        <VBtn variant="text" size="small" @click="reloadAll">{{ t('common.retry') }}</VBtn>
      </VAlert>

      <div v-if="progress" class="kpis mb-4" data-test="progress-kpis">
        <VCard border flat class="kpi">
          <div class="kpi-label">{{ t('asis.progress.total') }}</div>
          <div class="kpi-value">{{ progress.total }}</div>
        </VCard>
        <VCard border flat class="kpi">
          <div class="kpi-label">{{ t('asis.progress.doneRate') }}</div>
          <div class="kpi-value">{{ doneRate }}<small>%</small></div>
          <VProgressLinear :model-value="doneRate" color="status-done" height="4" rounded class="mt-2" />
        </VCard>
        <VCard border flat class="kpi">
          <div class="kpi-label">{{ t('asis.progress.review') }}</div>
          <div class="kpi-value text-status-review">{{ progress.byStatus.REVIEW }}</div>
        </VCard>
        <VCard border flat class="kpi">
          <div class="kpi-label">{{ t('asis.progress.unmatched') }}</div>
          <div class="kpi-value" :class="{ 'text-warning': progress.unmatchedElements > 0 }">{{ progress.unmatchedElements }}</div>
        </VCard>
        <VCard border flat class="kpi">
          <div class="kpi-label">{{ t('asis.progress.unconfirmed') }}</div>
          <div class="kpi-value" :class="{ 'text-warning': progress.unconfirmedEdges > 0 }">{{ progress.unconfirmedEdges }}</div>
          <div class="kpi-hint">{{ t('asis.progress.unconfirmedHint') }}</div>
        </VCard>
      </div>

      <VRow v-if="progress" class="mb-2">
        <VCol cols="12" md="4">
          <VCard border flat :title="t('asis.progress.byStatus')">
            <AppChart :option="statusOption" :label="t('asis.progress.byStatus')" :height="240" @select="onStatusChart" />
          </VCard>
        </VCol>
        <VCol cols="12" md="4">
          <VCard border flat :title="t('asis.progress.byOwner')">
            <AppChart :option="ownerOption" :label="t('asis.progress.byOwner')" :height="240" />
          </VCard>
        </VCol>
        <VCol cols="12" md="4">
          <VCard border flat :title="t('asis.progress.byMenu')">
            <AppChart :option="menuOption" :label="t('asis.progress.byMenu')" :height="240" @select="onMenuChart" />
          </VCard>
        </VCol>
      </VRow>

      <VCard border flat>
        <VCardTitle class="d-flex flex-wrap align-center ga-2">
          {{ t('asis.progress.screens') }}
          <VSpacer />
          <span v-if="selected.length" class="text-body-2 text-muted">{{ t('common.selected', { n: selected.length }) }}</span>
          <VBtn
            v-if="canAssign"
            size="small"
            color="primary"
            prepend-icon="ri-user-shared-line"
            :disabled="!selected.length"
            data-test="progress-assign"
            @click="openAssign"
          >{{ t('asis.progress.assign') }}</VBtn>
        </VCardTitle>
        <VCardText>
          <VRow dense>
            <VCol cols="12" sm="6" md="3">
              <VSelect v-model="filter.status" :items="statusItems" :label="t('asis.progress.filterStatus')" clearable hide-details />
            </VCol>
            <VCol cols="12" sm="6" md="3">
              <UserLookupField v-model="filter.ownerId" :label="t('asis.progress.filterOwner')" hide-details />
            </VCol>
            <VCol cols="12" sm="6" md="3">
              <VSelect v-model="filter.menuId" :items="menuItems" :label="t('asis.progress.filterMenu')" clearable hide-details />
            </VCol>
            <VCol cols="12" sm="6" md="3">
              <VTextField v-model="filter.q" :label="t('asis.progress.filterQ')" prepend-inner-icon="ri-search-line" clearable hide-details />
            </VCol>
          </VRow>
        </VCardText>
        <VAlert v-if="rowsError" type="error" variant="tonal" density="compact" class="mx-4 mb-2">{{ rowsError }}</VAlert>
        <VDataTableServer
          v-model="selected"
          :headers="headers"
          :items="rows?.content ?? []"
          :items-length="rows?.totalElements ?? 0"
          :loading="rowsLoading"
          :page="options.page"
          :items-per-page="options.itemsPerPage"
          :items-per-page-options="[20, 50, 100]"
          item-value="screenId"
          :show-select="canAssign"
          density="compact"
          hover
          data-test="progress-grid"
          :row-props="focusableRow"
          @update:options="onOptions"
          @click:row="onRowClick"
        >
          <template #item.name="{ item }">
            <span class="font-weight-medium">{{ item.name }}</span>
            <span class="text-caption text-muted font-mono ml-1">{{ item.screenCode }}</span>
          </template>
          <template #item.menuPath="{ item }"><span class="text-caption">{{ item.menuPath.join(' › ') }}</span></template>
          <template #item.status="{ item }"><AsisStatusChip :status="item.status" /></template>
          <template #item.owner="{ item }">{{ item.owner?.name ?? '-' }}</template>
          <template #item.reviewer="{ item }">{{ item.reviewer?.name ?? '-' }}</template>
          <template #item.unmatchedCount="{ item }">
            <span :class="{ 'text-warning font-weight-bold': item.unmatchedCount > 0 }">{{ item.unmatchedCount }}</span>
          </template>
          <template #item.updatedAt="{ item }"><span class="text-caption">{{ formatDateTime(item.updatedAt) }}</span></template>
        </VDataTableServer>
      </VCard>
    </template>

    <VDialog v-model="assign.open" max-width="460">
      <VCard :title="t('asis.progress.assignTitle', { n: selected.length })">
        <VCardText>
          <UserLookupField
            v-model="assign.ownerId"
            :label="t('asis.progress.owner')"
            :hint="t('asis.progress.clearOwnerHint')"
            class="mb-3"
            data-test="assign-owner"
          />
          <UserLookupField v-model="assign.reviewerId" :label="t('asis.progress.reviewer')" :hint="t('asis.progress.clearOwnerHint')" />
          <VAlert v-if="assign.error" type="error" variant="tonal" density="compact" class="mt-2" role="alert">{{ assign.error }}</VAlert>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="assign.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :loading="assign.saving" data-test="assign-save" @click="saveAssign">{{ t('common.apply') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>
  </VContainer>
</template>

<style scoped>
.kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
}

.kpi {
  padding: 12px 16px;
}

.kpi-label {
  font-size: 12px;
  color: rgb(var(--v-theme-muted));
}

.kpi-value {
  font-size: 26px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  line-height: 1.3;
}

.kpi-hint {
  font-size: 11px;
  color: rgb(var(--v-theme-muted));
  line-height: 1.4;
}

.kpi-value small {
  font-size: 14px;
  font-weight: 600;
  margin-left: 2px;
}
</style>
