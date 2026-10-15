<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { generateMonthlyReportApi, getMonthlyReportApi, getMonthlyReportsApi, updateMonthlyReportApi } from '@/api/smApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import ExcelExportButton from '@/components/common/ExcelExportButton.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useAuthStore } from '@/stores/auth'
import type { ApiError } from '@/types/api'
import type { MonthlyReport, MonthlyReportSummary } from '@/types/r3'
import { today } from '@/utils/date'
import { formatDateTime, formatPercent } from '@/utils/format'
import { MONTHLY_EDIT_ROLES } from '@/utils/permission'
import { shiftMonth } from '@/utils/sm'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// SCR-SM-009 월간 운영보고 (FR-SM-012) — SR·SLA·장애·변경·공수 자동 집계 + 총평/계획 편집
const { t } = useI18n()
const auth = useAuthStore()

const list = ref<MonthlyReportSummary[]>([])
const report = ref<MonthlyReport | null>(null)
const savedJson = ref('')
const loading = ref(false)
const error = ref<string | null>(null)
const gen = reactive({ open: false, month: shiftMonth(today().slice(0, 7), -1), busy: false, error: null as string | null })
const saving = ref(false)

// ─────────── 헬퍼 ───────────
const load = async () => {
  if (!projectId.value) return
  loading.value = true
  error.value = null
  try {
    list.value = await getMonthlyReportsApi(projectId.value)
    if (!report.value && list.value.length) await open(list.value[0].reportId)
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const { projectId } = useCurrentProject(() => { report.value = null; void load() })

const open = async (reportId: string) => {
  try {
    report.value = await getMonthlyReportApi(reportId)
    savedJson.value = JSON.stringify(report.value)
  } catch {
    showErrorToast('common.error.unknown')
  }
}

const generate = async () => {
  if (!projectId.value) return
  gen.busy = true
  gen.error = null
  try {
    report.value = await generateMonthlyReportApi(projectId.value, gen.month)
    savedJson.value = JSON.stringify(report.value)
    gen.open = false
    showSuccessToast('sm.report.generated')
    list.value = await getMonthlyReportsApi(projectId.value)
  } catch (e) {
    const err = e as ApiError
    gen.error = err.code === 'CONFLICT' ? t('sm.report.exists') : (err.message ?? t('common.actionFailed'))
  } finally {
    gen.busy = false
  }
}

const save = async () => {
  if (!report.value) return
  saving.value = true
  try {
    report.value = await updateMonthlyReportApi(report.value.reportId, report.value)
    savedJson.value = JSON.stringify(report.value)
    showSuccessToast('common.saved')
  } catch {
    showErrorToast('common.saveFailed')
  } finally {
    saving.value = false
  }
}

const durationText = (m: number | null) => (m === null ? '-' : m >= 60 ? `${Math.floor(m / 60)}h ${m % 60}m` : `${m}m`)

// ─────────── computed ───────────
const canEdit = computed(() => auth.hasAnyRole(MONTHLY_EDIT_ROLES))
const dirty = computed(() => !!report.value && JSON.stringify(report.value) !== savedJson.value)
const totalHours = computed(() => (report.value?.effort ?? []).reduce((s, e) => s + e.hours, 0))
const totalMm = computed(() => Math.round((report.value?.effort ?? []).reduce((s, e) => s + e.mm, 0) * 100) / 100)
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.sm.report')" :subtitle="t('sm.report.subtitle')">
      <template v-if="projectId">
        <ExcelExportButton v-if="report" dataset-id="monthly-ops-report" :file-name="`${t('nav.sm.report')}_${report.month}`" :params="{ reportId: report.reportId }" />
        <VBtn v-if="canEdit" color="primary" size="small" prepend-icon="ri-magic-line" data-test="report-generate" @click="Object.assign(gen, { open: true, error: null })">{{ t('sm.report.generate') }}</VBtn>
      </template>
    </AppPageHeader>
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <VRow v-else>
      <VCol cols="12" md="3">
        <VCard border flat :title="t('sm.report.list')">
          <VProgressLinear v-if="loading" indeterminate color="primary" />
          <VAlert v-if="error" type="error" variant="tonal" density="compact" class="ma-3">{{ error }}</VAlert>
          <VList density="compact" nav>
            <VListItem v-for="r in list" :key="r.reportId" :active="r.reportId === report?.reportId" color="primary" :title="r.month" :subtitle="formatDateTime(r.updatedAt)" @click="open(r.reportId)" />
            <VListItem v-if="!list.length && !loading" :title="t('common.noData')" disabled />
          </VList>
        </VCard>
      </VCol>
      <VCol cols="12" md="9">
        <VCard v-if="report" border flat data-test="report-detail">
          <VCardTitle class="d-flex align-center ga-2">
            {{ t('sm.report.titleOf', { month: report.month }) }}
            <VSpacer />
            <VChip v-if="dirty" size="small" color="warning" variant="tonal">{{ t('sm.report.unsaved') }}</VChip>
            <VBtn v-if="canEdit" color="primary" size="small" :loading="saving" :disabled="!dirty" data-test="report-save" @click="save">{{ t('common.save') }}</VBtn>
          </VCardTitle>
          <VCardText>
            <VRow dense class="mb-2">
              <VCol cols="6" sm="3"><div class="kpi"><div class="text-caption text-muted">{{ t('sm.report.srTotal') }}</div><div class="text-h5">{{ report.sr.total }}</div><div class="text-caption text-muted">{{ t('sm.report.carried', { n: report.sr.carriedOver }) }}</div></div></VCol>
              <VCol cols="6" sm="3"><div class="kpi"><div class="text-caption text-muted">{{ t('sm.slaPage.compliance') }}</div><div class="text-h5">{{ formatPercent(report.sla.complianceRate) }}</div><div class="text-caption text-muted">{{ t('sm.slaPage.responseRate') }} {{ formatPercent(report.sla.responseRate) }}</div></div></VCol>
              <VCol cols="6" sm="3"><div class="kpi"><div class="text-caption text-muted">{{ t('sm.report.incidents') }}</div><div class="text-h5">{{ report.incidents.length }}</div></div></VCol>
              <VCol cols="6" sm="3"><div class="kpi"><div class="text-caption text-muted">{{ t('sm.report.effort') }}</div><div class="text-h5">{{ totalMm }} MM</div><div class="text-caption text-muted">{{ totalHours }}h</div></div></VCol>
            </VRow>
            <VTextarea v-model="report.summary" :label="t('sm.report.summary')" :readonly="!canEdit" rows="3" auto-grow class="mb-2" data-test="report-summary" />
            <VTextarea v-model="report.plan" :label="t('sm.report.plan')" :readonly="!canEdit" rows="2" auto-grow class="mb-4" />

            <VRow dense>
              <VCol cols="12" md="6">
                <div class="text-subtitle-2 mb-1">{{ t('sm.report.byType') }}</div>
                <VTable density="compact" class="border rounded mb-4">
                  <tbody>
                    <tr v-for="(n, k) in report.sr.byType" :key="k"><td>{{ t(`sm.srType.${k}`) }}</td><td class="text-end font-mono">{{ n }}</td></tr>
                  </tbody>
                </VTable>
              </VCol>
              <VCol cols="12" md="6">
                <div class="text-subtitle-2 mb-1">{{ t('sm.report.byStatus') }}</div>
                <VTable density="compact" class="border rounded mb-4">
                  <tbody>
                    <tr v-for="(n, k) in report.sr.byStatus" :key="k"><td>{{ t(`sm.srStatus.${k}`) }}</td><td class="text-end font-mono">{{ n }}</td></tr>
                  </tbody>
                </VTable>
              </VCol>
            </VRow>

            <div class="text-subtitle-2 mb-1">{{ t('sm.report.breaches') }}</div>
            <div class="table-scroll mb-4">
              <VTable density="compact" class="border rounded">
                <thead><tr><th>{{ t('sm.col.no') }}</th><th>{{ t('sm.col.title') }}</th><th>{{ t('sm.report.reason') }}</th></tr></thead>
                <tbody>
                  <tr v-for="b in report.sla.breaches" :key="b.srNo"><td class="font-mono">{{ b.srNo }}</td><td>{{ b.title }}</td><td>{{ b.reason ?? '-' }}</td></tr>
                  <tr v-if="!report.sla.breaches.length"><td colspan="3" class="text-muted">{{ t('common.noData') }}</td></tr>
                </tbody>
              </VTable>
            </div>

            <VRow dense>
              <VCol cols="12" md="6">
                <div class="text-subtitle-2 mb-1">{{ t('sm.report.incidents') }}</div>
                <VTable density="compact" class="border rounded mb-4">
                  <tbody>
                    <tr v-for="i in report.incidents" :key="i.incidentNo"><td class="font-mono">{{ i.incidentNo }}</td><td>{{ i.title }}</td><td>{{ t('sm.incident.gradeN', { n: i.grade }) }}</td><td class="font-mono">{{ durationText(i.durationMinutes) }}</td></tr>
                    <tr v-if="!report.incidents.length"><td class="text-muted">{{ t('common.noData') }}</td></tr>
                  </tbody>
                </VTable>
              </VCol>
              <VCol cols="12" md="6">
                <div class="text-subtitle-2 mb-1">{{ t('sm.report.changes') }}</div>
                <VTable density="compact" class="border rounded mb-4">
                  <tbody>
                    <tr v-for="c in report.changes" :key="c.changeNo"><td class="font-mono">{{ c.changeNo }}</td><td>{{ c.title }}</td><td>{{ t(`sm.changeStatus.${c.status}`) }}</td></tr>
                    <tr v-if="!report.changes.length"><td class="text-muted">{{ t('common.noData') }}</td></tr>
                  </tbody>
                </VTable>
              </VCol>
              <VCol cols="12" md="6">
                <div class="text-subtitle-2 mb-1">{{ t('sm.report.effortByPerson') }}</div>
                <VTable density="compact" class="border rounded mb-4">
                  <tbody>
                    <tr v-for="e in report.effort" :key="e.userId"><td>{{ e.name }}</td><td class="text-end font-mono">{{ e.hours }}h</td><td class="text-end font-mono">{{ e.mm }} MM</td></tr>
                  </tbody>
                </VTable>
              </VCol>
              <VCol cols="12" md="6">
                <div class="text-subtitle-2 mb-1">{{ t('sm.report.topScreens') }}</div>
                <VTable density="compact" class="border rounded mb-4">
                  <tbody>
                    <tr v-for="s in report.topScreens" :key="s.screenId"><td>{{ s.name }}</td><td class="text-end font-mono">{{ s.srCount }}</td></tr>
                  </tbody>
                </VTable>
              </VCol>
            </VRow>
          </VCardText>
        </VCard>
        <VAlert v-else type="info" variant="tonal">{{ t('sm.report.pick') }}</VAlert>
      </VCol>
    </VRow>

    <VDialog v-model="gen.open" max-width="380">
      <VCard :title="t('sm.report.generate')">
        <VCardText>
          <VAlert v-if="gen.error" type="error" variant="tonal" density="compact" class="mb-3">{{ gen.error }}</VAlert>
          <VTextField v-model="gen.month" type="month" :label="t('sm.report.month')" data-test="report-month" />
          <div class="text-caption text-muted">{{ t('sm.report.generateHint') }}</div>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="gen.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :loading="gen.busy" data-test="report-generate-ok" @click="generate">{{ t('sm.report.generate') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>
  </VContainer>
</template>

<style scoped>
.kpi {
  border: 1px solid rgb(var(--v-theme-line));
  border-radius: 8px;
  padding: 10px 12px;
}

.table-scroll {
  overflow-x: auto;
}
</style>
