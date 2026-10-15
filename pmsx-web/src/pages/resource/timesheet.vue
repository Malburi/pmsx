<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  approveTimesheetApi, getPendingTimesheetsApi, getTimesheetApi, rejectTimesheetApi, saveTimesheetApi, submitTimesheetApi,
} from '@/api/resourceApi'
import { getWbsApi } from '@/api/scheduleApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import AppStatusChip from '@/components/common/AppStatusChip.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useAuthStore } from '@/stores/auth'
import type { ApiError } from '@/types/api'
import type { Timesheet, TimesheetEntry, TimesheetTarget, WbsItem } from '@/types/r2'
import { addDays, dayOfWeek, isoWeek, today, weekStart } from '@/utils/date'
import { TIMESHEET_APPROVE_ROLES } from '@/utils/permission'
import { dailyTotals, MAX_HOURS_PER_DAY } from '@/utils/quality'
import { showErrorToast, showSuccessToast } from '@/utils/toast'
import { hasChildren } from '@/utils/wbs'

// SCR-RS-004 작업일지 + 승인함 (FR-RS-006) — 행 = 작업 대상, 열 = 요일
interface Line { key: string; targetType: TimesheetTarget; targetId: string | null; targetLabel: string; hours: Record<string, number> }

const { t } = useI18n()
const auth = useAuthStore()

const tab = ref<'mine' | 'approve'>('mine')
const week = ref(weekStart(today()))
const sheet = ref<Timesheet | null>(null)
const lines = ref<Line[]>([])
const savedJson = ref('[]')
const wbs = ref<WbsItem[]>([])
const pending = ref<Timesheet[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref<string | null>(null)
const addDlg = reactive({ open: false, targetType: 'WBS' as TimesheetTarget, wbsId: null as string | null, label: '' })
const reject = reactive({ open: false, target: null as Timesheet | null, comment: '' })
let lineSeq = 0

// ─────────── 헬퍼 ───────────
const toLines = (ts: Timesheet): Line[] => {
  const map = new Map<string, Line>()
  ts.entries.forEach((e) => {
    const key = `${e.targetType}|${e.targetId ?? e.targetLabel}`
    const line = map.get(key) ?? { key, targetType: e.targetType, targetId: e.targetId, targetLabel: e.targetLabel, hours: {} }
    line.hours[e.date] = (line.hours[e.date] ?? 0) + e.hours
    map.set(key, line)
  })
  return [...map.values()]
}

const toEntries = (): TimesheetEntry[] => lines.value.flatMap((l) => Object.entries(l.hours)
  .filter(([, h]) => h > 0)
  .map(([date, hours]) => ({ date, targetType: l.targetType, targetId: l.targetId, targetLabel: l.targetLabel, hours, note: null })))

const loadSheet = async () => {
  if (!auth.userId) return
  loading.value = true
  error.value = null
  try {
    sheet.value = await getTimesheetApi(auth.userId, week.value)
    lines.value = toLines(sheet.value)
    savedJson.value = JSON.stringify(lines.value)
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}

const loadProject = async (projectId: string) => {
  wbs.value = await getWbsApi(projectId).catch(() => [])
  if (canApprove.value) pending.value = await getPendingTimesheetsApi(projectId).catch(() => [])
  await loadSheet()
}
const { projectId } = useCurrentProject(loadProject)

const moveWeek = (n: number) => { week.value = addDays(week.value, 7 * n) }

const onHours = (line: Line, day: string, e: Event) => {
  const v = Math.max(0, Math.min(24, Math.round((Number((e.target as HTMLInputElement).value) || 0) * 2) / 2))
  line.hours = { ...line.hours, [day]: v }
}

const addLine = () => {
  const w = wbs.value.find((x) => x.wbsId === addDlg.wbsId)
  const label = addDlg.targetType === 'WBS' ? (w ? `${w.wbsNo} ${w.name}` : '') : addDlg.label.trim()
  if (!label) return
  lines.value.push({ key: `new-${++lineSeq}`, targetType: addDlg.targetType, targetId: addDlg.targetType === 'WBS' ? addDlg.wbsId : null, targetLabel: label, hours: {} })
  addDlg.open = false
}

const persist = async (): Promise<Timesheet | null> => {
  if (!sheet.value || !auth.userId) return null
  const saved = await saveTimesheetApi({ ...sheet.value, userId: auth.userId, weekStart: week.value, entries: toEntries() })
  sheet.value = saved
  lines.value = toLines(saved)
  savedJson.value = JSON.stringify(lines.value)
  return saved
}

const save = async () => {
  saving.value = true
  try {
    await persist()
    showSuccessToast('common.saved')
  } catch (e) {
    showErrorToast((e as ApiError).code === 'VALIDATION' ? 'rs.ts.invalid' : 'common.saveFailed')
  } finally {
    saving.value = false
  }
}

const submit = async () => {
  saving.value = true
  try {
    const saved = await persist()
    if (saved?.timesheetId) {
      sheet.value = await submitTimesheetApi(saved.timesheetId)
      showSuccessToast('rs.ts.submitted')
    }
  } catch {
    showErrorToast('common.saveFailed')
  } finally {
    saving.value = false
  }
}

const approve = async (ts: Timesheet) => {
  if (!ts.timesheetId || !projectId.value) return
  try {
    await approveTimesheetApi(ts.timesheetId)
    showSuccessToast('rs.ts.approved')
    pending.value = await getPendingTimesheetsApi(projectId.value)
  } catch {
    showErrorToast('common.actionFailed')
  }
}

const doReject = async () => {
  if (!reject.target?.timesheetId || !reject.comment.trim() || !projectId.value) return
  try {
    await rejectTimesheetApi(reject.target.timesheetId, reject.comment.trim())
    reject.open = false
    showSuccessToast('rs.ts.rejected')
    pending.value = await getPendingTimesheetsApi(projectId.value)
  } catch {
    showErrorToast('common.actionFailed')
  }
}

const totalOf = (ts: Timesheet) => ts.entries.reduce((s, e) => s + e.hours, 0)
const dayName = (d: string) => t(`rs.ts.dow.${dayOfWeek(d)}`)

// ─────────── computed ───────────
const canApprove = computed(() => auth.hasAnyRole(TIMESHEET_APPROVE_ROLES))
const editable = computed(() => sheet.value?.status === 'DRAFT' || sheet.value?.status === 'REJECTED')
const dirty = computed(() => JSON.stringify(lines.value) !== savedJson.value)
const days = computed(() => Array.from({ length: 7 }, (_, i) => addDays(week.value, i)))
const totals = computed(() => dailyTotals(toEntries()))
const weekTotal = computed(() => Object.values(totals.value).reduce((s, v) => s + v, 0))
const overDays = computed(() => Object.entries(totals.value).filter(([, v]) => v > MAX_HOURS_PER_DAY).map(([d]) => d))
const wbsItems = computed(() => wbs.value
  .filter((i) => !hasChildren(wbs.value, i.wbsId) && !i.milestone)
  .sort((a, b) => Number(b.assignees.some((x) => x.userId === auth.userId)) - Number(a.assignees.some((x) => x.userId === auth.userId)))
  .map((i) => ({ title: `${i.wbsNo} ${i.name}${i.assignees.some((x) => x.userId === auth.userId) ? ' ★' : ''}`, value: i.wbsId })))
const targetTypes = computed(() => (['WBS', 'SCREEN', 'SR', 'ETC'] as TimesheetTarget[]).map((v) => ({ title: t(`rs.ts.target.${v}`), value: v })))

// ─────────── watch ───────────
watch(week, () => { void loadSheet() })
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.resource.timesheet')" :subtitle="t('rs.ts.subtitle')" />
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <VCard v-else border flat>
      <VTabs v-model="tab" density="compact" color="primary" class="border-b">
        <VTab value="mine">{{ t('rs.ts.tabMine') }}</VTab>
        <VTab v-if="canApprove" value="approve" data-test="ts-approve-tab">
          {{ t('rs.ts.tabApprove') }}
          <VBadge v-if="pending.length" :content="pending.length" color="error" inline />
        </VTab>
      </VTabs>

      <div v-show="tab === 'mine'">
        <div class="d-flex flex-wrap align-center ga-2 px-4 py-3">
          <VBtn icon variant="text" size="small" :aria-label="t('rs.ts.prevWeek')" @click="moveWeek(-1)"><VIcon icon="ri-arrow-left-s-line" /></VBtn>
          <b class="font-mono">{{ isoWeek(week) }}</b>
          <span class="text-body-2 text-muted">{{ week }} ~ {{ addDays(week, 6) }}</span>
          <VBtn icon variant="text" size="small" :aria-label="t('rs.ts.nextWeek')" @click="moveWeek(1)"><VIcon icon="ri-arrow-right-s-line" /></VBtn>
          <VBtn variant="text" size="small" @click="week = weekStart(today())">{{ t('rs.ts.thisWeek') }}</VBtn>
          <AppStatusChip v-if="sheet" :status="sheet.status" prefix="rs.ts.status" />
          <VSpacer />
          <template v-if="editable">
            <VBtn size="small" variant="tonal" prepend-icon="ri-add-line" data-test="ts-add" @click="Object.assign(addDlg, { open: true, wbsId: null, label: '' })">{{ t('rs.ts.addLine') }}</VBtn>
            <VBtn size="small" variant="tonal" :disabled="!dirty" :loading="saving" @click="save">{{ t('common.save') }}</VBtn>
            <VBtn size="small" color="primary" prepend-icon="ri-send-plane-line" :disabled="!lines.length || overDays.length > 0" :loading="saving" data-test="ts-submit" @click="submit">{{ t('rs.ts.submit') }}</VBtn>
          </template>
        </div>
        <VAlert v-if="sheet?.status === 'REJECTED'" type="warning" variant="tonal" density="compact" class="mx-4 mb-2">{{ t('rs.ts.rejectedMsg', { c: sheet.comment ?? '' }) }}</VAlert>
        <VAlert v-if="overDays.length" type="error" variant="tonal" density="compact" class="mx-4 mb-2">{{ t('rs.ts.overDay', { max: MAX_HOURS_PER_DAY }) }}</VAlert>
        <VAlert v-if="error" type="error" variant="tonal" density="compact" class="mx-4 mb-2">{{ error }}</VAlert>
        <VProgressLinear v-if="loading" indeterminate color="primary" />
        <div class="grid-wrap">
          <table class="ts-grid" data-test="ts-grid">
            <thead>
              <tr>
                <th class="target">{{ t('rs.ts.target.label') }}</th>
                <th v-for="d in days" :key="d" :class="{ weekend: dayOfWeek(d) === 0 || dayOfWeek(d) === 6, today: d === today() }">
                  {{ dayName(d) }}<br><span class="text-muted">{{ d.slice(5) }}</span>
                </th>
                <th>{{ t('rs.plan.sum') }}</th>
                <th v-if="editable" class="act" />
              </tr>
            </thead>
            <tbody>
              <tr v-for="(l, i) in lines" :key="l.key">
                <td class="target">
                  <VChip size="x-small" variant="tonal" label class="mr-1">{{ t(`rs.ts.target.${l.targetType}`) }}</VChip>
                  <span class="text-body-2">{{ l.targetLabel }}</span>
                </td>
                <td v-for="d in days" :key="d" :class="{ weekend: dayOfWeek(d) === 0 || dayOfWeek(d) === 6 }">
                  <input
                    v-if="editable"
                    type="number"
                    min="0"
                    max="24"
                    step="0.5"
                    class="h-input"
                    :value="l.hours[d] || ''"
                    :aria-label="`${l.targetLabel} ${d}`"
                    @change="onHours(l, d, $event)"
                  >
                  <span v-else>{{ l.hours[d] || '' }}</span>
                </td>
                <td class="font-weight-bold">{{ Object.values(l.hours).reduce((s, v) => s + v, 0) }}</td>
                <td v-if="editable" class="act">
                  <VBtn icon variant="text" size="x-small" :aria-label="t('common.delete')" @click="lines.splice(i, 1)"><VIcon icon="ri-close-line" /></VBtn>
                </td>
              </tr>
              <tr v-if="!lines.length"><td :colspan="editable ? 10 : 9" class="text-center text-muted pa-6">{{ t('rs.ts.empty') }}</td></tr>
            </tbody>
            <tfoot>
              <tr>
                <td class="target font-weight-bold">{{ t('rs.plan.sum') }}</td>
                <td v-for="d in days" :key="d" :class="['font-weight-bold', { 'text-error': (totals[d] ?? 0) > MAX_HOURS_PER_DAY }]">{{ totals[d] ?? '' }}</td>
                <td class="font-weight-bold">{{ weekTotal }}</td>
                <td v-if="editable" />
              </tr>
            </tfoot>
          </table>
        </div>
      </div>

      <div v-if="canApprove" v-show="tab === 'approve'" class="pa-4">
        <VTable density="compact">
          <thead>
            <tr><th>{{ t('rs.heatmap.person') }}</th><th>{{ t('rs.ts.week') }}</th><th class="text-end">{{ t('rs.ts.hours') }}</th><th /></tr>
          </thead>
          <tbody>
            <tr v-for="p in pending" :key="p.timesheetId ?? p.userId" :data-test="`pending-${p.userId}`">
              <td>{{ p.userName ?? p.userId }}</td>
              <td class="font-mono">{{ isoWeek(p.weekStart) }} ({{ p.weekStart }})</td>
              <td class="text-end">{{ totalOf(p) }}h</td>
              <td class="text-end">
                <VBtn size="small" variant="tonal" color="success" class="mr-1" @click="approve(p)">{{ t('rs.ts.approve') }}</VBtn>
                <VBtn size="small" variant="tonal" color="error" @click="Object.assign(reject, { open: true, target: p, comment: '' })">{{ t('rs.ts.reject') }}</VBtn>
              </td>
            </tr>
            <tr v-if="!pending.length"><td colspan="4" class="text-center text-muted pa-6">{{ t('rs.ts.noPending') }}</td></tr>
          </tbody>
        </VTable>
      </div>
    </VCard>

    <VDialog v-model="addDlg.open" max-width="480">
      <VCard :title="t('rs.ts.addLine')">
        <VCardText>
          <VSelect v-model="addDlg.targetType" :items="targetTypes" :label="t('rs.ts.target.label')" class="mb-3" />
          <VAutocomplete v-if="addDlg.targetType === 'WBS'" v-model="addDlg.wbsId" :items="wbsItems" :label="t('rs.ts.wbsPick')" :hint="t('rs.ts.wbsHint')" persistent-hint />
          <VTextField v-else v-model="addDlg.label" :label="t('rs.ts.labelPick')" :placeholder="t(`rs.ts.placeholder.${addDlg.targetType}`)" />
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="addDlg.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" data-test="ts-add-apply" @click="addLine">{{ t('common.add') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>

    <VDialog v-model="reject.open" max-width="440">
      <VCard :title="t('rs.ts.reject')">
        <VCardText>
          <VTextarea v-model="reject.comment" :label="t('rs.ts.rejectReason')" rows="3" autofocus />
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="reject.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="error" :disabled="!reject.comment.trim()" @click="doReject">{{ t('rs.ts.reject') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>
  </VContainer>
</template>

<style scoped>
.grid-wrap {
  overflow-x: auto;
}

.ts-grid {
  border-collapse: collapse;
  width: 100%;
  min-width: 720px;
  font-size: 12.5px;
}

.ts-grid th,
.ts-grid td {
  border-bottom: 1px solid rgb(var(--v-theme-line));
  padding: 6px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}

.ts-grid th {
  color: rgb(var(--v-theme-muted));
  font-weight: 600;
}

.ts-grid .target {
  text-align: left;
  min-width: 240px;
}

.ts-grid .weekend {
  background: rgba(var(--v-theme-on-surface), 0.035);
}

.ts-grid th.today {
  color: rgb(var(--v-theme-primary));
}

.ts-grid tfoot td {
  background: rgba(var(--v-theme-on-surface), 0.04);
}

.act {
  width: 40px;
}

.h-input {
  width: 56px;
  border: 1px solid rgb(var(--v-theme-line));
  border-radius: 4px;
  padding: 3px 4px;
  text-align: right;
  font: inherit;
  color: inherit;
  background: transparent;
}

.h-input:focus {
  outline: none;
  border-color: rgb(var(--v-theme-primary));
}
</style>
