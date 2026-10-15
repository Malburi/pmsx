<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { createRiskApi, deleteRiskApi, getRisksApi, riskToIssueApi, updateRiskApi } from '@/api/qualityApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import AppStatusChip from '@/components/common/AppStatusChip.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import ExcelExportButton from '@/components/common/ExcelExportButton.vue'
import UserLookupField from '@/components/common/UserLookupField.vue'
import RiskMatrix from '@/components/quality/RiskMatrix.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useElementWidth } from '@/composables/useElementWidth'
import { useAuthStore } from '@/stores/auth'
import type { ApiError } from '@/types/api'
import type { Risk, RiskPayload } from '@/types/r2'
import { fitColumns } from '@/utils/columns'
import { ISSUE_EDIT_ROLES } from '@/utils/permission'
import { RISK_LEVEL_COLOR, RISK_STATUSES, riskLevel } from '@/utils/quality'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// SCR-QA-002 리스크 (FR-QA-002) — 5×5 매트릭스, 대응 계획, 이슈 전환
const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()

const risks = ref<Risk[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const cell = ref<{ p: number; i: number } | null>(null)
const showClosed = ref(false)
const form = reactive({
  open: false, editId: null as string | null, saving: false, error: null as string | null,
  data: { title: '', description: '', probability: 3, impact: 3, response: '', status: 'IDENTIFIED', ownerId: null, dueDate: null } as RiskPayload,
  owner: null as Risk['owner'],
})
const convert = ref<Risk | null>(null)
const removeTarget = ref<Risk | null>(null)

// ─────────── 헬퍼 ───────────
const load = async (projectId: string) => {
  loading.value = true
  error.value = null
  try {
    risks.value = await getRisksApi(projectId)
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const { projectId } = useCurrentProject(load)
const reload = () => { if (projectId.value) void load(projectId.value) }

const openForm = (r: Risk | null) => {
  Object.assign(form, {
    open: true, editId: r?.riskId ?? null, saving: false, error: null, owner: r?.owner ?? null,
    data: r ? { title: r.title, description: r.description, probability: r.probability, impact: r.impact, response: r.response, status: r.status, ownerId: r.owner?.userId ?? null, dueDate: r.dueDate }
      : { title: '', description: '', probability: 3, impact: 3, response: '', status: 'IDENTIFIED', ownerId: null, dueDate: null },
  })
}

const save = async () => {
  if (!projectId.value) return
  if (!form.data.title.trim()) { form.error = t('validation.required'); return }
  form.saving = true
  try {
    if (form.editId) await updateRiskApi(form.editId, form.data)
    else await createRiskApi(projectId.value, form.data)
    form.open = false
    showSuccessToast('common.saved')
    reload()
  } catch (e) {
    form.error = (e as ApiError).message ?? t('common.saveFailed')
  } finally {
    form.saving = false
  }
}

const toIssue = async () => {
  if (!convert.value) return
  try {
    const issue = await riskToIssueApi(convert.value.riskId)
    convert.value = null
    showSuccessToast('qa.risk.converted')
    await router.push({ path: '/quality/issues', query: { id: issue.issueId } })
  } catch {
    showErrorToast('common.actionFailed')
  }
}

const remove = async () => {
  if (!removeTarget.value) return
  try {
    await deleteRiskApi(removeTarget.value.riskId)
    removeTarget.value = null
    reload()
  } catch {
    showErrorToast('common.deleteFailed')
  }
}

// ─────────── computed ───────────
const canEdit = computed(() => auth.hasAnyRole(ISSUE_EDIT_ROLES))
const shown = computed(() => risks.value
  .filter((r) => (showClosed.value || r.status !== 'CLOSED') && (!cell.value || (r.probability === cell.value.p && r.impact === cell.value.i)))
  .sort((a, b) => b.score - a.score))
const statusItems = computed(() => RISK_STATUSES.map((s) => ({ title: t(`qa.riskStatus.${s}`), value: s })))
const scaleItems = [1, 2, 3, 4, 5].map((n) => ({ title: String(n), value: n }))
const formScore = computed(() => form.data.probability * form.data.impact)
// 2단 화면에서 목록 자리가 좁으면 우선순위 낮은 열을 숨긴다 (마지막 열 잘림 방지)
const listCard = ref<HTMLElement | { $el: HTMLElement } | null>(null)
const listWidth = useElementWidth(listCard, 900)
const allHeaders = computed(() => [
  { title: t('qa.col.no'), key: 'riskNo', width: 100 },
  { title: t('qa.col.title'), key: 'title', minWidth: 200 },
  { title: t('qa.risk.score'), key: 'score', width: 80, align: 'center' as const },
  { title: t('qa.risk.response'), key: 'response', minWidth: 200, hideBelow: 820 },
  { title: t('qa.col.status'), key: 'status', width: 100 },
  { title: t('qa.col.owner'), key: 'owner', width: 90, hideBelow: 680 },
  { title: '', key: 'actions', sortable: false, width: 120, align: 'end' as const },
])
const headers = computed(() => fitColumns(allHeaders.value, listWidth.value))
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.quality.risk')" :subtitle="t('qa.risk.subtitle')">
      <template v-if="projectId">
        <ExcelExportButton dataset-id="risks" :file-name="t('nav.quality.risk')" :params="{ projectId }" />
        <VBtn v-if="canEdit" color="primary" size="small" prepend-icon="ri-add-line" data-test="risk-add" @click="openForm(null)">{{ t('qa.risk.add') }}</VBtn>
      </template>
    </AppPageHeader>
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <VRow v-else>
      <VCol cols="12" lg="4">
        <VCard border flat :title="t('qa.risk.matrix')">
          <VCardText>
            <RiskMatrix :risks="risks" :selected="cell" @select="cell = $event" />
            <div class="text-caption text-muted mt-2">{{ t('qa.risk.matrixHint') }}</div>
          </VCardText>
        </VCard>
      </VCol>
      <VCol cols="12" lg="8">
        <VCard ref="listCard" border flat>
          <div class="d-flex align-center px-4 pt-3 ga-2">
            <VChip v-if="cell" closable label @click:close="cell = null">{{ t('qa.risk.cellFilter', { p: cell.p, i: cell.i }) }}</VChip>
            <VSpacer />
            <VCheckbox v-model="showClosed" :label="t('qa.risk.showClosed')" density="compact" hide-details />
          </div>
          <VAlert v-if="error" type="error" variant="tonal" density="compact" class="ma-3">{{ error }}</VAlert>
          <VDataTable :headers="headers" :items="shown" :loading="loading" item-value="riskId" density="compact" :items-per-page="50" data-test="risk-table">
            <template #item.riskNo="{ item }"><span class="font-mono text-no-wrap">{{ item.riskNo }}</span></template>
            <template #item.score="{ item }">
              <VChip size="small" :color="RISK_LEVEL_COLOR[riskLevel(item.score)]" variant="flat" label>{{ item.score }}</VChip>
              <div class="text-caption text-muted">{{ item.probability }}×{{ item.impact }}</div>
            </template>
            <template #item.response="{ item }"><span class="text-caption">{{ item.response }}</span></template>
            <template #item.status="{ item }"><AppStatusChip :status="item.status" prefix="qa.riskStatus" size="x-small" /></template>
            <template #item.owner="{ item }">{{ item.owner?.name ?? '-' }}</template>
            <template #item.actions="{ item }">
              <template v-if="canEdit">
                <VBtn v-if="item.status !== 'OCCURRED' && item.status !== 'CLOSED'" size="x-small" variant="tonal" color="error" :data-test="`risk-to-issue-${item.riskId}`" @click="convert = item">{{ t('qa.risk.toIssue') }}</VBtn>
                <VBtn icon variant="text" size="x-small" :aria-label="t('common.edit')" @click="openForm(item)"><VIcon icon="ri-edit-line" /></VBtn>
                <VBtn icon variant="text" size="x-small" :aria-label="t('common.delete')" @click="removeTarget = item"><VIcon icon="ri-delete-bin-line" /></VBtn>
              </template>
            </template>
          </VDataTable>
        </VCard>
      </VCol>
    </VRow>

    <VDialog v-model="form.open" max-width="560">
      <VCard :title="form.editId ? t('qa.risk.edit') : t('qa.risk.add')">
        <VCardText>
          <VTextField v-model="form.data.title" :label="t('qa.col.title')" class="mb-2" data-test="risk-title" />
          <VTextarea v-model="form.data.description" :label="t('qa.risk.description')" rows="2" class="mb-2" />
          <VRow dense align="center">
            <VCol cols="4"><VSelect v-model="form.data.probability" :items="scaleItems" :label="t('qa.risk.probability')" /></VCol>
            <VCol cols="4"><VSelect v-model="form.data.impact" :items="scaleItems" :label="t('qa.risk.impact')" /></VCol>
            <VCol cols="4"><VChip :color="RISK_LEVEL_COLOR[riskLevel(formScore)]" variant="flat" label>{{ t('qa.risk.scoreN', { n: formScore }) }}</VChip></VCol>
          </VRow>
          <VTextarea v-model="form.data.response" :label="t('qa.risk.response')" rows="2" class="my-2" />
          <VRow dense>
            <VCol cols="12" sm="4"><VSelect v-model="form.data.status" :items="statusItems" :label="t('qa.col.status')" /></VCol>
            <VCol cols="12" sm="4"><UserLookupField v-model="form.data.ownerId" :label="t('qa.col.owner')" :initial="form.owner" /></VCol>
            <VCol cols="12" sm="4"><VTextField v-model="form.data.dueDate" type="date" :label="t('qa.col.due')" /></VCol>
          </VRow>
          <VAlert v-if="form.error" type="error" variant="tonal" density="compact" class="mt-2">{{ form.error }}</VAlert>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="form.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :loading="form.saving" data-test="risk-save" @click="save">{{ t('common.save') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>
    <ConfirmDialog
      :model-value="!!convert"
      :title="t('qa.risk.toIssue')"
      :message="t('qa.risk.toIssueMessage', { no: convert?.riskNo ?? '' })"
      color="error"
      :confirm-text="t('qa.risk.toIssue')"
      @update:model-value="(v) => { if (!v) convert = null }"
      @confirm="toIssue"
    />
    <ConfirmDialog
      :model-value="!!removeTarget"
      :title="t('common.delete')"
      :message="t('qa.risk.removeMessage', { no: removeTarget?.riskNo ?? '' })"
      @update:model-value="(v) => { if (!v) removeTarget = null }"
      @confirm="remove"
    />
  </VContainer>
</template>
