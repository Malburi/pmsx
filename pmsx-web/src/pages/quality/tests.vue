<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  createTestCaseApi, createTestRoundApi, createTestScenarioApi, deleteTestCaseApi, getTestResultsApi, getTestRoundsApi, getTestScenariosApi,
  saveTestResultsApi, updateTestCaseApi,
} from '@/api/qualityApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import AppStatusChip from '@/components/common/AppStatusChip.vue'
import ExcelExportButton from '@/components/common/ExcelExportButton.vue'
import DefectFormDialog from '@/components/quality/DefectFormDialog.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useTraceTargets, type TraceTarget } from '@/composables/useTraceTargets'
import { useAuthStore } from '@/stores/auth'
import type { ApiError } from '@/types/api'
import type { Defect, TestCase, TestCasePayload, TestLevel, TestResult, TestResultRow, TestRound, TestScenario } from '@/types/r2'
import { TEST_EDIT_ROLES } from '@/utils/permission'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// SCR-QA-004 테스트 시나리오·케이스·회차 결과 (FR-QA-005·006)
const { t } = useI18n()
const auth = useAuthStore()
const { load: loadTargets } = useTraceTargets()

const LEVELS: TestLevel[] = ['UNIT', 'INTEGRATION', 'ACCEPTANCE']
const RESULTS: TestResult[] = ['PASS', 'FAIL', 'BLOCK', 'NOT_RUN']
const tab = ref<'cases' | 'run'>('cases')
const scenarios = ref<TestScenario[]>([])
const rounds = ref<TestRound[]>([])
const roundId = ref<string | null>(null)
const results = ref<TestResultRow[]>([])
const resultsJson = ref('[]')
const loading = ref(false)
const error = ref<string | null>(null)
const selCaseId = ref<string | null>(null)
const reqs = ref<TraceTarget[]>([])
const screens = ref<TraceTarget[]>([])
const scnDlg = reactive({ open: false, level: 'UNIT' as TestLevel, name: '' })
const roundDlg = reactive({ open: false, level: 'UNIT' as TestLevel, name: '' })
const caseDlg = reactive({ open: false, scenarioId: null as string | null, editId: null as string | null, saving: false, data: {} as TestCasePayload })
const defectDlg = reactive({ open: false, row: null as TestResultRow | null, preset: {} as Record<string, unknown> })

// ─────────── 헬퍼 ───────────
const load = async (projectId: string) => {
  loading.value = true
  error.value = null
  try {
    const [s, r] = await Promise.all([getTestScenariosApi(projectId), getTestRoundsApi(projectId)])
    scenarios.value = s
    rounds.value = r
    if (!roundId.value && r.length) roundId.value = r[r.length - 1].roundId
    if (!reqs.value.length) [reqs.value, screens.value] = await Promise.all([loadTargets('REQ'), loadTargets('SCREEN')]).catch(() => [[], []])
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const { projectId } = useCurrentProject(load)
const reload = () => { if (projectId.value) void load(projectId.value) }

const loadResults = async (id: string) => {
  const saved = await getTestResultsApi(id).catch(() => [] as TestResultRow[])
  // 회차 레벨의 모든 케이스를 행으로 깔고, 저장된 결과를 덮는다
  results.value = roundCases.value.map((c) => saved.find((r) => r.caseId === c.caseId) ?? { caseId: c.caseId, result: 'NOT_RUN', note: '', defectId: null })
  resultsJson.value = JSON.stringify(results.value)
}

const addScenario = async () => {
  if (!projectId.value || !scnDlg.name.trim()) return
  try {
    await createTestScenarioApi(projectId.value, { level: scnDlg.level, name: scnDlg.name.trim() })
    scnDlg.open = false
    reload()
  } catch {
    showErrorToast('common.saveFailed')
  }
}

const addRound = async () => {
  if (!projectId.value || !roundDlg.name.trim()) return
  try {
    const r = await createTestRoundApi(projectId.value, { level: roundDlg.level, name: roundDlg.name.trim() })
    roundDlg.open = false
    rounds.value.push(r)
    roundId.value = r.roundId
    tab.value = 'run'
  } catch {
    showErrorToast('common.saveFailed')
  }
}

const openCase = (scenarioId: string, c: TestCase | null) => {
  Object.assign(caseDlg, {
    open: true, scenarioId, editId: c?.caseId ?? null, saving: false,
    data: c ? { title: c.title, precondition: c.precondition, steps: c.steps, expected: c.expected, reqIds: [...c.reqIds], screenId: c.screenId }
      : { title: '', precondition: '', steps: '', expected: '', reqIds: [], screenId: null },
  })
}

const saveCase = async () => {
  if (!caseDlg.scenarioId || !caseDlg.data.title.trim()) return
  caseDlg.saving = true
  try {
    if (caseDlg.editId) await updateTestCaseApi(caseDlg.editId, caseDlg.data)
    else await createTestCaseApi(caseDlg.scenarioId, caseDlg.data)
    caseDlg.open = false
    showSuccessToast('common.saved')
    reload()
  } catch {
    showErrorToast('common.saveFailed')
  } finally {
    caseDlg.saving = false
  }
}

const removeCase = async (caseId: string) => {
  try {
    await deleteTestCaseApi(caseId)
    if (selCaseId.value === caseId) selCaseId.value = null
    reload()
  } catch {
    showErrorToast('common.deleteFailed')
  }
}

const saveResults = async () => {
  if (!roundId.value) return
  try {
    results.value = await saveTestResultsApi(roundId.value, results.value)
    resultsJson.value = JSON.stringify(results.value)
    showSuccessToast('common.saved')
    reload()
  } catch {
    showErrorToast('common.saveFailed')
  }
}

const caseOf = (id: string) => scenarios.value.flatMap((s) => s.cases).find((c) => c.caseId === id)

const openDefect = (row: TestResultRow) => {
  const c = caseOf(row.caseId)
  Object.assign(defectDlg, {
    open: true, row,
    preset: { title: c ? `[${c.tcNo}] ${c.title} 실패` : '', caseId: row.caseId, screenId: c?.screenId ?? null, description: row.note, context: t('qa.test.defectFromCase', { tc: c?.tcNo ?? '' }) },
  })
}

const onDefectSaved = (d: Defect) => {
  if (defectDlg.row) defectDlg.row.defectId = d.defectId
  showSuccessToast('qa.test.defectCreated')
}

const labelOf = (list: TraceTarget[], id: string | null) => (id ? list.find((x) => x.id === id)?.label ?? id : '-')

// ─────────── computed ───────────
const canEdit = computed(() => auth.hasAnyRole(TEST_EDIT_ROLES))
const round = computed(() => rounds.value.find((r) => r.roundId === roundId.value) ?? null)
const roundCases = computed(() => scenarios.value.filter((s) => s.level === round.value?.level).flatMap((s) => s.cases))
const selected = computed(() => (selCaseId.value ? caseOf(selCaseId.value) ?? null : null))
const resultsDirty = computed(() => JSON.stringify(results.value) !== resultsJson.value)
const summary = computed(() => Object.fromEntries(RESULTS.map((r) => [r, results.value.filter((x) => x.result === r).length])) as Record<TestResult, number>)
const levelItems = computed(() => LEVELS.map((l) => ({ title: t(`qa.level.${l}`), value: l })))
const resultItems = computed(() => RESULTS.map((r) => ({ title: t(`qa.result.${r}`), value: r })))
const roundItems = computed(() => rounds.value.map((r) => ({ title: `${r.name} (${t(`qa.level.${r.level}`)})`, value: r.roundId })))
const reqItems = computed(() => reqs.value.map((x) => ({ title: x.label, value: x.id })))
const screenItems = computed(() => screens.value.map((x) => ({ title: x.label, value: x.id })))

// ─────────── watch ───────────
watch([roundId, () => scenarios.value.length], ([id]) => { if (id) void loadResults(id as string) })
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.quality.test')" :subtitle="t('qa.test.subtitle')">
      <template v-if="projectId">
        <ExcelExportButton dataset-id="test-cases" :file-name="t('qa.test.fileName')" :params="{ projectId }" />
        <template v-if="canEdit">
          <VBtn size="small" variant="tonal" prepend-icon="ri-folder-add-line" @click="Object.assign(scnDlg, { open: true, name: '' })">{{ t('qa.test.addScenario') }}</VBtn>
          <VBtn size="small" color="primary" prepend-icon="ri-play-list-add-line" data-test="round-add" @click="Object.assign(roundDlg, { open: true, name: '' })">{{ t('qa.test.addRound') }}</VBtn>
        </template>
      </template>
    </AppPageHeader>
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <VCard v-else border flat>
      <VTabs v-model="tab" density="compact" color="primary" class="border-b">
        <VTab value="cases">{{ t('qa.test.tabCases') }}</VTab>
        <VTab value="run" data-test="test-run-tab">{{ t('qa.test.tabRun') }}</VTab>
      </VTabs>
      <VAlert v-if="error" type="error" variant="tonal" density="compact" class="ma-3">{{ error }}</VAlert>
      <VProgressLinear v-if="loading" indeterminate color="primary" />

      <VRow v-show="tab === 'cases'" no-gutters>
        <VCol cols="12" md="5" class="border-e">
          <VList density="compact" open-strategy="multiple" :opened="scenarios.map((s) => s.scenarioId)" data-test="scenario-tree">
            <VListGroup v-for="s in scenarios" :key="s.scenarioId" :value="s.scenarioId">
              <template #activator="{ props: gp }">
                <VListItem v-bind="gp" :title="s.name" prepend-icon="ri-folder-3-line">
                  <template #append>
                    <VChip size="x-small" label variant="tonal" class="mr-1">{{ t(`qa.level.${s.level}`) }}</VChip>
                    <VBtn v-if="canEdit" icon variant="text" size="x-small" :aria-label="t('qa.test.addCase')" @click.stop="openCase(s.scenarioId, null)"><VIcon icon="ri-add-line" /></VBtn>
                  </template>
                </VListItem>
              </template>
              <VListItem
                v-for="c in s.cases"
                :key="c.caseId"
                :active="c.caseId === selCaseId"
                :title="c.title"
                :subtitle="c.tcNo"
                @click="selCaseId = c.caseId"
              >
                <template #append><AppStatusChip :status="c.lastResult ?? 'NOT_RUN'" prefix="qa.result" size="x-small" /></template>
              </VListItem>
            </VListGroup>
          </VList>
        </VCol>
        <VCol cols="12" md="7">
          <div v-if="selected" class="pa-4" data-test="case-detail">
            <div class="d-flex align-center ga-2 mb-2">
              <span class="font-mono text-body-2">{{ selected.tcNo }}</span>
              <AppStatusChip :status="selected.lastResult ?? 'NOT_RUN'" prefix="qa.result" size="x-small" />
              <VSpacer />
              <template v-if="canEdit">
                <VBtn icon variant="text" size="small" :aria-label="t('common.edit')" @click="openCase(scenarios.find((s) => s.cases.includes(selected as TestCase))?.scenarioId ?? '', selected)"><VIcon icon="ri-edit-line" /></VBtn>
                <VBtn icon variant="text" size="small" :aria-label="t('common.delete')" @click="removeCase(selected.caseId)"><VIcon icon="ri-delete-bin-line" /></VBtn>
              </template>
            </div>
            <div class="text-subtitle-1 font-weight-medium mb-3">{{ selected.title }}</div>
            <dl class="kv">
              <dt>{{ t('qa.test.precondition') }}</dt><dd>{{ selected.precondition || '-' }}</dd>
              <dt>{{ t('qa.test.steps') }}</dt><dd class="pre">{{ selected.steps || '-' }}</dd>
              <dt>{{ t('qa.test.expected') }}</dt><dd>{{ selected.expected || '-' }}</dd>
              <dt>{{ t('qa.test.reqs') }}</dt><dd>{{ selected.reqIds.map((r) => labelOf(reqs, r)).join(', ') || '-' }}</dd>
              <dt>{{ t('qa.test.screen') }}</dt><dd>{{ labelOf(screens, selected.screenId) }}</dd>
            </dl>
          </div>
          <div v-else class="pa-6 text-body-2 text-muted">{{ t('qa.test.pickCase') }}</div>
        </VCol>
      </VRow>

      <div v-show="tab === 'run'" class="pa-4">
        <div class="d-flex flex-wrap align-center ga-2 mb-3">
          <VSelect v-model="roundId" :items="roundItems" :label="t('qa.test.round')" hide-details class="round-select" />
          <VChip v-for="r in RESULTS" :key="r" size="small" :color="r === 'PASS' ? 'success' : r === 'FAIL' ? 'error' : r === 'BLOCK' ? 'warning' : undefined" variant="tonal" label>
            {{ t(`qa.result.${r}`) }} {{ summary[r] }}
          </VChip>
          <VSpacer />
          <VBtn v-if="canEdit" color="primary" size="small" :disabled="!resultsDirty" data-test="results-save" @click="saveResults">{{ t('common.save') }}</VBtn>
        </div>
        <VTable density="compact" class="border rounded" data-test="results-grid">
          <thead>
            <tr><th>{{ t('qa.col.no') }}</th><th>{{ t('qa.test.case') }}</th><th class="res-col">{{ t('qa.test.result') }}</th><th>{{ t('qa.test.note') }}</th><th>{{ t('nav.quality.defect') }}</th></tr>
          </thead>
          <tbody>
            <tr v-for="row in results" :key="row.caseId" :class="`res-${row.result}`">
              <td class="font-mono text-caption">{{ caseOf(row.caseId)?.tcNo }}</td>
              <td class="text-body-2">{{ caseOf(row.caseId)?.title }}</td>
              <td>
                <VSelect v-if="canEdit" v-model="row.result" :items="resultItems" density="compact" hide-details variant="plain" :aria-label="t('qa.test.result')" />
                <AppStatusChip v-else :status="row.result" prefix="qa.result" size="x-small" />
              </td>
              <td><input v-if="canEdit" v-model="row.note" class="note" :aria-label="t('qa.test.note')"><span v-else class="text-caption">{{ row.note }}</span></td>
              <td>
                <VChip v-if="row.defectId" size="x-small" color="error" variant="tonal" :to="{ path: '/quality/defects', query: { id: row.defectId } }">{{ t('qa.test.linked') }}</VChip>
                <VBtn v-else-if="row.result === 'FAIL' && canEdit" size="x-small" variant="tonal" color="error" prepend-icon="ri-bug-line" :data-test="`fail-defect-${row.caseId}`" @click="openDefect(row)">
                  {{ t('qa.test.createDefect') }}
                </VBtn>
              </td>
            </tr>
            <tr v-if="!results.length"><td colspan="5" class="text-center text-muted pa-6">{{ round ? t('qa.test.noCases') : t('qa.test.noRound') }}</td></tr>
          </tbody>
        </VTable>
      </div>
    </VCard>

    <VDialog v-model="scnDlg.open" max-width="420">
      <VCard :title="t('qa.test.addScenario')">
        <VCardText>
          <VSelect v-model="scnDlg.level" :items="levelItems" :label="t('qa.test.level')" class="mb-3" />
          <VTextField v-model="scnDlg.name" :label="t('qa.test.scenarioName')" />
        </VCardText>
        <VCardActions><VSpacer /><VBtn variant="text" @click="scnDlg.open = false">{{ t('common.cancel') }}</VBtn><VBtn color="primary" :disabled="!scnDlg.name.trim()" @click="addScenario">{{ t('common.add') }}</VBtn></VCardActions>
      </VCard>
    </VDialog>
    <VDialog v-model="roundDlg.open" max-width="420">
      <VCard :title="t('qa.test.addRound')">
        <VCardText>
          <VSelect v-model="roundDlg.level" :items="levelItems" :label="t('qa.test.level')" class="mb-3" />
          <VTextField v-model="roundDlg.name" :label="t('qa.test.roundName')" :placeholder="t('qa.test.roundPh')" />
        </VCardText>
        <VCardActions><VSpacer /><VBtn variant="text" @click="roundDlg.open = false">{{ t('common.cancel') }}</VBtn><VBtn color="primary" :disabled="!roundDlg.name.trim()" @click="addRound">{{ t('common.add') }}</VBtn></VCardActions>
      </VCard>
    </VDialog>
    <VDialog v-model="caseDlg.open" max-width="600">
      <VCard :title="caseDlg.editId ? t('qa.test.editCase') : t('qa.test.addCase')">
        <VCardText>
          <VTextField v-model="caseDlg.data.title" :label="t('qa.test.case')" class="mb-2" />
          <VTextField v-model="caseDlg.data.precondition" :label="t('qa.test.precondition')" class="mb-2" />
          <VTextarea v-model="caseDlg.data.steps" :label="t('qa.test.steps')" rows="3" class="mb-2" />
          <VTextField v-model="caseDlg.data.expected" :label="t('qa.test.expected')" class="mb-2" />
          <VAutocomplete v-model="caseDlg.data.reqIds" :items="reqItems" :label="t('qa.test.reqs')" multiple chips closable-chips class="mb-2" />
          <VAutocomplete v-model="caseDlg.data.screenId" :items="screenItems" :label="t('qa.test.screen')" clearable />
        </VCardText>
        <VCardActions><VSpacer /><VBtn variant="text" @click="caseDlg.open = false">{{ t('common.cancel') }}</VBtn><VBtn color="primary" :loading="caseDlg.saving" :disabled="!caseDlg.data.title?.trim()" @click="saveCase">{{ t('common.save') }}</VBtn></VCardActions>
      </VCard>
    </VDialog>
    <DefectFormDialog v-model="defectDlg.open" :preset="defectDlg.preset" @saved="onDefectSaved" />
  </VContainer>
</template>

<style scoped>
.round-select {
  min-width: 260px;
  max-width: 340px;
}

.res-col {
  width: 130px;
}

tr.res-FAIL td:first-child {
  box-shadow: inset 3px 0 0 rgb(var(--v-theme-error));
}

tr.res-PASS td:first-child {
  box-shadow: inset 3px 0 0 rgb(var(--v-theme-success));
}

tr.res-BLOCK td:first-child {
  box-shadow: inset 3px 0 0 rgb(var(--v-theme-warning));
}

.kv {
  display: grid;
  grid-template-columns: 96px 1fr;
  gap: 6px 12px;
  font-size: 13px;
}

.kv dt {
  color: rgb(var(--v-theme-muted));
}

.kv dd {
  margin: 0;
}

.pre {
  white-space: pre-wrap;
}

.note {
  width: 100%;
  min-width: 120px;
  border: 1px solid rgb(var(--v-theme-line));
  border-radius: 4px;
  padding: 3px 6px;
  font: inherit;
  color: inherit;
  background: transparent;
}
</style>
