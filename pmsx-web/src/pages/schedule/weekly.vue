<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  generateWeeklyReportApi, getWeeklyReportApi, getWeeklyReportsApi, submitWeeklyReportApi, updateWeeklyReportApi,
} from '@/api/scheduleApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import AppStatusChip from '@/components/common/AppStatusChip.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import ExcelExportButton from '@/components/common/ExcelExportButton.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useAuthStore } from '@/stores/auth'
import type { ApiError } from '@/types/api'
import type { WeeklyReport, WeeklyReportSummary } from '@/types/r2'
import { addDays, today, weekStart } from '@/utils/date'
import { formatDateTime } from '@/utils/format'
import { WEEKLY_EDIT_ROLES } from '@/utils/permission'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// SCR-PJ-004 주간보고 (FR-PJ-009) — WBS·이슈·리스크·MM 에서 자동 집계한 초안을 고쳐 제출한다
const { t } = useI18n()
const auth = useAuthStore()

const list = ref<WeeklyReportSummary[]>([])
const report = ref<WeeklyReport | null>(null)
const savedJson = ref('')
const loading = ref(false)
const error = ref<string | null>(null)
const saving = ref(false)
const gen = reactive({ open: false, weekStart: weekStart(today()), saving: false, error: null as string | null })
const submitOpen = ref(false)

// ─────────── 헬퍼 ───────────
const loadList = async (projectId: string) => {
  error.value = null
  try {
    list.value = await getWeeklyReportsApi(projectId)
    if (list.value[0] && !report.value) await open(list.value[0].reportId)
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  }
}
const { projectId } = useCurrentProject((id) => { report.value = null; return loadList(id) })

const open = async (reportId: string) => {
  loading.value = true
  try {
    report.value = await getWeeklyReportApi(reportId)
    savedJson.value = JSON.stringify(report.value)
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}

const generate = async () => {
  if (!projectId.value) return
  gen.saving = true
  gen.error = null
  try {
    const r = await generateWeeklyReportApi(projectId.value, weekStart(gen.weekStart))
    gen.open = false
    await loadList(projectId.value)
    report.value = r
    savedJson.value = JSON.stringify(r)
  } catch (e) {
    const err = e as ApiError
    gen.error = err.code === 'CONFLICT' ? t('pj.weekly.exists') : (err.message ?? t('common.saveFailed'))
  } finally {
    gen.saving = false
  }
}

const save = async () => {
  if (!report.value) return
  saving.value = true
  try {
    report.value = await updateWeeklyReportApi(report.value.reportId, report.value)
    savedJson.value = JSON.stringify(report.value)
    showSuccessToast('common.saved')
  } catch {
    showErrorToast('common.saveFailed')
  } finally {
    saving.value = false
  }
}

const submit = async () => {
  if (!report.value || !projectId.value) return
  saving.value = true
  try {
    if (dirty.value) await updateWeeklyReportApi(report.value.reportId, report.value)
    report.value = await submitWeeklyReportApi(report.value.reportId)
    savedJson.value = JSON.stringify(report.value)
    submitOpen.value = false
    showSuccessToast('pj.weekly.submitted')
    await loadList(projectId.value)
  } catch {
    showErrorToast('common.saveFailed')
  } finally {
    saving.value = false
  }
}

// ─────────── computed ───────────
const canEdit = computed(() => auth.hasAnyRole(WEEKLY_EDIT_ROLES) && report.value?.status === 'DRAFT')
const dirty = computed(() => !!report.value && JSON.stringify(report.value) !== savedJson.value)
const period = computed(() => (report.value ? `${report.value.weekStart} ~ ${addDays(report.value.weekStart, 4)}` : ''))
const delta = computed(() => (report.value ? Math.round((report.value.progress.actual - report.value.progress.prevActual) * 10) / 10 : 0))
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.schedule.weekly')" :subtitle="t('pj.weekly.subtitle')">
      <VBtn v-if="auth.hasAnyRole(WEEKLY_EDIT_ROLES) && projectId" color="primary" size="small" prepend-icon="ri-magic-line" data-test="weekly-generate" @click="Object.assign(gen, { open: true, error: null })">
        {{ t('pj.weekly.generate') }}
      </VBtn>
    </AppPageHeader>
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <VRow v-else>
      <VCol cols="12" md="3">
        <VCard border flat>
          <VList density="compact" :aria-label="t('pj.weekly.list')">
            <VListSubheader>{{ t('pj.weekly.list') }}</VListSubheader>
            <VListItem
              v-for="w in list"
              :key="w.reportId"
              :active="report?.reportId === w.reportId"
              :title="t('pj.weekly.weekOf', { d: w.weekStart })"
              :subtitle="formatDateTime(w.updatedAt)"
              @click="open(w.reportId)"
            >
              <template #append><AppStatusChip :status="w.status" prefix="pj.weekly.status" size="x-small" /></template>
            </VListItem>
            <VListItem v-if="!list.length" :title="t('pj.weekly.none')" disabled />
          </VList>
        </VCard>
      </VCol>
      <VCol cols="12" md="9">
        <VAlert v-if="error" type="error" variant="tonal" density="compact" class="mb-3">{{ error }}</VAlert>
        <VProgressLinear v-if="loading" indeterminate color="primary" class="mb-2" />
        <VCard v-if="report" border flat data-test="weekly-report">
          <VCardTitle class="d-flex flex-wrap align-center ga-2">
            {{ t('pj.weekly.title', { period }) }}
            <AppStatusChip :status="report.status" prefix="pj.weekly.status" />
            <VSpacer />
            <ExcelExportButton dataset-id="weekly-report" :file-name="t('pj.weekly.fileName', { d: report.weekStart })" :params="{ reportId: report.reportId }" />
            <template v-if="canEdit">
              <VBtn size="small" variant="tonal" :disabled="!dirty" :loading="saving" @click="save">{{ t('common.save') }}</VBtn>
              <VBtn size="small" color="primary" prepend-icon="ri-send-plane-line" data-test="weekly-submit" @click="submitOpen = true">{{ t('pj.weekly.submit') }}</VBtn>
            </template>
          </VCardTitle>
          <VCardText>
            <div class="kpis mb-4">
              <div class="kpi"><span>{{ t('pj.progress.planned') }}</span><b>{{ report.progress.planned }}%</b></div>
              <div class="kpi"><span>{{ t('pj.progress.actual') }}</span><b class="text-primary">{{ report.progress.actual }}%</b></div>
              <div class="kpi"><span>{{ t('pj.weekly.delta') }}</span><b :class="delta >= 0 ? 'text-success' : 'text-error'">{{ delta >= 0 ? '+' : '' }}{{ delta }}%p</b></div>
              <div class="kpi"><span>{{ t('pj.weekly.mm') }}</span><b>{{ report.mm.actual }} / {{ report.mm.planned }}</b></div>
              <div v-if="report.analysis" class="kpi"><span>{{ t('pj.weekly.analysis') }}</span><b>{{ report.analysis.done }} / {{ report.analysis.total }}</b></div>
            </div>

            <h3 class="section">{{ t('pj.weekly.summary') }}</h3>
            <VTextarea v-if="canEdit" v-model="report.summary" rows="3" auto-grow :placeholder="t('pj.weekly.summaryPh')" density="compact" />
            <p v-else class="text-body-2 pre">{{ report.summary || '-' }}</p>

            <h3 class="section">{{ t('pj.weekly.thisWeek') }}</h3>
            <VTable density="compact" class="border rounded">
              <thead><tr><th>{{ t('pj.wbs.colNo') }}</th><th>{{ t('pj.wbs.colName') }}</th><th>{{ t('pj.wbs.colAssignees') }}</th><th class="text-end">{{ t('pj.wbs.colProgress') }}</th><th>{{ t('pj.weekly.note') }}</th></tr></thead>
              <tbody>
                <tr v-for="(r, i) in report.thisWeek" :key="`tw-${i}`">
                  <td class="font-mono">{{ r.wbsNo }}</td>
                  <td>{{ r.name }} <VIcon v-if="r.done" icon="ri-checkbox-circle-fill" size="14" color="success" /></td>
                  <td>{{ r.assignees }}</td>
                  <td class="text-end">{{ r.progress }}%</td>
                  <td><input v-if="canEdit" v-model="r.note" class="note-input" :aria-label="t('pj.weekly.note')"><span v-else>{{ r.note }}</span></td>
                </tr>
                <tr v-if="!report.thisWeek.length"><td colspan="5" class="text-muted text-center">{{ t('common.noData') }}</td></tr>
              </tbody>
            </VTable>

            <h3 class="section">{{ t('pj.weekly.nextWeek') }}</h3>
            <VTable density="compact" class="border rounded">
              <thead><tr><th>{{ t('pj.wbs.colNo') }}</th><th>{{ t('pj.wbs.colName') }}</th><th>{{ t('pj.wbs.colAssignees') }}</th><th>{{ t('pj.wbs.colEnd') }}</th><th>{{ t('pj.weekly.note') }}</th></tr></thead>
              <tbody>
                <tr v-for="(r, i) in report.nextWeek" :key="`nw-${i}`">
                  <td class="font-mono">{{ r.wbsNo }}</td><td>{{ r.name }}</td><td>{{ r.assignees }}</td><td class="font-mono">{{ r.planEnd }}</td>
                  <td><input v-if="canEdit" v-model="r.note" class="note-input" :aria-label="t('pj.weekly.note')"><span v-else>{{ r.note }}</span></td>
                </tr>
                <tr v-if="!report.nextWeek.length"><td colspan="5" class="text-muted text-center">{{ t('common.noData') }}</td></tr>
              </tbody>
            </VTable>

            <VRow class="mt-1">
              <VCol cols="12" lg="6">
                <h3 class="section">{{ t('pj.weekly.issues') }}</h3>
                <VTable density="compact" class="border rounded">
                  <tbody>
                    <tr v-for="r in report.issues" :key="r.no">
                      <td class="font-mono text-no-wrap">{{ r.no }}</td><td>{{ r.title }}</td>
                      <td><AppStatusChip :status="r.status" prefix="qa.issueStatus" size="x-small" /></td>
                      <td class="text-no-wrap">{{ r.owner }}</td><td class="font-mono text-no-wrap">{{ r.dueDate ?? '-' }}</td>
                    </tr>
                    <tr v-if="!report.issues.length"><td class="text-muted text-center">{{ t('common.noData') }}</td></tr>
                  </tbody>
                </VTable>
              </VCol>
              <VCol cols="12" lg="6">
                <h3 class="section">{{ t('pj.weekly.risks') }}</h3>
                <VTable density="compact" class="border rounded">
                  <tbody>
                    <tr v-for="r in report.risks" :key="r.no">
                      <td class="font-mono text-no-wrap">{{ r.no }}</td><td>{{ r.title }}</td>
                      <td><VChip size="x-small" :color="r.score >= 15 ? 'error' : 'warning'" variant="tonal" label>{{ r.score }}</VChip></td>
                      <td class="text-caption">{{ r.response }}</td>
                    </tr>
                    <tr v-if="!report.risks.length"><td class="text-muted text-center">{{ t('common.noData') }}</td></tr>
                  </tbody>
                </VTable>
              </VCol>
            </VRow>
          </VCardText>
        </VCard>
        <VAlert v-else-if="!loading" type="info" variant="tonal">{{ t('pj.weekly.pick') }}</VAlert>
      </VCol>
    </VRow>

    <VDialog v-model="gen.open" max-width="420">
      <VCard :title="t('pj.weekly.generate')">
        <VCardText>
          <VTextField v-model="gen.weekStart" type="date" :label="t('pj.weekly.weekStart')" :hint="t('pj.weekly.weekStartHint')" persistent-hint />
          <VAlert v-if="gen.error" type="error" variant="tonal" density="compact" class="mt-3">{{ gen.error }}</VAlert>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="gen.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :loading="gen.saving" data-test="weekly-generate-run" @click="generate">{{ t('pj.weekly.generateRun') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>
    <ConfirmDialog
      v-model="submitOpen"
      :title="t('pj.weekly.submit')"
      :message="t('pj.weekly.submitConfirm')"
      :confirm-text="t('pj.weekly.submit')"
      color="primary"
      :loading="saving"
      @confirm="submit"
    />
  </VContainer>
</template>

<style scoped>
.kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 8px;
}

.kpi {
  display: flex;
  flex-direction: column;
  border: 1px solid rgb(var(--v-theme-line));
  border-radius: 8px;
  padding: 8px 12px;
}

.kpi span {
  font-size: 12px;
  color: rgb(var(--v-theme-muted));
}

.kpi b {
  font-size: 18px;
  font-variant-numeric: tabular-nums;
}

.section {
  font-size: 14px;
  font-weight: 600;
  margin: 16px 0 8px;
}

.pre {
  white-space: pre-wrap;
}

.note-input {
  width: 100%;
  min-width: 100px;
  border: 1px solid rgb(var(--v-theme-line));
  border-radius: 4px;
  padding: 2px 6px;
  font: inherit;
  color: inherit;
  background: transparent;
}

.note-input:focus {
  outline: none;
  border-color: rgb(var(--v-theme-primary));
}
</style>
