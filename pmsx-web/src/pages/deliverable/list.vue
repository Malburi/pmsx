<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { getWbsApi } from '@/api/scheduleApi'
import {
  createProjectDeliverableApi, deleteProjectDeliverableApi, deliverableActionApi, getDeliverableHistoryApi, getProjectDeliverablesApi,
  updateProjectDeliverableApi,
} from '@/api/traceApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import AppStatusChip from '@/components/common/AppStatusChip.vue'
import AttachmentPanel from '@/components/common/AttachmentPanel.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import ExcelExportButton from '@/components/common/ExcelExportButton.vue'
import UserLookupField from '@/components/common/UserLookupField.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useElementWidth } from '@/composables/useElementWidth'
import { useAuthStore } from '@/stores/auth'
import type { ApiError } from '@/types/api'
import type { Deliverable, DeliverablePayload, HistoryEntry, WbsItem } from '@/types/r2'
import { fitColumns } from '@/utils/columns'
import { today } from '@/utils/date'
import { formatDateTime } from '@/utils/format'
import { DELIVERABLE_APPROVE_ROLES, DELIVERABLE_EDIT_ROLES } from '@/utils/permission'
import { showErrorToast, showSuccessToast } from '@/utils/toast'
import { hasChildren } from '@/utils/wbs'

// SCR-DL-001 산출물 대장 (FR-DL-001·002)
const { t } = useI18n()
const auth = useAuthStore()

const PHASES = ['분석', '설계', '구현', '시험', '이행']
const STATUSES = ['DRAFT', 'REVIEW', 'APPROVED', 'REJECTED'] as const
const list = ref<Deliverable[]>([])
const wbs = ref<WbsItem[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const filter = reactive({ phase: null as string | null, status: null as string | null })
const form = reactive({
  open: false, editId: null as string | null, saving: false, error: null as string | null,
  data: { phase: '분석', name: '', ownerId: null, dueDate: null, customerApproval: false, wbsId: null } as DeliverablePayload,
})
const detail = reactive({ open: false, item: null as Deliverable | null, tab: 'files' as 'files' | 'history', history: [] as HistoryEntry[] })
const action = reactive({ open: false, kind: 'approve' as 'request-review' | 'approve' | 'reject', target: null as Deliverable | null, comment: '', saving: false })
const removeTarget = ref<Deliverable | null>(null)

// ─────────── 헬퍼 ───────────
const load = async (projectId: string) => {
  loading.value = true
  error.value = null
  try {
    list.value = await getProjectDeliverablesApi(projectId, { phase: filter.phase ?? undefined, status: filter.status ?? undefined })
    if (!wbs.value.length) wbs.value = await getWbsApi(projectId).catch(() => [])
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const { projectId } = useCurrentProject(load)
const reload = () => { if (projectId.value) void load(projectId.value) }

const openForm = (d: Deliverable | null) => {
  Object.assign(form, {
    open: true, editId: d?.deliverableId ?? null, saving: false, error: null,
    data: d
      ? { phase: d.phase, name: d.name, ownerId: d.owner?.userId ?? null, dueDate: d.dueDate, customerApproval: d.customerApproval, wbsId: d.wbsId }
      : { phase: filter.phase ?? '분석', name: '', ownerId: auth.userId, dueDate: null, customerApproval: false, wbsId: null },
  })
}

const saveForm = async () => {
  if (!projectId.value || !form.data.name.trim()) { form.error = t('validation.required'); return }
  form.saving = true
  form.error = null
  try {
    if (form.editId) await updateProjectDeliverableApi(form.editId, form.data)
    else await createProjectDeliverableApi(projectId.value, form.data)
    form.open = false
    showSuccessToast('common.saved')
    reload()
  } catch (e) {
    form.error = (e as ApiError).message ?? t('common.saveFailed')
  } finally {
    form.saving = false
  }
}

const openDetail = async (d: Deliverable, tab: 'files' | 'history' = 'files') => {
  Object.assign(detail, { open: true, item: d, tab, history: [] })
  detail.history = await getDeliverableHistoryApi(d.deliverableId).catch(() => [])
}

const openAction = (kind: typeof action.kind, d: Deliverable) => Object.assign(action, { open: true, kind, target: d, comment: '', saving: false })

const runAction = async () => {
  if (!action.target) return
  if (action.kind === 'reject' && !action.comment.trim()) return
  action.saving = true
  try {
    await deliverableActionApi(action.target.deliverableId, action.kind, action.comment.trim() || undefined)
    action.open = false
    showSuccessToast(`dl.action.done.${action.kind}`)
    reload()
  } catch (e) {
    const err = e as ApiError
    showErrorToast(err.code === 'FORBIDDEN' ? 'common.forbiddenAction' : 'common.actionFailed')
  } finally {
    action.saving = false
  }
}

const remove = async () => {
  if (!removeTarget.value) return
  try {
    await deleteProjectDeliverableApi(removeTarget.value.deliverableId)
    removeTarget.value = null
    reload()
  } catch {
    showErrorToast('common.deleteFailed')
  }
}

const canApprove = (d: Deliverable) => d.status === 'REVIEW' && (auth.hasAnyRole(['PM']) || (d.customerApproval && auth.hasAnyRole(['CUSTOMER'])))
const overdue = (d: Deliverable) => !!d.dueDate && d.status !== 'APPROVED' && d.dueDate < today()

// ─────────── computed ───────────
const canEdit = computed(() => auth.hasAnyRole(DELIVERABLE_EDIT_ROLES))
const canApproveAny = computed(() => auth.hasAnyRole(DELIVERABLE_APPROVE_ROLES))
const phaseItems = PHASES.map((p) => ({ title: p, value: p }))
const statusItems = computed(() => STATUSES.map((s) => ({ title: t(`dl.status.${s}`), value: s })))
const wbsItems = computed(() => wbs.value.filter((w) => !hasChildren(wbs.value, w.wbsId)).map((w) => ({ title: `${w.wbsNo} ${w.name}`, value: w.wbsId })))
const counts = computed(() => STATUSES.map((s) => ({ s, n: list.value.filter((d) => d.status === s).length })))
// 화면이 좁으면 덜 중요한 열을 숨긴다
const listCard = ref<HTMLElement | { $el: HTMLElement } | null>(null)
const listWidth = useElementWidth(listCard, 1200)
const allHeaders = computed(() => [
  { title: t('dl.col.phase'), key: 'phase', width: 70 },
  { title: t('dl.col.name'), key: 'name', minWidth: 200 },
  { title: t('dl.col.owner'), key: 'owner', width: 90, hideBelow: 780 },
  { title: t('dl.col.due'), key: 'dueDate', width: 110 },
  { title: t('dl.col.status'), key: 'status', width: 90 },
  { title: t('dl.col.files'), key: 'fileCount', width: 70, align: 'end' as const, hideBelow: 900 },
  { title: t('dl.col.updated'), key: 'updatedAt', width: 140, hideBelow: 1000 },
  { title: '', key: 'actions', sortable: false, width: 220, align: 'end' as const },
])
const headers = computed(() => fitColumns(allHeaders.value, listWidth.value))

// ─────────── watch ───────────
watch(() => [filter.phase, filter.status], reload)
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.deliverable.list')" :subtitle="t('dl.subtitle')">
      <ExcelExportButton v-if="projectId" dataset-id="deliverables" :file-name="t('nav.deliverable.list')" :params="{ projectId }" />
      <VBtn v-if="canEdit && projectId" color="primary" size="small" prepend-icon="ri-add-line" data-test="dl-add" @click="openForm(null)">{{ t('dl.add') }}</VBtn>
    </AppPageHeader>
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <template v-else>
      <div class="d-flex flex-wrap ga-2 mb-3">
        <VChip
          v-for="c in counts"
          :key="c.s"
          :color="filter.status === c.s ? 'primary' : undefined"
          :variant="filter.status === c.s ? 'flat' : 'tonal'"
          label
          @click="filter.status = filter.status === c.s ? null : c.s"
        >{{ t(`dl.status.${c.s}`) }} {{ c.n }}</VChip>
      </div>
      <VCard ref="listCard" border flat>
        <VCardText class="pb-0">
          <VRow dense>
            <VCol cols="12" sm="4" md="3"><VSelect v-model="filter.phase" :items="phaseItems" :label="t('dl.col.phase')" clearable hide-details /></VCol>
            <VCol cols="12" sm="4" md="3"><VSelect v-model="filter.status" :items="statusItems" :label="t('dl.col.status')" clearable hide-details /></VCol>
          </VRow>
        </VCardText>
        <VAlert v-if="error" type="error" variant="tonal" density="compact" class="ma-3">{{ error }}</VAlert>
        <VDataTable :headers="headers" :items="list" :loading="loading" item-value="deliverableId" density="compact" :items-per-page="50" data-test="dl-table">
          <template #item.name="{ item }">
            <a href="#" class="font-weight-medium text-decoration-none" @click.prevent="openDetail(item)">{{ item.name }}</a>
            <VIcon v-if="item.customerApproval" icon="ri-user-star-line" size="14" class="ml-1" color="info" :title="t('dl.customerApproval')" />
          </template>
          <template #item.owner="{ item }">{{ item.owner?.name ?? '-' }}</template>
          <template #item.dueDate="{ item }"><span :class="['font-mono text-caption', { 'text-error font-weight-bold': overdue(item) }]">{{ item.dueDate ?? '-' }}</span></template>
          <template #item.status="{ item }"><AppStatusChip :status="item.status" prefix="dl.status" /></template>
          <template #item.updatedAt="{ item }"><span class="text-caption">{{ formatDateTime(item.updatedAt) }}</span></template>
          <template #item.actions="{ item }">
            <div class="d-flex justify-end ga-1">
              <VBtn v-if="canEdit && (item.status === 'DRAFT' || item.status === 'REJECTED')" size="x-small" variant="tonal" color="info" @click="openAction('request-review', item)">{{ t('dl.action.request-review') }}</VBtn>
              <template v-if="canApproveAny && canApprove(item)">
                <VBtn size="x-small" variant="tonal" color="success" :data-test="`dl-approve-${item.deliverableId}`" @click="openAction('approve', item)">{{ t('dl.action.approve') }}</VBtn>
                <VBtn size="x-small" variant="tonal" color="error" @click="openAction('reject', item)">{{ t('dl.action.reject') }}</VBtn>
              </template>
              <VBtn icon variant="text" size="x-small" :aria-label="t('dl.history')" @click="openDetail(item, 'history')"><VIcon icon="ri-history-line" /></VBtn>
              <VBtn v-if="canEdit" icon variant="text" size="x-small" :aria-label="t('common.edit')" @click="openForm(item)"><VIcon icon="ri-edit-line" /></VBtn>
              <VBtn v-if="canEdit && item.status !== 'APPROVED'" icon variant="text" size="x-small" :aria-label="t('common.delete')" @click="removeTarget = item"><VIcon icon="ri-delete-bin-line" /></VBtn>
            </div>
          </template>
        </VDataTable>
      </VCard>
    </template>

    <VDialog v-model="form.open" max-width="520">
      <VCard :title="form.editId ? t('dl.edit') : t('dl.add')">
        <VCardText>
          <VRow dense>
            <VCol cols="12" sm="4"><VSelect v-model="form.data.phase" :items="phaseItems" :label="t('dl.col.phase')" /></VCol>
            <VCol cols="12" sm="8"><VTextField v-model="form.data.name" :label="t('dl.col.name')" data-test="dl-name" /></VCol>
            <VCol cols="12" sm="6"><UserLookupField v-model="form.data.ownerId" :label="t('dl.col.owner')" /></VCol>
            <VCol cols="12" sm="6"><VTextField v-model="form.data.dueDate" type="date" :label="t('dl.col.due')" /></VCol>
            <VCol cols="12"><VAutocomplete v-model="form.data.wbsId" :items="wbsItems" :label="t('dl.wbs')" :hint="t('dl.wbsHint')" persistent-hint clearable /></VCol>
            <VCol cols="12"><VCheckbox v-model="form.data.customerApproval" :label="t('dl.customerApproval')" density="compact" hide-details /></VCol>
          </VRow>
          <VAlert v-if="form.error" type="error" variant="tonal" density="compact" class="mt-2">{{ form.error }}</VAlert>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="form.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :loading="form.saving" data-test="dl-save" @click="saveForm">{{ t('common.save') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>

    <VDialog v-model="detail.open" max-width="720">
      <VCard v-if="detail.item" :title="detail.item.name" :subtitle="`${detail.item.phase} · ${detail.item.owner?.name ?? '-'}`">
        <VTabs v-model="detail.tab" density="compact" color="primary" class="border-b">
          <VTab value="files">{{ t('dl.files') }}</VTab>
          <VTab value="history">{{ t('dl.history') }}</VTab>
        </VTabs>
        <VCardText>
          <AttachmentPanel v-if="detail.tab === 'files'" target-type="DL_DELIVERABLE" :target-id="detail.item.deliverableId" :readonly="!canEdit" />
          <VTimeline v-else density="compact" side="end" truncate-line="both">
            <VTimelineItem v-for="(h, i) in detail.history" :key="i" size="x-small" :dot-color="h.action === 'APPROVE' ? 'success' : h.action === 'REJECT' ? 'error' : 'info'">
              <div class="text-body-2"><b>{{ t(`dl.historyAction.${h.action}`) }}</b> · {{ h.user }}</div>
              <div class="text-caption text-muted">{{ formatDateTime(h.at) }}</div>
              <div v-if="h.comment" class="text-body-2 mt-1">{{ h.comment }}</div>
            </VTimelineItem>
            <div v-if="!detail.history.length" class="text-body-2 text-muted">{{ t('common.noData') }}</div>
          </VTimeline>
        </VCardText>
        <VCardActions><VSpacer /><VBtn variant="text" @click="detail.open = false">{{ t('common.close') }}</VBtn></VCardActions>
      </VCard>
    </VDialog>

    <VDialog v-model="action.open" max-width="440">
      <VCard :title="t(`dl.action.${action.kind}`)">
        <VCardText>
          <div class="text-body-2 mb-3">{{ action.target?.name }}</div>
          <VTextarea
            v-model="action.comment"
            :label="action.kind === 'reject' ? t('dl.rejectReason') : t('dl.comment')"
            rows="3"
            :error-messages="action.kind === 'reject' && !action.comment.trim() ? t('validation.required') : undefined"
          />
          <div v-if="action.kind === 'approve' && action.target?.wbsId" class="text-caption text-muted">{{ t('dl.approveHint') }}</div>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="action.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn :color="action.kind === 'reject' ? 'error' : 'primary'" :loading="action.saving" data-test="dl-action-run" @click="runAction">{{ t(`dl.action.${action.kind}`) }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>
    <ConfirmDialog
      :model-value="!!removeTarget"
      :title="t('common.delete')"
      :message="t('dl.removeMessage', { name: removeTarget?.name ?? '' })"
      @update:model-value="(v) => { if (!v) removeTarget = null }"
      @confirm="remove"
    />
  </VContainer>
</template>
