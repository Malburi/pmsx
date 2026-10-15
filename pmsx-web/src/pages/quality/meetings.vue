<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  createDecisionApi, createMeetingApi, deleteDecisionApi, deleteMeetingApi, getActionItemsApi, getDecisionsApi, getMeetingsApi,
  patchActionItemApi, updateDecisionApi, updateMeetingApi,
} from '@/api/qualityApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import MarkdownEditor from '@/components/common/MarkdownEditor.vue'
import MarkdownView from '@/components/common/MarkdownView.vue'
import UserLookupField from '@/components/common/UserLookupField.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useAuthStore } from '@/stores/auth'
import type { ApiError, UserRef } from '@/types/api'
import type { ActionItem, Decision, Meeting, MeetingPayload } from '@/types/r2'
import { today } from '@/utils/date'
import { formatDateTime } from '@/utils/format'
import { ISSUE_EDIT_ROLES } from '@/utils/permission'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// SCR-QA-006 회의록·액션아이템·의사결정 로그 (FR-QA-003·010)
type ActionRow = MeetingPayload['actionItems'][number] & { owner?: UserRef | null }

const { t } = useI18n()
const auth = useAuthStore()

const tab = ref<'meetings' | 'actions' | 'decisions'>('meetings')
const meetings = ref<Meeting[]>([])
const selectedId = ref<string | null>(null)
const actions = ref<(ActionItem & { meetingId?: string; meetingNo?: string })[]>([])
const decisions = ref<Decision[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const form = reactive({
  open: false, editId: null as string | null, saving: false,
  data: { title: '', at: '', attendees: '', content: '', actionItems: [] as ActionRow[] },
})
const dec = reactive({ open: false, editId: null as string | null, data: { title: '', content: '', rationale: '', decidedBy: '', decidedAt: today() } as Omit<Decision, 'decisionId'> })
const removeMeeting = ref(false)

// ─────────── 헬퍼 ───────────
const load = async (projectId: string) => {
  loading.value = true
  error.value = null
  try {
    const [m, a, d] = await Promise.all([getMeetingsApi(projectId), getActionItemsApi(projectId, true), getDecisionsApi(projectId)])
    meetings.value = m
    actions.value = a
    decisions.value = d
    if (!selectedId.value || !m.some((x) => x.meetingId === selectedId.value)) selectedId.value = m[0]?.meetingId ?? null
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const { projectId } = useCurrentProject(load)
const reload = () => { if (projectId.value) void load(projectId.value) }

const toLocalInput = (iso: string) => (iso ? iso.slice(0, 16) : '')

const openForm = (m: Meeting | null) => {
  Object.assign(form, {
    open: true, editId: m?.meetingId ?? null, saving: false,
    data: m
      ? { title: m.title, at: toLocalInput(m.at), attendees: m.attendees, content: m.content, actionItems: m.actionItems.map((a) => ({ itemId: a.itemId, content: a.content, ownerId: a.owner?.userId ?? null, owner: a.owner, dueDate: a.dueDate, done: a.done })) }
      : { title: '', at: `${today()}T10:00`, attendees: '', content: t('qa.meeting.template'), actionItems: [] },
  })
}

const saveForm = async () => {
  if (!projectId.value || !form.data.title.trim()) return
  form.saving = true
  try {
    const payload: MeetingPayload = {
      ...form.data,
      at: new Date(form.data.at).toISOString(),
      actionItems: form.data.actionItems.filter((a) => a.content.trim()).map(({ owner: _o, ...a }) => a),
    }
    const saved = form.editId ? await updateMeetingApi(form.editId, payload) : await createMeetingApi(projectId.value, payload)
    form.open = false
    selectedId.value = saved.meetingId
    showSuccessToast('common.saved')
    reload()
  } catch {
    showErrorToast('common.saveFailed')
  } finally {
    form.saving = false
  }
}

const toggleAction = async (a: ActionItem) => {
  try {
    await patchActionItemApi(a.itemId, { done: !a.done })
    reload()
  } catch {
    showErrorToast('common.saveFailed')
  }
}

const doRemoveMeeting = async () => {
  if (!selected.value) return
  try {
    await deleteMeetingApi(selected.value.meetingId)
    removeMeeting.value = false
    selectedId.value = null
    reload()
  } catch {
    showErrorToast('common.deleteFailed')
  }
}

const openDec = (d: Decision | null) => {
  Object.assign(dec, { open: true, editId: d?.decisionId ?? null, data: d ? { title: d.title, content: d.content, rationale: d.rationale, decidedBy: d.decidedBy, decidedAt: d.decidedAt } : { title: '', content: '', rationale: '', decidedBy: '', decidedAt: today() } })
}

const saveDec = async () => {
  if (!projectId.value || !dec.data.title.trim()) return
  try {
    if (dec.editId) await updateDecisionApi(dec.editId, dec.data)
    else await createDecisionApi(projectId.value, dec.data)
    dec.open = false
    reload()
  } catch {
    showErrorToast('common.saveFailed')
  }
}

const removeDec = async (d: Decision) => {
  try {
    await deleteDecisionApi(d.decisionId)
    reload()
  } catch {
    showErrorToast('common.deleteFailed')
  }
}

const overdue = (a: { dueDate: string | null; done: boolean }) => !a.done && !!a.dueDate && a.dueDate < today()

// ─────────── computed ───────────
const canEdit = computed(() => auth.hasAnyRole(ISSUE_EDIT_ROLES))
const selected = computed(() => meetings.value.find((m) => m.meetingId === selectedId.value) ?? null)
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.quality.meeting')" :subtitle="t('qa.meeting.subtitle')">
      <template v-if="canEdit && projectId">
        <VBtn v-if="tab === 'decisions'" color="primary" size="small" prepend-icon="ri-add-line" @click="openDec(null)">{{ t('qa.decision.add') }}</VBtn>
        <VBtn v-else color="primary" size="small" prepend-icon="ri-add-line" data-test="meeting-add" @click="openForm(null)">{{ t('qa.meeting.add') }}</VBtn>
      </template>
    </AppPageHeader>
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <VCard v-else border flat>
      <VTabs v-model="tab" density="compact" color="primary" class="border-b">
        <VTab value="meetings">{{ t('qa.meeting.tab') }}</VTab>
        <VTab value="actions">{{ t('qa.meeting.tabActions') }} <VBadge v-if="actions.length" :content="actions.length" color="warning" inline /></VTab>
        <VTab value="decisions">{{ t('qa.decision.tab') }}</VTab>
      </VTabs>
      <VAlert v-if="error" type="error" variant="tonal" density="compact" class="ma-3">{{ error }}</VAlert>
      <VProgressLinear v-if="loading" indeterminate color="primary" />

      <VRow v-show="tab === 'meetings'" no-gutters>
        <VCol cols="12" md="4" class="border-e">
          <VList density="compact">
            <VListItem
              v-for="m in meetings"
              :key="m.meetingId"
              :active="m.meetingId === selectedId"
              :title="m.title"
              :subtitle="`${m.meetingNo} · ${formatDateTime(m.at)}`"
              @click="selectedId = m.meetingId"
            >
              <template #append>
                <VChip v-if="m.actionItems.some((a) => !a.done)" size="x-small" color="warning" variant="tonal">{{ m.actionItems.filter((a) => !a.done).length }}</VChip>
              </template>
            </VListItem>
            <VListItem v-if="!meetings.length" :title="t('qa.meeting.none')" disabled />
          </VList>
        </VCol>
        <VCol cols="12" md="8">
          <div v-if="selected" class="pa-4" data-test="meeting-detail">
            <div class="d-flex align-center ga-2">
              <h2 class="text-h6">{{ selected.title }}</h2>
              <VSpacer />
              <template v-if="canEdit">
                <VBtn icon variant="text" size="small" :aria-label="t('common.edit')" @click="openForm(selected)"><VIcon icon="ri-edit-line" /></VBtn>
                <VBtn icon variant="text" size="small" :aria-label="t('common.delete')" @click="removeMeeting = true"><VIcon icon="ri-delete-bin-line" /></VBtn>
              </template>
            </div>
            <div class="text-caption text-muted mb-3">{{ selected.meetingNo }} · {{ formatDateTime(selected.at) }} · {{ t('qa.meeting.attendees') }}: {{ selected.attendees }}</div>
            <MarkdownView :source="selected.content" class="mb-4" />
            <div class="text-subtitle-2 mb-1">{{ t('qa.meeting.actionItems') }}</div>
            <VList density="compact" class="py-0">
              <VListItem v-for="a in selected.actionItems" :key="a.itemId" class="px-0">
                <template #prepend>
                  <VCheckboxBtn :model-value="a.done" :disabled="!canEdit" :aria-label="a.content" @update:model-value="toggleAction(a)" />
                </template>
                <VListItemTitle :class="{ 'text-decoration-line-through text-muted': a.done }">{{ a.content }}</VListItemTitle>
                <VListItemSubtitle :class="{ 'text-error': overdue(a) }">{{ a.owner?.name ?? '-' }} · {{ a.dueDate ?? '-' }}</VListItemSubtitle>
              </VListItem>
              <div v-if="!selected.actionItems.length" class="text-body-2 text-muted">{{ t('common.noData') }}</div>
            </VList>
          </div>
          <div v-else class="pa-6 text-body-2 text-muted">{{ t('qa.meeting.pick') }}</div>
        </VCol>
      </VRow>

      <div v-show="tab === 'actions'" class="pa-4">
        <VTable density="compact">
          <thead><tr><th /><th>{{ t('qa.meeting.action') }}</th><th>{{ t('qa.col.owner') }}</th><th>{{ t('qa.col.due') }}</th><th>{{ t('qa.meeting.source') }}</th></tr></thead>
          <tbody>
            <tr v-for="a in actions" :key="a.itemId">
              <td><VCheckboxBtn :model-value="a.done" :disabled="!canEdit" :aria-label="a.content" @update:model-value="toggleAction(a)" /></td>
              <td class="text-body-2">{{ a.content }}</td>
              <td>{{ a.owner?.name ?? '-' }}</td>
              <td :class="['font-mono text-caption', { 'text-error font-weight-bold': overdue(a) }]">{{ a.dueDate ?? '-' }}</td>
              <td><a href="#" class="text-caption" @click.prevent="tab = 'meetings'; selectedId = a.meetingId ?? null">{{ a.meetingNo }}</a></td>
            </tr>
            <tr v-if="!actions.length"><td colspan="5" class="text-center text-muted pa-6">{{ t('qa.meeting.allDone') }}</td></tr>
          </tbody>
        </VTable>
      </div>

      <div v-show="tab === 'decisions'" class="pa-4">
        <VTable density="compact">
          <thead><tr><th>{{ t('qa.decision.date') }}</th><th>{{ t('qa.decision.title') }}</th><th>{{ t('qa.decision.content') }}</th><th>{{ t('qa.decision.rationale') }}</th><th>{{ t('qa.decision.by') }}</th><th /></tr></thead>
          <tbody>
            <tr v-for="d in decisions" :key="d.decisionId">
              <td class="font-mono text-caption text-no-wrap">{{ d.decidedAt }}</td>
              <td class="font-weight-medium">{{ d.title }}</td>
              <td class="text-body-2">{{ d.content }}</td>
              <td class="text-caption">{{ d.rationale }}</td>
              <td>{{ d.decidedBy }}</td>
              <td class="text-no-wrap">
                <template v-if="canEdit">
                  <VBtn icon variant="text" size="x-small" :aria-label="t('common.edit')" @click="openDec(d)"><VIcon icon="ri-edit-line" /></VBtn>
                  <VBtn icon variant="text" size="x-small" :aria-label="t('common.delete')" @click="removeDec(d)"><VIcon icon="ri-delete-bin-line" /></VBtn>
                </template>
              </td>
            </tr>
            <tr v-if="!decisions.length"><td colspan="6" class="text-center text-muted pa-6">{{ t('common.noData') }}</td></tr>
          </tbody>
        </VTable>
      </div>
    </VCard>

    <VDialog v-model="form.open" max-width="760" scrollable>
      <VCard :title="form.editId ? t('qa.meeting.edit') : t('qa.meeting.add')">
        <VCardText>
          <VRow dense>
            <VCol cols="12" sm="7"><VTextField v-model="form.data.title" :label="t('qa.meeting.titleLabel')" data-test="meeting-title" /></VCol>
            <VCol cols="12" sm="5"><VTextField v-model="form.data.at" type="datetime-local" :label="t('qa.meeting.at')" /></VCol>
            <VCol cols="12"><VTextField v-model="form.data.attendees" :label="t('qa.meeting.attendees')" /></VCol>
            <VCol cols="12"><MarkdownEditor v-model="form.data.content" :rows="8" /></VCol>
          </VRow>
          <div class="d-flex align-center mt-3 mb-1">
            <span class="text-subtitle-2">{{ t('qa.meeting.actionItems') }}</span>
            <VSpacer />
            <VBtn size="x-small" variant="tonal" prepend-icon="ri-add-line" @click="form.data.actionItems.push({ content: '', ownerId: null, dueDate: null, done: false })">{{ t('common.add') }}</VBtn>
          </div>
          <VRow v-for="(a, i) in form.data.actionItems" :key="i" dense align="center">
            <VCol cols="12" sm="6"><VTextField v-model="a.content" :label="t('qa.meeting.action')" hide-details /></VCol>
            <VCol cols="6" sm="3"><UserLookupField v-model="a.ownerId" :label="t('qa.col.owner')" :initial="a.owner ?? null" /></VCol>
            <VCol cols="5" sm="2"><VTextField v-model="a.dueDate" type="date" :label="t('qa.col.due')" hide-details /></VCol>
            <VCol cols="1"><VBtn icon variant="text" size="x-small" :aria-label="t('common.delete')" @click="form.data.actionItems.splice(i, 1)"><VIcon icon="ri-close-line" /></VBtn></VCol>
          </VRow>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="form.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :loading="form.saving" :disabled="!form.data.title.trim()" data-test="meeting-save" @click="saveForm">{{ t('common.save') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>

    <VDialog v-model="dec.open" max-width="520">
      <VCard :title="dec.editId ? t('qa.decision.edit') : t('qa.decision.add')">
        <VCardText>
          <VTextField v-model="dec.data.title" :label="t('qa.decision.title')" class="mb-2" />
          <VTextarea v-model="dec.data.content" :label="t('qa.decision.content')" rows="2" class="mb-2" />
          <VTextarea v-model="dec.data.rationale" :label="t('qa.decision.rationale')" rows="2" class="mb-2" />
          <VRow dense>
            <VCol cols="6"><VTextField v-model="dec.data.decidedBy" :label="t('qa.decision.by')" /></VCol>
            <VCol cols="6"><VTextField v-model="dec.data.decidedAt" type="date" :label="t('qa.decision.date')" /></VCol>
          </VRow>
        </VCardText>
        <VCardActions><VSpacer /><VBtn variant="text" @click="dec.open = false">{{ t('common.cancel') }}</VBtn><VBtn color="primary" :disabled="!dec.data.title.trim()" @click="saveDec">{{ t('common.save') }}</VBtn></VCardActions>
      </VCard>
    </VDialog>
    <ConfirmDialog v-model="removeMeeting" :title="t('common.delete')" :message="t('qa.meeting.removeMessage', { no: selected?.meetingNo ?? '' })" @confirm="doRemoveMeeting" />
  </VContainer>
</template>
