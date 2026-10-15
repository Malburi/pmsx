<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { checkRoutineApi, createRoutineJobApi, deleteRoutineJobApi, getRoutineJobsApi, getRoutineOccurrencesApi, updateRoutineJobApi } from '@/api/smApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import UserLookupField from '@/components/common/UserLookupField.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useAuthStore } from '@/stores/auth'
import type { ApiError } from '@/types/api'
import type { RoutineJob, RoutineJobPayload, RoutineOccurrence } from '@/types/r3'
import { today } from '@/utils/date'
import { SM_EDIT_ROLES } from '@/utils/permission'
import { monthGrid, shiftMonth } from '@/utils/sm'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// SCR-SM-005 정기 작업 캘린더 (FR-SM-009) — 정기 점검·백업 확인·월마감 체크
const { t } = useI18n()
const auth = useAuthStore()

const month = ref(today().slice(0, 7))
const jobs = ref<RoutineJob[]>([])
const occ = ref<RoutineOccurrence[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const check = reactive({ open: false, item: null as RoutineOccurrence | null, done: true, note: '', saving: false })
const form = reactive({ open: false, editing: null as RoutineJob | null, data: {} as RoutineJobPayload, checklistText: '', saving: false, error: null as string | null })
const removeTarget = ref<RoutineJob | null>(null)

// ─────────── 헬퍼 ───────────
const load = async () => {
  if (!projectId.value) return
  loading.value = true
  error.value = null
  try {
    const cells = monthGrid(month.value).filter((c): c is string => !!c)
    const [j, o] = await Promise.all([
      getRoutineJobsApi(projectId.value),
      getRoutineOccurrencesApi(projectId.value, cells[0], cells[cells.length - 1]),
    ])
    jobs.value = j
    occ.value = o
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const { projectId } = useCurrentProject(() => load())

const openCheck = (o: RoutineOccurrence) => {
  if (!canEdit.value) return
  Object.assign(check, { open: true, item: o, done: !o.done, note: o.note ?? '', saving: false })
}

const saveCheck = async () => {
  if (!check.item) return
  check.saving = true
  try {
    const updated = await checkRoutineApi(check.item.jobId, { date: check.item.date, done: check.done, note: check.note.trim() || undefined })
    occ.value = occ.value.map((o) => (o.jobId === updated.jobId && o.date === updated.date ? updated : o))
    check.open = false
  } catch {
    showErrorToast('common.saveFailed')
  } finally {
    check.saving = false
  }
}

const openForm = (j: RoutineJob | null) => {
  form.editing = j
  form.data = j
    ? { name: j.name, rule: { ...j.rule, dow: j.rule.dow ? [...j.rule.dow] : undefined }, checklist: [...j.checklist], ownerId: j.owner?.userId ?? null }
    : { name: '', rule: { freq: 'WEEKLY', dow: [1] }, checklist: [], ownerId: null }
  form.checklistText = form.data.checklist.join('\n')
  Object.assign(form, { open: true, saving: false, error: null })
}

const save = async () => {
  if (!projectId.value) return
  if (!form.data.name.trim()) { form.error = t('validation.required'); return }
  form.saving = true
  try {
    const rule = form.data.rule.freq === 'WEEKLY' ? { freq: 'WEEKLY' as const, dow: form.data.rule.dow?.length ? form.data.rule.dow : [1] }
      : form.data.rule.freq === 'MONTHLY' ? { freq: 'MONTHLY' as const, day: Math.min(31, Math.max(1, Number(form.data.rule.day) || 1)) }
        : { freq: 'DAILY' as const }
    const payload: RoutineJobPayload = { ...form.data, rule, checklist: form.checklistText.split('\n').map((s) => s.trim()).filter(Boolean) }
    if (form.editing) await updateRoutineJobApi(form.editing.jobId, payload)
    else await createRoutineJobApi(projectId.value, payload)
    form.open = false
    showSuccessToast('common.saved')
    void load()
  } catch (e) {
    form.error = (e as ApiError).message ?? t('common.saveFailed')
  } finally {
    form.saving = false
  }
}

const remove = async () => {
  if (!removeTarget.value) return
  try {
    await deleteRoutineJobApi(removeTarget.value.jobId)
    removeTarget.value = null
    void load()
  } catch {
    showErrorToast('common.deleteFailed')
  }
}

const ruleLabel = (j: RoutineJob) => {
  if (j.rule.freq === 'DAILY') return t('sm.routine.daily')
  if (j.rule.freq === 'WEEKLY') return t('sm.routine.weeklyOn', { days: (j.rule.dow ?? []).map((d) => t(`sm.weekday.${d}`)).join('·') })
  return t('sm.routine.monthlyOn', { day: j.rule.day ?? 1 })
}

// ─────────── computed ───────────
const canEdit = computed(() => auth.hasAnyRole(SM_EDIT_ROLES))
const cells = computed(() => monthGrid(month.value))
const byDate = computed(() => occ.value.reduce<Record<string, RoutineOccurrence[]>>((acc, o) => { (acc[o.date] ??= []).push(o); return acc }, {}))
const weekdays = computed(() => [0, 1, 2, 3, 4, 5, 6].map((d) => t(`sm.weekday.${d}`)))
const freqItems = computed(() => (['DAILY', 'WEEKLY', 'MONTHLY'] as const).map((v) => ({ title: t(`sm.routine.freq.${v}`), value: v })))
const dowItems = computed(() => [1, 2, 3, 4, 5, 6, 0].map((d) => ({ title: t(`sm.weekday.${d}`), value: d })))
const monthDone = computed(() => {
  const inMonth = occ.value.filter((o) => o.date.startsWith(month.value) && o.date <= today())
  return { done: inMonth.filter((o) => o.done).length, total: inMonth.length }
})
const ownerInitial = computed(() => form.editing?.owner ?? null)

watch(month, () => { void load() })
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.sm.routine')" :subtitle="t('sm.routine.subtitle')">
      <VBtn v-if="canEdit && projectId" color="primary" size="small" prepend-icon="ri-add-line" @click="openForm(null)">{{ t('sm.routine.addJob') }}</VBtn>
    </AppPageHeader>
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <VRow v-else>
      <VCol cols="12" lg="9">
        <VCard border flat>
          <VCardTitle class="d-flex flex-wrap align-center ga-2">
            <VBtn icon="ri-arrow-left-s-line" variant="text" size="small" :aria-label="t('sm.prevMonth')" @click="month = shiftMonth(month, -1)" />
            <span class="font-mono">{{ month }}</span>
            <VBtn icon="ri-arrow-right-s-line" variant="text" size="small" :aria-label="t('sm.nextMonth')" @click="month = shiftMonth(month, 1)" />
            <VSpacer />
            <span class="text-body-2 text-muted">{{ t('sm.routine.doneRate', monthDone) }}</span>
          </VCardTitle>
          <VProgressLinear v-if="loading" indeterminate color="primary" />
          <VAlert v-if="error" type="error" variant="tonal" density="compact" class="ma-3">{{ error }}</VAlert>
          <div class="cal-scroll">
            <div class="cal" data-test="routine-calendar">
              <div v-for="w in weekdays" :key="w" class="cal-head">{{ w }}</div>
              <div v-for="(d, i) in cells" :key="i" :class="['cal-cell', { empty: !d, today: d === today() }]">
                <template v-if="d">
                  <div class="cal-day">{{ Number(d.slice(8)) }}</div>
                  <button
                    v-for="o in byDate[d] ?? []"
                    :key="o.jobId"
                    type="button"
                    :class="['occ', { done: o.done, overdue: !o.done && d < today() }]"
                    :title="o.note ?? o.name"
                    :disabled="!canEdit"
                    data-test="routine-occ"
                    @click="openCheck(o)"
                  >
                    <VIcon :icon="o.done ? 'ri-checkbox-circle-fill' : 'ri-checkbox-blank-circle-line'" size="12" />
                    {{ o.name }}
                  </button>
                </template>
              </div>
            </div>
          </div>
          <div class="d-flex flex-wrap ga-4 px-4 py-2 text-caption text-muted">
            <span><VIcon icon="ri-checkbox-circle-fill" size="12" color="success" /> {{ t('sm.routine.legendDone') }}</span>
            <span><VIcon icon="ri-checkbox-blank-circle-line" size="12" color="error" /> {{ t('sm.routine.legendOverdue') }}</span>
          </div>
        </VCard>
      </VCol>
      <VCol cols="12" lg="3">
        <VCard border flat :title="t('sm.routine.jobs')">
          <VList density="compact">
            <VListItem v-for="j in jobs" :key="j.jobId" :title="j.name" :subtitle="`${ruleLabel(j)} · ${j.owner?.name ?? '-'}`">
              <template v-if="canEdit" #append>
                <VBtn icon="ri-edit-line" variant="text" size="x-small" :aria-label="t('common.edit')" @click="openForm(j)" />
                <VBtn icon="ri-delete-bin-line" variant="text" size="x-small" :aria-label="t('common.delete')" @click="removeTarget = j" />
              </template>
            </VListItem>
            <VListItem v-if="!jobs.length" :title="t('common.noData')" disabled />
          </VList>
        </VCard>
      </VCol>
    </VRow>

    <VDialog v-model="check.open" max-width="440">
      <VCard :title="check.item ? `${check.item.name} · ${check.item.date}` : ''">
        <VCardText>
          <template v-if="check.item">
            <div v-for="c in jobs.find((j) => j.jobId === check.item?.jobId)?.checklist ?? []" :key="c" class="text-body-2">· {{ c }}</div>
          </template>
          <VSwitch v-model="check.done" :label="check.done ? t('sm.routine.markDone') : t('sm.routine.markUndone')" color="success" hide-details class="mt-2" />
          <VTextField v-model="check.note" :label="t('sm.routine.note')" hide-details class="mt-2" />
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="check.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :loading="check.saving" data-test="routine-check-save" @click="saveCheck">{{ t('common.save') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>

    <VDialog v-model="form.open" max-width="520">
      <VCard :title="form.editing ? t('sm.routine.editJob') : t('sm.routine.addJob')">
        <VCardText>
          <VAlert v-if="form.error" type="error" variant="tonal" density="compact" class="mb-3">{{ form.error }}</VAlert>
          <VTextField v-model="form.data.name" :label="t('sm.routine.name')" class="mb-2" />
          <VSelect v-model="form.data.rule.freq" :items="freqItems" :label="t('sm.routine.freqLabel')" class="mb-2" />
          <VSelect v-if="form.data.rule.freq === 'WEEKLY'" v-model="form.data.rule.dow" :items="dowItems" :label="t('sm.routine.dow')" multiple chips class="mb-2" />
          <VTextField v-if="form.data.rule.freq === 'MONTHLY'" v-model.number="form.data.rule.day" type="number" min="1" max="31" :label="t('sm.routine.day')" :hint="t('sm.routine.dayHint')" persistent-hint class="mb-2" />
          <UserLookupField v-model="form.data.ownerId" :label="t('sm.routine.owner')" :initial="ownerInitial" class="mb-2" />
          <VTextarea v-model="form.checklistText" :label="t('sm.routine.checklist')" :hint="t('sm.routine.checklistHint')" persistent-hint rows="3" auto-grow />
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="form.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :loading="form.saving" @click="save">{{ t('common.save') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>

    <ConfirmDialog
      :model-value="!!removeTarget"
      :title="t('common.delete')"
      :message="t('sm.routine.removeMessage', { name: removeTarget?.name ?? '' })"
      @update:model-value="(v: boolean) => { if (!v) removeTarget = null }"
      @confirm="remove"
    />
  </VContainer>
</template>

<style scoped>
.cal-scroll {
  overflow-x: auto;
}

.cal {
  display: grid;
  grid-template-columns: repeat(7, minmax(96px, 1fr));
  min-width: 672px;
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
  min-height: 96px;
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

.occ {
  display: flex;
  align-items: center;
  gap: 3px;
  width: 100%;
  font-size: 11px;
  padding: 1px 4px;
  margin-bottom: 2px;
  border-radius: 4px;
  text-align: left;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  background: rgba(var(--v-theme-info), 0.12);
  color: rgb(var(--v-theme-on-surface));
}

.occ.done {
  background: rgba(var(--v-theme-success), 0.16);
}

.occ.overdue {
  background: rgba(var(--v-theme-error), 0.14);
}
</style>
