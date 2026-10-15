<route lang="yaml">
meta:
  roles: [SYS_ADMIN, PMO, PM, PL]
</route>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { getHeatmapApi, getMmPlanApi, saveMmPlanApi } from '@/api/resourceApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import ExcelExportButton from '@/components/common/ExcelExportButton.vue'
import ExcelImportDialog from '@/components/common/ExcelImportDialog.vue'
import AllocHeatmap from '@/components/resource/AllocHeatmap.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useAuthStore } from '@/stores/auth'
import type { ApiError } from '@/types/api'
import type { HeatmapRow, MmPlanRow } from '@/types/r2'
import { addDays, isoWeek, monthEnd, monthRange, today, weekStart } from '@/utils/date'
import { RESOURCE_EDIT_ROLES } from '@/utils/permission'
import { sumMm } from '@/utils/quality'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// SCR-RS-002 투입 계획(월별 MM) · 가용량 히트맵 (FR-RS-003·004)
const { t } = useI18n()
const auth = useAuthStore()

const tab = ref<'plan' | 'heatmap'>('plan')
const rows = ref<MmPlanRow[]>([])
const savedJson = ref('[]')
const heat = ref<HeatmapRow[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref<string | null>(null)
const excelOpen = ref(false)
const heatOpt = reactive({ unit: 'month' as 'month' | 'week', allProjects: true })

// ─────────── 헬퍼 ───────────
const load = async (projectId: string) => {
  loading.value = true
  error.value = null
  try {
    rows.value = await getMmPlanApi(projectId, range.value.from, range.value.to)
    savedJson.value = JSON.stringify(rows.value)
    await loadHeat()
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}

const loadHeat = async () => {
  if (!projectId.value) return
  // 월 단위는 프로젝트 기간 전체, 주 단위는 이번 주부터 12주
  const from = heatOpt.unit === 'month' ? `${range.value.from}-01` : weekStart(today())
  const to = heatOpt.unit === 'month' ? monthEnd(range.value.to) : addDays(weekStart(today()), 7 * 12 - 1)
  heat.value = await getHeatmapApi({ from, to, unit: heatOpt.unit, projectId: heatOpt.allProjects ? undefined : projectId.value }).catch(() => [])
}
const { projectId, project } = useCurrentProject(load)

const onCell = (row: MmPlanRow, month: string, e: Event) => {
  const v = Math.round(Math.max(0, Math.min(1.5, Number((e.target as HTMLInputElement).value) || 0)) * 100) / 100
  row.months = { ...row.months, [month]: v }
}

const save = async () => {
  if (!projectId.value) return
  saving.value = true
  try {
    rows.value = await saveMmPlanApi(projectId.value, rows.value, range.value.from, range.value.to)
    savedJson.value = JSON.stringify(rows.value)
    showSuccessToast('common.saved')
    await loadHeat()
  } catch (e) {
    const err = e as ApiError
    if (err.code === 'VALIDATION') showErrorToast('rs.plan.invalid')
    else showErrorToast('common.saveFailed')
  } finally {
    saving.value = false
  }
}

const reload = () => { if (projectId.value) void load(projectId.value) }
const monthLabel = (m: string) => `${m.slice(2, 4)}.${m.slice(5, 7)}`
const weekLabel = (w: string) => `${isoWeek(w).slice(5)}\n${w.slice(5)}`

// ─────────── computed ───────────
const canEdit = computed(() => auth.hasAnyRole(RESOURCE_EDIT_ROLES))
const dirty = computed(() => JSON.stringify(rows.value) !== savedJson.value)
const range = computed(() => ({
  from: (project.value?.startDate ?? today()).slice(0, 7),
  to: (project.value?.endDate ?? today()).slice(0, 7),
}))
const months = computed(() => monthRange(range.value.from, range.value.to))
const colTotal = (m: string) => Math.round(rows.value.reduce((s, r) => s + (r.months[m] ?? 0), 0) * 100) / 100
const grandTotal = computed(() => Math.round(rows.value.reduce((s, r) => s + sumMm(r.months), 0) * 100) / 100)
const periods = computed(() => (heat.value[0] ? Object.keys(heat.value[0].cells).sort() : []))
const currentMonth = today().slice(0, 7)

// ─────────── watch ───────────
watch(() => [heatOpt.unit, heatOpt.allProjects], () => { void loadHeat() })
// 새로고침 직후에는 프로젝트 기간을 모른 채 먼저 읽으므로, 기간이 정해지면 다시 읽는다
watch(() => `${range.value.from}~${range.value.to}`, () => { if (projectId.value) void load(projectId.value) })
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.resource.plan')" :subtitle="t('rs.plan.subtitle')">
      <template v-if="projectId">
        <ExcelExportButton dataset-id="mm-plan" :file-name="t('rs.plan.fileName')" :params="{ projectId }" />
        <VBtn v-if="canEdit" size="small" variant="tonal" prepend-icon="ri-file-excel-2-line" @click="excelOpen = true">{{ t('common.excel.import') }}</VBtn>
      </template>
    </AppPageHeader>
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <VCard v-else border flat>
      <VTabs v-model="tab" density="compact" color="primary" class="border-b">
        <VTab value="plan">{{ t('rs.plan.tabPlan') }}</VTab>
        <VTab value="heatmap" data-test="plan-heatmap-tab">{{ t('rs.plan.tabHeatmap') }}</VTab>
      </VTabs>
      <VAlert v-if="error" type="error" variant="tonal" density="compact" class="ma-3">
        {{ error }}<VBtn variant="text" size="small" @click="reload">{{ t('common.retry') }}</VBtn>
      </VAlert>
      <VProgressLinear v-if="loading" indeterminate color="primary" />

      <div v-show="tab === 'plan'">
        <div class="d-flex align-center px-4 py-2 ga-2">
          <span class="text-body-2">{{ t('rs.plan.total', { mm: grandTotal }) }}</span>
          <VSpacer />
          <template v-if="canEdit">
            <span v-if="dirty" class="text-caption text-warning">{{ t('pj.wbs.unsaved') }}</span>
            <VBtn size="small" variant="text" :disabled="!dirty" @click="rows = JSON.parse(savedJson)">{{ t('common.reset') }}</VBtn>
            <VBtn size="small" color="primary" :disabled="!dirty" :loading="saving" data-test="plan-save" @click="save">{{ t('common.save') }}</VBtn>
          </template>
        </div>
        <div class="grid-wrap">
          <table class="mm-grid" data-test="mm-grid">
            <thead>
              <tr>
                <th class="sticky">{{ t('rs.pool.colName') }}</th>
                <th>{{ t('rs.pool.colGrade') }}</th>
                <th>{{ t('rs.plan.role') }}</th>
                <th v-for="m in months" :key="m" :class="{ now: m === currentMonth }">{{ monthLabel(m) }}</th>
                <th>{{ t('rs.plan.sum') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in rows" :key="r.userId">
                <td class="sticky">{{ r.name }}</td>
                <td>{{ r.grade ?? '-' }}</td>
                <td>{{ r.roleId }}</td>
                <td v-for="m in months" :key="m" :class="{ now: m === currentMonth }">
                  <input
                    v-if="canEdit"
                    type="number"
                    step="0.1"
                    min="0"
                    max="1.5"
                    class="mm-input"
                    :value="r.months[m] ?? 0"
                    :aria-label="`${r.name} ${m}`"
                    @change="onCell(r, m, $event)"
                  >
                  <span v-else>{{ r.months[m] || '' }}</span>
                </td>
                <td class="font-weight-bold">{{ sumMm(r.months) }}</td>
              </tr>
            </tbody>
            <tfoot>
              <tr>
                <td class="sticky font-weight-bold">{{ t('rs.plan.sum') }}</td>
                <td /><td />
                <td v-for="m in months" :key="m" class="font-weight-bold" :class="{ now: m === currentMonth }">{{ colTotal(m) }}</td>
                <td class="font-weight-bold">{{ grandTotal }}</td>
              </tr>
            </tfoot>
          </table>
        </div>
        <div class="text-caption text-muted px-4 py-2">{{ t('rs.plan.hint') }}</div>
      </div>

      <div v-show="tab === 'heatmap'" class="pa-4">
        <div class="d-flex flex-wrap align-center ga-3 mb-3">
          <VBtnToggle v-model="heatOpt.unit" density="compact" variant="outlined" mandatory divided>
            <VBtn value="month" size="small">{{ t('rs.heatmap.month') }}</VBtn>
            <VBtn value="week" size="small">{{ t('rs.heatmap.week') }}</VBtn>
          </VBtnToggle>
          <VCheckbox v-model="heatOpt.allProjects" :label="t('rs.heatmap.allProjects')" density="compact" hide-details />
        </div>
        <AllocHeatmap :rows="heat" :periods="periods" :period-label="heatOpt.unit === 'month' ? monthLabel : weekLabel" />
      </div>
    </VCard>
    <ExcelImportDialog v-if="projectId" v-model="excelOpen" dataset-id="mm-plan" :params="{ projectId }" :title="t('rs.plan.import')" @committed="reload" />
  </VContainer>
</template>

<style scoped>
.grid-wrap {
  overflow-x: auto;
}

.mm-grid {
  border-collapse: collapse;
  font-size: 12.5px;
  min-width: 100%;
}

.mm-grid th,
.mm-grid td {
  border: 1px solid rgb(var(--v-theme-line));
  padding: 4px 6px;
  text-align: center;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.mm-grid th {
  color: rgb(var(--v-theme-muted));
  font-weight: 600;
}

.mm-grid .now {
  background: rgba(var(--v-theme-primary), 0.06);
}

.mm-grid tfoot td {
  background: rgba(var(--v-theme-on-surface), 0.04);
}

.sticky {
  position: sticky;
  left: 0;
  background: rgb(var(--v-theme-surface));
  text-align: left !important;
  z-index: 1;
}

.mm-input {
  width: 52px;
  border: 1px solid transparent;
  border-radius: 4px;
  text-align: right;
  font: inherit;
  color: inherit;
  background: transparent;
  padding: 2px 4px;
}

.mm-input:hover {
  border-color: rgb(var(--v-theme-line));
}

.mm-input:focus {
  outline: none;
  border-color: rgb(var(--v-theme-primary));
}
</style>
