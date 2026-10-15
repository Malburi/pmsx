<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { cabDecisionApi, createChangeApi, getChangeCalendarApi, getChangesApi, transitionChangeApi, updateChangeApi } from '@/api/smApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import AppStatusChip from '@/components/common/AppStatusChip.vue'
import ExcelExportButton from '@/components/common/ExcelExportButton.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useAuthStore } from '@/stores/auth'
import type { ApiError } from '@/types/api'
import type { Change, ChangePayload, ChangeStatus } from '@/types/r3'
import { focusableRow } from '@/utils/a11y'
import { today } from '@/utils/date'
import { formatDateTime } from '@/utils/format'
import { CAB_ROLES, SM_EDIT_ROLES } from '@/utils/permission'
import { CHANGE_TRANSITIONS, monthGrid, shiftMonth } from '@/utils/sm'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// SCR-SM-004 변경·배포 (FR-SM-008) — CAB 승인·배포 이력·캘린더
const { t } = useI18n()
const auth = useAuthStore()

const view = ref<'list' | 'calendar'>('list')
const list = ref<Change[]>([])
const calendar = ref<Change[]>([])
const month = ref(today().slice(0, 7))
const loading = ref(false)
const error = ref<string | null>(null)
const detail = ref<Change | null>(null)
const form = reactive({ open: false, editing: null as Change | null, data: {} as ChangePayload, commitsText: '', saving: false, error: null as string | null })
const cab = reactive({ open: false, decision: 'APPROVE' as 'APPROVE' | 'REJECT', comment: '', saving: false })
const busy = ref(false)

// ─────────── 헬퍼 ───────────
const load = async () => {
  if (!projectId.value) return
  loading.value = true
  error.value = null
  try {
    list.value = await getChangesApi(projectId.value)
    if (detail.value) detail.value = list.value.find((c) => c.changeId === detail.value?.changeId) ?? null
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const loadCalendar = async () => {
  if (!projectId.value) return
  const from = `${month.value}-01`
  const to = monthGridLast.value
  calendar.value = await getChangeCalendarApi(projectId.value, from, to).catch(() => [])
}
const { projectId } = useCurrentProject(() => { detail.value = null; void load(); void loadCalendar() })

const toLocal = (iso: string | null) => (iso ? formatDateTime(iso).replace(' ', 'T') : '')

const openForm = (c: Change | null) => {
  form.editing = c
  form.data = c
    ? { title: c.title, type: c.type, risk: c.risk, plan: c.plan, rollbackPlan: c.rollbackPlan, scheduledAt: toLocal(c.scheduledAt), srIds: [...c.srIds], screenIds: [...c.screenIds], commits: [...c.commits] }
    : { title: '', type: 'NORMAL', risk: 'M', plan: '', rollbackPlan: '', scheduledAt: '', srIds: [], screenIds: [], commits: [] }
  form.commitsText = form.data.commits.join('\n')
  Object.assign(form, { open: true, saving: false, error: null })
}

const save = async () => {
  if (!projectId.value) return
  if (!form.data.title.trim() || !form.data.plan.trim() || !form.data.rollbackPlan.trim()) { form.error = t('sm.change.requiredPlans'); return }
  form.saving = true
  try {
    const payload: ChangePayload = {
      ...form.data,
      scheduledAt: form.data.scheduledAt ? `${form.data.scheduledAt}:00+09:00` : null,
      commits: form.commitsText.split('\n').map((s) => s.trim()).filter(Boolean),
    }
    const saved = form.editing ? await updateChangeApi(form.editing.changeId, payload) : await createChangeApi(projectId.value, payload)
    form.open = false
    detail.value = saved
    showSuccessToast('common.saved')
    void load()
    void loadCalendar()
  } catch (e) {
    form.error = (e as ApiError).message ?? t('common.saveFailed')
  } finally {
    form.saving = false
  }
}

const decide = async () => {
  if (!detail.value) return
  cab.saving = true
  try {
    detail.value = await cabDecisionApi(detail.value.changeId, cab.decision, cab.comment.trim())
    cab.open = false
    showSuccessToast('sm.change.cabSaved')
    void load()
  } catch (e) {
    showErrorToast((e as ApiError).code === 'FORBIDDEN' ? 'common.forbiddenAction' : 'common.actionFailed')
  } finally {
    cab.saving = false
  }
}

const move = async (to: ChangeStatus) => {
  if (!detail.value) return
  busy.value = true
  try {
    detail.value = await transitionChangeApi(detail.value.changeId, to)
    void load()
    void loadCalendar()
  } catch {
    showErrorToast('common.actionFailed')
  } finally {
    busy.value = false
  }
}

const onRowClick = (_e: Event, p: { item: Change }) => { detail.value = p.item }
const eventsOn = (d: string) => calendar.value.filter((c) => (c.deployedAt ?? c.scheduledAt ?? '').slice(0, 10) === d)

// ─────────── computed ───────────
const canEdit = computed(() => auth.hasAnyRole(SM_EDIT_ROLES))
const canCab = computed(() => auth.hasAnyRole(CAB_ROLES))
const myCabDecided = computed(() => !!detail.value?.cab.some((c) => c.userId === auth.userId))
const nextStatuses = computed(() => (detail.value ? CHANGE_TRANSITIONS[detail.value.status] : []))
const cells = computed(() => monthGrid(month.value))
const monthGridLast = computed(() => [...cells.value].reverse().find((c): c is string => !!c) ?? `${month.value}-28`)
const headers = computed(() => [
  { title: t('sm.col.no'), key: 'changeNo', width: 100 },
  { title: t('sm.col.title'), key: 'title' },
  { title: t('sm.change.type'), key: 'type', width: 90 },
  { title: t('sm.change.risk'), key: 'risk', width: 70 },
  { title: t('sm.change.scheduledAt'), key: 'scheduledAt', width: 140 },
  { title: t('sm.col.status'), key: 'status', width: 100 },
])
const typeItems = computed(() => (['NORMAL', 'STANDARD', 'EMERGENCY'] as const).map((v) => ({ title: t(`sm.changeType.${v}`), value: v })))
const riskItems = computed(() => (['H', 'M', 'L'] as const).map((v) => ({ title: t(`qa.priority.${v}`), value: v })))
const weekdays = computed(() => [0, 1, 2, 3, 4, 5, 6].map((d) => t(`sm.weekday.${d}`)))

watch(month, () => { void loadCalendar() })
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.sm.change')" :subtitle="t('sm.change.subtitle')">
      <VBtnToggle v-model="view" density="compact" variant="outlined" mandatory divided>
        <VBtn value="list" size="small" prepend-icon="ri-list-check">{{ t('sm.change.listView') }}</VBtn>
        <VBtn value="calendar" size="small" prepend-icon="ri-calendar-line" data-test="change-calendar">{{ t('sm.change.calendarView') }}</VBtn>
      </VBtnToggle>
      <template v-if="projectId">
        <ExcelExportButton dataset-id="changes" :file-name="t('nav.sm.change')" :params="{ projectId }" />
        <VBtn v-if="canEdit" color="primary" size="small" prepend-icon="ri-add-line" data-test="change-add" @click="openForm(null)">{{ t('sm.change.add') }}</VBtn>
      </template>
    </AppPageHeader>
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <VRow v-else>
      <VCol cols="12" :lg="detail ? 7 : 12">
        <VCard v-if="view === 'list'" border flat>
          <VAlert v-if="error" type="error" variant="tonal" density="compact" class="ma-3">{{ error }}</VAlert>
          <VDataTable :headers="headers" :items="list" :loading="loading" item-value="changeId" density="compact" hover data-test="change-table" :row-props="focusableRow" @click:row="onRowClick">
            <template #item.changeNo="{ item }"><span class="font-mono">{{ item.changeNo }}</span></template>
            <template #item.type="{ item }"><AppStatusChip :status="item.type" prefix="sm.changeType" size="x-small" /></template>
            <template #item.risk="{ item }"><AppStatusChip :status="item.risk" prefix="qa.priority" size="x-small" /></template>
            <template #item.scheduledAt="{ item }"><span class="font-mono text-caption">{{ formatDateTime(item.scheduledAt) }}</span></template>
            <template #item.status="{ item }"><AppStatusChip :status="item.status" prefix="sm.changeStatus" size="x-small" /></template>
          </VDataTable>
        </VCard>
        <VCard v-else border flat>
          <VCardTitle class="d-flex align-center ga-2">
            <VBtn icon="ri-arrow-left-s-line" variant="text" size="small" :aria-label="t('sm.prevMonth')" @click="month = shiftMonth(month, -1)" />
            <span class="font-mono">{{ month }}</span>
            <VBtn icon="ri-arrow-right-s-line" variant="text" size="small" :aria-label="t('sm.nextMonth')" @click="month = shiftMonth(month, 1)" />
          </VCardTitle>
          <div class="cal-scroll">
            <div class="cal">
              <div v-for="w in weekdays" :key="w" class="cal-head">{{ w }}</div>
              <div v-for="(d, i) in cells" :key="i" :class="['cal-cell', { empty: !d, today: d === today() }]">
                <template v-if="d">
                  <div class="cal-day">{{ Number(d.slice(8)) }}</div>
                  <button v-for="c in eventsOn(d)" :key="c.changeId" type="button" :class="['cal-ev', `st-${c.status}`]" :title="c.title" @click="detail = c">
                    {{ c.changeNo }} {{ c.title }}
                  </button>
                </template>
              </div>
            </div>
          </div>
        </VCard>
      </VCol>
      <VCol v-if="detail" cols="12" lg="5">
        <VCard border flat data-test="change-detail">
          <VCardTitle class="d-flex flex-wrap align-center ga-2">
            <span class="font-mono text-body-2">{{ detail.changeNo }}</span>
            <AppStatusChip :status="detail.status" prefix="sm.changeStatus" size="x-small" />
            <AppStatusChip :status="detail.type" prefix="sm.changeType" size="x-small" />
            <VSpacer />
            <VBtn v-if="canEdit" icon variant="text" size="small" :aria-label="t('common.edit')" @click="openForm(detail)"><VIcon icon="ri-edit-line" /></VBtn>
            <VBtn icon variant="text" size="small" :aria-label="t('common.close')" @click="detail = null"><VIcon icon="ri-close-line" /></VBtn>
          </VCardTitle>
          <VCardText>
            <div class="text-subtitle-1 font-weight-medium">{{ detail.title }}</div>
            <div class="text-caption text-muted mb-2">
              {{ t('sm.change.scheduledAt') }} {{ formatDateTime(detail.scheduledAt) }} · {{ t('sm.change.deployedAt') }} {{ formatDateTime(detail.deployedAt) }}
            </div>
            <div class="text-caption text-muted">{{ t('sm.change.plan') }}</div>
            <p class="text-body-2 pre mb-2">{{ detail.plan }}</p>
            <div class="text-caption text-muted">{{ t('sm.change.rollbackPlan') }}</div>
            <p class="text-body-2 pre mb-3">{{ detail.rollbackPlan }}</p>
            <div v-if="detail.commits.length" class="mb-3">
              <div class="text-caption text-muted">{{ t('sm.change.commits') }}</div>
              <code v-for="c in detail.commits" :key="c" class="d-block text-caption">{{ c }}</code>
            </div>
            <div v-if="detail.srIds.length" class="text-caption mb-3">
              {{ t('sm.change.relatedSrs') }}
              <RouterLink v-for="id in detail.srIds" :key="id" :to="{ path: '/sm/srs', query: { id } }" class="ml-2">{{ id }}</RouterLink>
            </div>

            <div class="text-subtitle-2 mb-1">{{ t('sm.change.cab') }}</div>
            <VList density="compact" class="py-0 mb-2 border rounded-lg" data-test="change-cab">
              <VListItem v-for="(c, i) in detail.cab" :key="i">
                <template #prepend><VIcon :icon="c.decision === 'APPROVE' ? 'ri-checkbox-circle-fill' : 'ri-close-circle-fill'" :color="c.decision === 'APPROVE' ? 'success' : 'error'" size="18" class="mr-2" /></template>
                <VListItemTitle class="text-body-2">{{ c.name }} · {{ t(`sm.cabDecision.${c.decision}`) }}</VListItemTitle>
                <VListItemSubtitle>{{ formatDateTime(c.at) }}<template v-if="c.comment"> — {{ c.comment }}</template></VListItemSubtitle>
              </VListItem>
              <VListItem v-if="!detail.cab.length" :title="t('sm.change.noCab')" disabled />
            </VList>
            <div class="d-flex flex-wrap ga-2">
              <template v-if="canCab && detail.status === 'REQUESTED' && !myCabDecided">
                <VBtn size="small" color="success" variant="tonal" prepend-icon="ri-check-line" data-test="change-cab-approve" @click="Object.assign(cab, { open: true, decision: 'APPROVE', comment: '' })">{{ t('sm.cabDecision.APPROVE') }}</VBtn>
                <VBtn size="small" color="error" variant="text" @click="Object.assign(cab, { open: true, decision: 'REJECT', comment: '' })">{{ t('sm.cabDecision.REJECT') }}</VBtn>
              </template>
              <template v-if="canEdit">
                <VBtn v-for="s in nextStatuses" :key="s" size="small" variant="tonal" :loading="busy" :data-test="`change-to-${s}`" @click="move(s)">{{ t(`sm.changeAction.${s}`) }}</VBtn>
              </template>
            </div>
          </VCardText>
        </VCard>
      </VCol>
    </VRow>

    <VDialog v-model="form.open" max-width="680" scrollable>
      <VCard :title="form.editing ? t('sm.change.edit') : t('sm.change.add')">
        <VCardText>
          <VAlert v-if="form.error" type="error" variant="tonal" density="compact" class="mb-3">{{ form.error }}</VAlert>
          <VRow dense>
            <VCol cols="12"><VTextField v-model="form.data.title" :label="t('sm.col.title')" data-test="change-title" /></VCol>
            <VCol cols="6" sm="4"><VSelect v-model="form.data.type" :items="typeItems" :label="t('sm.change.type')" /></VCol>
            <VCol cols="6" sm="3"><VSelect v-model="form.data.risk" :items="riskItems" :label="t('sm.change.risk')" /></VCol>
            <VCol cols="12" sm="5"><VTextField :model-value="form.data.scheduledAt ?? ''" type="datetime-local" :label="t('sm.change.scheduledAt')" @update:model-value="form.data.scheduledAt = $event || null" /></VCol>
            <VCol cols="12"><VTextarea v-model="form.data.plan" :label="t('sm.change.plan')" rows="3" auto-grow data-test="change-plan" /></VCol>
            <VCol cols="12"><VTextarea v-model="form.data.rollbackPlan" :label="t('sm.change.rollbackPlan')" rows="2" auto-grow data-test="change-rollback" /></VCol>
            <VCol cols="12"><VTextarea v-model="form.commitsText" :label="t('sm.change.commits')" :hint="t('sm.change.commitsHint')" persistent-hint rows="2" auto-grow /></VCol>
          </VRow>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="form.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :loading="form.saving" data-test="change-save" @click="save">{{ t('common.save') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>

    <VDialog v-model="cab.open" max-width="420">
      <VCard :title="t(`sm.cabDecision.${cab.decision}`)">
        <VCardText><VTextarea v-model="cab.comment" :label="t('sm.change.cabComment')" rows="3" auto-grow /></VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="cab.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn :color="cab.decision === 'APPROVE' ? 'success' : 'error'" :loading="cab.saving" data-test="change-cab-ok" @click="decide">{{ t('common.confirm') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>
  </VContainer>
</template>

<style scoped>
.pre {
  white-space: pre-wrap;
}

.cal-scroll {
  overflow-x: auto;
}

.cal {
  display: grid;
  grid-template-columns: repeat(7, minmax(92px, 1fr));
  min-width: 644px;
  border-top: 1px solid rgb(var(--v-theme-line));
  border-left: 1px solid rgb(var(--v-theme-line));
}

.cal-head {
  padding: 4px 6px;
  font-size: 12px;
  font-weight: 600;
  color: rgb(var(--v-theme-muted));
  border-right: 1px solid rgb(var(--v-theme-line));
  border-bottom: 1px solid rgb(var(--v-theme-line));
}

.cal-cell {
  min-height: 92px;
  padding: 4px;
  border-right: 1px solid rgb(var(--v-theme-line));
  border-bottom: 1px solid rgb(var(--v-theme-line));
}

.cal-cell.empty {
  background: rgba(var(--v-theme-on-surface), 0.03);
}

.cal-cell.today .cal-day {
  color: rgb(var(--v-theme-primary));
  font-weight: 700;
}

.cal-day {
  font-size: 12px;
  margin-bottom: 2px;
}

.cal-ev {
  display: block;
  width: 100%;
  text-align: left;
  font-size: 11px;
  padding: 1px 4px;
  margin-bottom: 2px;
  border-radius: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  background: rgba(var(--v-theme-info), 0.14);
  color: rgb(var(--v-theme-on-surface));
}

.cal-ev.st-DEPLOYED,
.cal-ev.st-CLOSED {
  background: rgba(var(--v-theme-success), 0.16);
}

.cal-ev.st-FAILED,
.cal-ev.st-ROLLED_BACK {
  background: rgba(var(--v-theme-error), 0.16);
}

.cal-ev.st-REQUESTED {
  background: rgba(var(--v-theme-warning), 0.16);
}
</style>
