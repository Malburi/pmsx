<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  createRequirementApi, deleteRequirementApi, getRequirementHistoryApi, getRequirementsApi, updateRequirementApi,
} from '@/api/traceApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import AppStatusChip from '@/components/common/AppStatusChip.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import ExcelExportButton from '@/components/common/ExcelExportButton.vue'
import ExcelImportDialog from '@/components/common/ExcelImportDialog.vue'
import TraceLinkEditor from '@/components/trace/TraceLinkEditor.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useElementWidth } from '@/composables/useElementWidth'
import { useAuthStore } from '@/stores/auth'
import type { ApiError } from '@/types/api'
import type { Page } from '@/types/common'
import type { FieldHistory, Requirement, RequirementPayload, ReqStatus } from '@/types/r2'
import { fitColumns } from '@/utils/columns'
import { formatDateTime } from '@/utils/format'
import { REQUIREMENT_EDIT_ROLES } from '@/utils/permission'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// SCR-DL-002 요구사항 (FR-DL-003·004)
const { t } = useI18n()
const auth = useAuthStore()

const STATUSES: ReqStatus[] = ['PROPOSED', 'ACCEPTED', 'IMPLEMENTED', 'VERIFIED', 'DROPPED']
const page = ref<Page<Requirement> | null>(null)
const options = ref({ page: 1, itemsPerPage: 20 })
const filter = reactive({ q: '', status: null as ReqStatus | null })
const loading = ref(false)
const error = ref<string | null>(null)
const selected = ref<Requirement | null>(null)
const tab = ref<'links' | 'history'>('links')
const history = ref<FieldHistory[]>([])
const excelOpen = ref(false)
const removeOpen = ref(false)
const form = reactive({
  open: false, editId: null as string | null, saving: false, errors: {} as Record<string, string>,
  data: { title: '', description: '', source: null, category: null, priority: 'M', status: 'PROPOSED' } as RequirementPayload,
})
let timer: ReturnType<typeof setTimeout> | null = null

// ─────────── 헬퍼 ───────────
const load = async () => {
  if (!projectId.value) return
  loading.value = true
  error.value = null
  try {
    page.value = await getRequirementsApi(projectId.value, {
      q: filter.q.trim() || undefined, status: filter.status ?? undefined, page: options.value.page - 1, size: options.value.itemsPerPage,
    })
    if (selected.value) selected.value = page.value.content.find((r) => r.reqId === selected.value?.reqId) ?? selected.value
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const { projectId } = useCurrentProject(() => load())

const onOptions = (o: { page: number; itemsPerPage: number }) => {
  options.value = { page: o.page, itemsPerPage: o.itemsPerPage }
  void load()
}

const pick = async (r: Requirement) => {
  selected.value = r
  history.value = await getRequirementHistoryApi(r.reqId).catch(() => [])
}

const onRowClick = (_e: Event, p: { item: Requirement }) => { void pick(p.item) }

const openForm = (r: Requirement | null) => {
  Object.assign(form, {
    open: true, editId: r?.reqId ?? null, saving: false, errors: {},
    data: r ? { title: r.title, description: r.description, source: r.source, category: r.category, priority: r.priority, status: r.status }
      : { title: '', description: '', source: null, category: null, priority: 'M', status: 'PROPOSED' },
  })
}

const saveForm = async () => {
  if (!projectId.value) return
  form.errors = form.data.title.trim() ? {} : { title: t('validation.required') }
  if (Object.keys(form.errors).length) return
  form.saving = true
  try {
    const saved = form.editId ? await updateRequirementApi(form.editId, form.data) : await createRequirementApi(projectId.value, form.data)
    form.open = false
    showSuccessToast('common.saved')
    await load()
    await pick(saved)
  } catch (e) {
    const err = e as ApiError
    form.errors = err.fields ?? {}
    if (!err.fields) showErrorToast('common.saveFailed')
  } finally {
    form.saving = false
  }
}

const remove = async () => {
  if (!selected.value) return
  try {
    await deleteRequirementApi(selected.value.reqId)
    selected.value = null
    removeOpen.value = false
    await load()
  } catch {
    showErrorToast('common.deleteFailed')
  }
}

// ─────────── computed ───────────
const canEdit = computed(() => auth.hasAnyRole(REQUIREMENT_EDIT_ROLES))
const statusItems = computed(() => STATUSES.map((s) => ({ title: t(`dl.reqStatus.${s}`), value: s })))
const priorityItems = computed(() => (['H', 'M', 'L'] as const).map((p) => ({ title: t(`qa.priority.${p}`), value: p })))
// 2단 화면에서 목록 자리가 좁으면 우선순위 낮은 열을 숨긴다 (마지막 열 잘림 방지)
const listCard = ref<HTMLElement | { $el: HTMLElement } | null>(null)
const listWidth = useElementWidth(listCard, 700)
const allHeaders = computed(() => [
  { title: t('dl.req.no'), key: 'reqNo', width: 100 },
  { title: t('dl.req.title'), key: 'title', minWidth: 220 },
  { title: t('dl.req.category'), key: 'category', width: 80, hideBelow: 600 },
  { title: t('dl.req.priority'), key: 'priority', width: 80, hideBelow: 520 },
  { title: t('dl.req.status'), key: 'status', width: 100 },
  { title: t('dl.col.updated'), key: 'updatedAt', width: 140, hideBelow: 700 },
])
const headers = computed(() => fitColumns(allHeaders.value, listWidth.value))

// ─────────── watch ───────────
watch(() => filter.status, () => { options.value.page = 1; void load() })
watch(() => filter.q, () => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => { options.value.page = 1; void load() }, 300)
})
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.deliverable.requirement')" :subtitle="t('dl.req.subtitle')">
      <template v-if="projectId">
        <ExcelExportButton dataset-id="requirements" :file-name="t('nav.deliverable.requirement')" :params="{ projectId }" />
        <VBtn v-if="canEdit" size="small" variant="tonal" prepend-icon="ri-file-excel-2-line" @click="excelOpen = true">{{ t('common.excel.import') }}</VBtn>
        <VBtn v-if="canEdit" size="small" color="primary" prepend-icon="ri-add-line" data-test="req-add" @click="openForm(null)">{{ t('dl.req.add') }}</VBtn>
      </template>
    </AppPageHeader>
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <VRow v-else>
      <VCol cols="12" lg="7">
        <VCard ref="listCard" border flat>
          <VCardText class="pb-0">
            <VRow dense>
              <VCol cols="12" sm="7"><VTextField v-model="filter.q" :label="t('common.search')" prepend-inner-icon="ri-search-line" clearable hide-details /></VCol>
              <VCol cols="12" sm="5"><VSelect v-model="filter.status" :items="statusItems" :label="t('dl.req.status')" clearable hide-details /></VCol>
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
            item-value="reqId"
            density="compact"
            hover
            :row-props="({ item }) => ({ class: item.reqId === selected?.reqId ? 'row-selected' : '', tabindex: 0 })"
            data-test="req-table"
            @update:options="onOptions"
            @click:row="onRowClick"
          >
            <template #item.reqNo="{ item }"><span class="font-mono">{{ item.reqNo }}</span></template>
            <template #item.priority="{ item }"><AppStatusChip :status="item.priority" prefix="qa.priority" size="x-small" /></template>
            <template #item.status="{ item }"><AppStatusChip :status="item.status" prefix="dl.reqStatus" size="x-small" /></template>
            <template #item.updatedAt="{ item }"><span class="text-caption">{{ formatDateTime(item.updatedAt) }}</span></template>
          </VDataTableServer>
        </VCard>
      </VCol>
      <VCol cols="12" lg="5">
        <VCard v-if="selected" border flat data-test="req-detail">
          <VCardTitle class="d-flex align-center ga-2">
            <span class="font-mono text-body-2">{{ selected.reqNo }}</span>
            <AppStatusChip :status="selected.status" prefix="dl.reqStatus" size="x-small" />
            <VSpacer />
            <template v-if="canEdit">
              <VBtn icon variant="text" size="small" :aria-label="t('common.edit')" @click="openForm(selected)"><VIcon icon="ri-edit-line" /></VBtn>
              <VBtn icon variant="text" size="small" :aria-label="t('common.delete')" @click="removeOpen = true"><VIcon icon="ri-delete-bin-line" /></VBtn>
            </template>
          </VCardTitle>
          <VCardText>
            <div class="text-subtitle-1 font-weight-medium mb-1">{{ selected.title }}</div>
            <p class="text-body-2 mb-2 pre">{{ selected.description }}</p>
            <div class="text-caption text-muted mb-3">
              {{ t('dl.req.category') }}: {{ selected.category ?? '-' }} · {{ t('dl.req.source') }}: {{ selected.source ?? '-' }} · {{ t('dl.req.priority') }}: {{ t(`qa.priority.${selected.priority}`) }}
            </div>
            <VTabs v-model="tab" density="compact" color="primary" class="border-b mb-3">
              <VTab value="links">{{ t('dl.trace.links') }}</VTab>
              <VTab value="history">{{ t('dl.req.history') }}</VTab>
            </VTabs>
            <TraceLinkEditor v-if="tab === 'links'" :id="selected.reqId" type="REQ" :readonly="!canEdit" />
            <VTable v-else density="compact">
              <tbody>
                <tr v-for="(h, i) in history" :key="i">
                  <td class="text-caption text-no-wrap">{{ formatDateTime(h.at) }}</td>
                  <td class="text-caption">{{ h.user }}</td>
                  <td class="text-caption">{{ t(`dl.req.field.${h.field}`) }}</td>
                  <td class="text-caption">{{ h.before ?? '-' }} → <b>{{ h.after ?? '-' }}</b></td>
                </tr>
                <tr v-if="!history.length"><td class="text-muted text-body-2">{{ t('common.noData') }}</td></tr>
              </tbody>
            </VTable>
          </VCardText>
        </VCard>
        <VAlert v-else type="info" variant="tonal">{{ t('dl.req.pick') }}</VAlert>
      </VCol>
    </VRow>

    <VDialog v-model="form.open" max-width="600">
      <VCard :title="form.editId ? t('dl.req.edit') : t('dl.req.add')">
        <VCardText>
          <VRow dense>
            <VCol cols="12"><VTextField v-model="form.data.title" :label="t('dl.req.title')" :error-messages="form.errors.title" data-test="req-title" /></VCol>
            <VCol cols="12"><VTextarea v-model="form.data.description" :label="t('dl.req.description')" rows="3" /></VCol>
            <VCol cols="6" sm="3"><VSelect v-model="form.data.priority" :items="priorityItems" :label="t('dl.req.priority')" /></VCol>
            <VCol cols="6" sm="3"><VSelect v-model="form.data.status" :items="statusItems" :label="t('dl.req.status')" /></VCol>
            <VCol cols="6" sm="3"><VTextField v-model="form.data.category" :label="t('dl.req.category')" /></VCol>
            <VCol cols="6" sm="3"><VTextField v-model="form.data.source" :label="t('dl.req.source')" /></VCol>
          </VRow>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="form.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :loading="form.saving" data-test="req-save" @click="saveForm">{{ t('common.save') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>
    <ExcelImportDialog v-if="projectId" v-model="excelOpen" dataset-id="requirements" :params="{ projectId }" :title="t('dl.req.import')" @committed="load" />
    <ConfirmDialog v-model="removeOpen" :title="t('common.delete')" :message="t('dl.req.removeMessage', { no: selected?.reqNo ?? '' })" @confirm="remove" />
  </VContainer>
</template>

<style scoped>
.pre {
  white-space: pre-wrap;
}

:deep(.row-selected) {
  background: rgba(var(--v-theme-primary), 0.08);
}
</style>
