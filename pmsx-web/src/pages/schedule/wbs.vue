<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { onBeforeRouteLeave, useRouter, type RouteLocationNormalized } from 'vue-router'
import {
  createBaselineApi, generateWbsApi, getMethodologiesApi, getProjectProgressApi, getWbsApi, importMsProjectApi, patchWbsApi, saveWbsApi,
} from '@/api/scheduleApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import AppStatusChip from '@/components/common/AppStatusChip.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import ExcelExportButton from '@/components/common/ExcelExportButton.vue'
import ExcelImportDialog from '@/components/common/ExcelImportDialog.vue'
import UserMultiPick from '@/components/common/UserMultiPick.vue'
import ProgressSCurve from '@/components/schedule/ProgressSCurve.vue'
import WbsDepsDialog from '@/components/schedule/WbsDepsDialog.vue'
import WbsGantt from '@/components/schedule/WbsGantt.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useAuthStore } from '@/stores/auth'
import type { ApiError, UserRef } from '@/types/api'
import type { Methodology, ProjectProgress, WbsDep, WbsItem, WbsStatus } from '@/types/r2'
import { addDays, today } from '@/utils/date'
import { ganttRange, todayX, type GanttScale } from '@/utils/gantt'
import { BASELINE_ROLES, WBS_EDIT_ROLES } from '@/utils/permission'
import { WBS_STATUSES } from '@/utils/quality'
import { showErrorToast, showSuccessToast } from '@/utils/toast'
import { depthMap, hasChildren, indent, isDelayed, moveSibling, outdent, renumber, rollup, subtreeIds, visibleRows } from '@/utils/wbs'

// SCR-PJ-001 WBS / 간트 (FR-PJ-002~006·008)
const { t } = useI18n()
const auth = useAuthStore()
const router = useRouter()

const ROW_H = 36
const HEADER_H = 48
const items = ref<WbsItem[]>([])
const savedJson = ref('[]')
const progress = ref<ProjectProgress | null>(null)
const loading = ref(false)
const saving = ref(false)
const loadError = ref<string | null>(null)
const selectedId = ref<string | null>(null)
// 저장하지 않은 변경이 있을 때 화면 이탈 확인 (회사 ConfirmDialog — window.confirm 금지)
const leave = reactive({ open: false, to: null as RouteLocationNormalized | null, confirmed: false })
const gridEl = ref<HTMLElement | null>(null)
const collapsed = ref(new Set<string>())
const view = reactive({ scale: 'week' as GanttScale, baseline: true, critical: true, curve: false })
const depsOpen = ref(false)
const assignee = reactive({ open: false, users: [] as UserRef[] })
const gen = reactive({ open: false, methodologyId: null as string | null, startDate: today(), replace: false, saving: false, error: null as string | null })
const methodologies = ref<Methodology[]>([])
const msp = reactive({ open: false, file: null as File | null, replace: false, saving: false, error: null as string | null })
const excelOpen = ref(false)
const baseline = reactive({ open: false, name: '', saving: false })
const removeOpen = ref(false)
const ganttEl = ref<HTMLDivElement | null>(null)
let tmpSeq = 0

// ─────────── 헬퍼 ───────────
const load = async (projectId: string) => {
  loading.value = true
  loadError.value = null
  try {
    const [list, prog] = await Promise.all([getWbsApi(projectId), getProjectProgressApi(projectId)])
    items.value = list
    savedJson.value = JSON.stringify(list)
    progress.value = prog
  } catch (e) {
    loadError.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const { projectId } = useCurrentProject(load)

const update = (id: string, patch: Partial<WbsItem>) => {
  items.value = items.value.map((i) => (i.wbsId === id ? { ...i, ...patch } : i))
}

const onCell = (id: string, field: keyof WbsItem, e: Event) => {
  const el = e.target as HTMLInputElement | HTMLSelectElement
  const raw = el.value
  if (field === 'weight' || field === 'progress') {
    const n = Math.max(0, Math.min(field === 'progress' ? 100 : 100, Number(raw) || 0))
    const patch: Partial<WbsItem> = { [field]: n }
    if (field === 'progress') patch.status = n >= 100 ? 'DONE' : n > 0 ? 'IN_PROGRESS' : 'TODO'
    update(id, patch)
  } else if (field === 'planStart' || field === 'planEnd') {
    const row = items.value.find((i) => i.wbsId === id)
    if (!row || !raw) return
    const start = field === 'planStart' ? raw : row.planStart
    const end = field === 'planEnd' ? raw : row.planEnd
    // 시작 > 종료가 되면 다른 쪽을 같은 날로 맞춘다
    if (start && end && start > end) update(id, field === 'planStart' ? { planStart: raw, planEnd: raw } : { planStart: raw, planEnd: raw })
    else update(id, { [field]: raw })
  } else if (field === 'status') {
    update(id, { status: raw as WbsStatus, ...(raw === 'DONE' ? { progress: 100 } : {}) })
  } else {
    update(id, { [field]: raw })
  }
}

const select = (id: string) => { selectedId.value = id }

const toggle = (id: string) => {
  const next = new Set(collapsed.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  collapsed.value = next
}

const newItem = (parentId: string | null, order: number, start: string, end: string): WbsItem => ({
  wbsId: `tmp-${++tmpSeq}`, parentId, wbsNo: '', name: t('pj.wbs.newTask'), order, planStart: start, planEnd: end,
  actStart: null, actEnd: null, weight: 1, progress: 0, milestone: false, status: 'TODO', assignees: [], deliverableId: null,
  deps: [], critical: false, baselineStart: null, baselineEnd: null,
})

const addSibling = () => {
  const sel = selected.value
  const start = sel?.planStart ?? today()
  const item = newItem(sel?.parentId ?? null, (sel?.order ?? items.value.length) + 0.5, start, addDays(start, 4))
  items.value = renumber([...items.value, item])
  selectedId.value = item.wbsId
}

const addChild = () => {
  const sel = selected.value
  if (!sel) return
  const start = sel.planStart ?? today()
  const item = newItem(sel.wbsId, 9999, start, addDays(start, 4))
  items.value = renumber([...items.value, item])
  const next = new Set(collapsed.value)
  next.delete(sel.wbsId)
  collapsed.value = next
  selectedId.value = item.wbsId
}

const structural = (fn: (list: WbsItem[], id: string) => WbsItem[]) => {
  if (!selectedId.value) return
  items.value = fn(items.value, selectedId.value)
}

const removeSelected = () => {
  if (!selectedId.value) return
  const gone = subtreeIds(items.value, selectedId.value)
  items.value = renumber(items.value.filter((i) => !gone.has(i.wbsId)).map((i) => ({ ...i, deps: i.deps.filter((d) => !gone.has(d.predId)) })))
  selectedId.value = null
  removeOpen.value = false
}

const saveDeps = (deps: WbsDep[]) => { if (selectedId.value) update(selectedId.value, { deps }) }

const openAssignees = () => {
  if (!selected.value) return
  assignee.users = [...selected.value.assignees]
  assignee.open = true
}

const applyAssignees = () => {
  if (selectedId.value) update(selectedId.value, { assignees: assignee.users })
  assignee.open = false
}

const save = async () => {
  if (!projectId.value) return
  saving.value = true
  try {
    // 상위 항목의 진척·기간은 서버가 계산하므로 하위 값만 의미가 있다
    const list = await saveWbsApi(projectId.value, renumber(items.value))
    items.value = list
    savedJson.value = JSON.stringify(list)
    showSuccessToast('common.saved')
    progress.value = await getProjectProgressApi(projectId.value)
  } catch (e) {
    const err = e as ApiError
    if (err.code === 'WBS_CYCLE') showErrorToast('pj.wbs.cycle')
    else showErrorToast('common.saveFailed')
  } finally {
    saving.value = false
  }
}

const discard = () => {
  items.value = JSON.parse(savedJson.value)
  selectedId.value = null
}

// 간트 드래그: 저장 안 한 변경이 없으면 바로 PATCH, 있으면 화면 사본에만 반영
const onGanttChange = async (id: string, start: string, end: string) => {
  if (!dirty.value) {
    try {
      const updated = await patchWbsApi(id, { planStart: start, planEnd: end })
      items.value = items.value.map((i) => (i.wbsId === id ? updated : i))
      savedJson.value = JSON.stringify(items.value)
    } catch {
      showErrorToast('common.saveFailed')
    }
    return
  }
  update(id, { planStart: start, planEnd: end })
}

const openGenerate = async () => {
  Object.assign(gen, { open: true, saving: false, error: null, startDate: projectStart.value })
  if (!methodologies.value.length) {
    methodologies.value = await getMethodologiesApi().catch(() => [])
    gen.methodologyId = methodologies.value[0]?.methodologyId ?? null
  }
}

const runGenerate = async () => {
  if (!projectId.value || !gen.methodologyId) return
  gen.saving = true
  gen.error = null
  try {
    items.value = await generateWbsApi(projectId.value, { methodologyId: gen.methodologyId, startDate: gen.startDate, replace: gen.replace })
    savedJson.value = JSON.stringify(items.value)
    gen.open = false
    showSuccessToast('pj.wbs.generated')
  } catch (e) {
    gen.error = (e as ApiError).message ?? t('common.saveFailed')
  } finally {
    gen.saving = false
  }
}

const runMsp = async () => {
  if (!projectId.value || !msp.file) return
  msp.saving = true
  msp.error = null
  try {
    items.value = await importMsProjectApi(projectId.value, msp.file, msp.replace)
    savedJson.value = JSON.stringify(items.value)
    msp.open = false
    showSuccessToast('pj.wbs.imported')
  } catch (e) {
    msp.error = (e as ApiError).message ?? t('common.saveFailed')
  } finally {
    msp.saving = false
  }
}

const saveBaseline = async () => {
  if (!projectId.value || !baseline.name.trim()) return
  baseline.saving = true
  try {
    await createBaselineApi(projectId.value, baseline.name.trim())
    baseline.open = false
    showSuccessToast('pj.wbs.baselineSaved')
    await load(projectId.value)
  } catch {
    showErrorToast('common.saveFailed')
  } finally {
    baseline.saving = false
  }
}

// 처음 열거나 스케일을 바꾸면 오늘선이 보이도록 간트를 가로로 옮긴다
const scrollToToday = async () => {
  await nextTick()
  const x = todayX(asOf.value, range.value, view.scale)
  if (ganttEl.value && x !== null) ganttEl.value.scrollLeft = Math.max(0, x - ganttEl.value.clientWidth / 3)
}

const onMspFile = (v: File | File[] | null) => { msp.file = Array.isArray(v) ? v[0] ?? null : v }
const reload = () => { if (projectId.value) void load(projectId.value) }

// ─────────── computed ───────────
const canEdit = computed(() => auth.hasAnyRole(WBS_EDIT_ROLES))
const canBaseline = computed(() => auth.hasAnyRole(BASELINE_ROLES))
const dirty = computed(() => JSON.stringify(items.value) !== savedJson.value)
const display = computed(() => rollup(items.value))
const parentIds = computed(() => new Set(items.value.filter((i) => hasChildren(items.value, i.wbsId)).map((i) => i.wbsId)))
const rows = computed(() => visibleRows(display.value, collapsed.value))
const depths = computed(() => depthMap(items.value))
const selected = computed(() => items.value.find((i) => i.wbsId === selectedId.value) ?? null)
const range = computed(() => ganttRange(display.value, today(), 7))
const asOf = computed(() => progress.value?.asOf ?? today())
const projectStart = computed(() => display.value.map((i) => i.planStart).filter((d): d is string => !!d).sort()[0] ?? today())
const statusItems = computed(() => WBS_STATUSES.map((s) => ({ value: s, title: t(`pj.status.${s}`) })))
const methodologyItems = computed(() => methodologies.value.map((m) => ({ title: m.name, value: m.methodologyId })))
const delayedCount = computed(() => display.value.filter((i) => !parentIds.value.has(i.wbsId) && isDelayed(i, asOf.value)).length)

// ─────────── watch ───────────
watch(() => [view.scale, savedJson.value === '[]'], () => { void scrollToToday() })
watch(progress, () => { void scrollToToday() })

// ─────────── 라이프사이클 ───────────
onBeforeRouteLeave((to) => {
  if (!dirty.value || leave.confirmed) return true
  Object.assign(leave, { open: true, to })
  return false
})

// 그리드·간트는 각자 세로 스크롤(헤더 고정)하고, 행 위치는 서로 맞춘다
let syncing = false
const syncScroll = (from: 'grid' | 'gantt') => {
  if (syncing) { syncing = false; return }
  const src = from === 'grid' ? gridEl.value : ganttEl.value
  const dst = from === 'grid' ? ganttEl.value : gridEl.value
  if (!src || !dst || dst.scrollTop === src.scrollTop) return
  syncing = true
  dst.scrollTop = src.scrollTop
}

const confirmLeave = () => {
  const to = leave.to
  Object.assign(leave, { open: false, to: null, confirmed: true })
  if (to) void router.push(to.fullPath)
}
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.schedule.wbs')" :subtitle="t('pj.wbs.subtitle')">
      <ExcelExportButton v-if="projectId" dataset-id="wbs" :file-name="t('nav.schedule.wbs')" :params="{ projectId }" />
      <VMenu v-if="canEdit" location="bottom end">
        <template #activator="{ props: mp }">
          <VBtn v-bind="mp" variant="tonal" size="small" prepend-icon="ri-download-2-line" append-icon="ri-arrow-down-s-line">{{ t('pj.wbs.importMenu') }}</VBtn>
        </template>
        <VList density="compact">
          <VListItem prepend-icon="ri-magic-line" :title="t('pj.wbs.generate')" data-test="wbs-generate" @click="openGenerate" />
          <VListItem prepend-icon="ri-file-excel-2-line" :title="t('pj.wbs.importExcel')" @click="excelOpen = true" />
          <VListItem prepend-icon="ri-file-code-line" :title="t('pj.wbs.importMsp')" @click="msp.open = true" />
        </VList>
      </VMenu>
      <VBtn v-if="canBaseline" variant="tonal" size="small" prepend-icon="ri-bookmark-line" @click="Object.assign(baseline, { open: true, name: '' })">{{ t('pj.wbs.saveBaseline') }}</VBtn>
    </AppPageHeader>

    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <template v-else>
      <VAlert v-if="loadError" type="error" variant="tonal" density="compact" class="mb-3">
        {{ loadError }}<VBtn variant="text" size="small" @click="reload">{{ t('common.retry') }}</VBtn>
      </VAlert>

      <!-- 진척 요약 -->
      <VCard v-if="progress" border flat class="mb-3">
        <div class="d-flex flex-wrap align-center ga-4 px-4 py-3">
          <div class="kpi"><span class="kpi-label">{{ t('pj.progress.planned') }}</span><b>{{ progress.planned }}%</b></div>
          <div class="kpi"><span class="kpi-label">{{ t('pj.progress.actual') }}</span><b class="text-primary">{{ progress.actual }}%</b></div>
          <div class="kpi">
            <span class="kpi-label">SPI</span>
            <b :class="(progress.spi ?? 1) < 0.9 ? 'text-error' : (progress.spi ?? 1) < 0.95 ? 'text-warning' : 'text-success'">{{ progress.spi ?? '-' }}</b>
          </div>
          <div class="kpi"><span class="kpi-label">{{ t('pj.wbs.delayed') }}</span><b :class="{ 'text-error': delayedCount > 0 }">{{ delayedCount }}</b></div>
          <VSpacer />
          <VBtn variant="text" size="small" :prepend-icon="view.curve ? 'ri-arrow-up-s-line' : 'ri-line-chart-line'" @click="view.curve = !view.curve">
            {{ t('pj.progress.sCurve') }}
          </VBtn>
        </div>
        <VExpandTransition>
          <div v-if="view.curve" class="px-4 pb-3"><ProgressSCurve :progress="progress" /></div>
        </VExpandTransition>
      </VCard>

      <VCard border flat>
        <!-- 도구 모음 -->
        <div class="d-flex flex-wrap align-center ga-1 px-3 py-2 border-b toolbar">
          <template v-if="canEdit">
            <VBtn size="small" variant="text" prepend-icon="ri-add-line" data-test="wbs-add" @click="addSibling">{{ t('pj.wbs.add') }}</VBtn>
            <VBtn size="small" variant="text" prepend-icon="ri-corner-down-right-line" :disabled="!selected" @click="addChild">{{ t('pj.wbs.addChild') }}</VBtn>
            <VBtn icon size="small" variant="text" :disabled="!selected" :aria-label="t('pj.wbs.outdent')" @click="structural(outdent)"><VIcon icon="ri-indent-decrease" /></VBtn>
            <VBtn icon size="small" variant="text" :disabled="!selected" :aria-label="t('pj.wbs.indent')" data-test="wbs-indent" @click="structural(indent)"><VIcon icon="ri-indent-increase" /></VBtn>
            <VBtn icon size="small" variant="text" :disabled="!selected" :aria-label="t('pj.wbs.up')" @click="structural((l, id) => moveSibling(l, id, -1))"><VIcon icon="ri-arrow-up-line" /></VBtn>
            <VBtn icon size="small" variant="text" :disabled="!selected" :aria-label="t('pj.wbs.down')" @click="structural((l, id) => moveSibling(l, id, 1))"><VIcon icon="ri-arrow-down-line" /></VBtn>
            <VBtn size="small" variant="text" prepend-icon="ri-git-commit-line" :disabled="!selected" @click="depsOpen = true">{{ t('pj.wbs.deps') }}</VBtn>
            <VBtn size="small" variant="text" prepend-icon="ri-user-add-line" :disabled="!selected || parentIds.has(selected.wbsId)" @click="openAssignees">{{ t('pj.wbs.assignees') }}</VBtn>
            <VBtn size="small" variant="text" color="error" prepend-icon="ri-delete-bin-line" :disabled="!selected" @click="removeOpen = true">{{ t('common.delete') }}</VBtn>
            <VDivider vertical class="mx-1" />
          </template>
          <VBtnToggle v-model="view.scale" density="compact" variant="outlined" mandatory divided>
            <VBtn value="week" size="small">{{ t('pj.wbs.scaleWeek') }}</VBtn>
            <VBtn value="month" size="small">{{ t('pj.wbs.scaleMonth') }}</VBtn>
          </VBtnToggle>
          <VCheckbox v-model="view.baseline" :label="t('pj.wbs.showBaseline')" density="compact" hide-details class="ml-2 flex-grow-0" />
          <VCheckbox v-model="view.critical" :label="t('pj.wbs.showCritical')" density="compact" hide-details class="flex-grow-0" />
          <VSpacer />
          <template v-if="canEdit">
            <span v-if="dirty" class="text-caption text-warning mr-2">{{ t('pj.wbs.unsaved') }}</span>
            <VBtn size="small" variant="text" :disabled="!dirty" @click="discard">{{ t('common.reset') }}</VBtn>
            <VBtn size="small" color="primary" :disabled="!dirty" :loading="saving" data-test="wbs-save" @click="save">{{ t('common.save') }}</VBtn>
          </template>
        </div>
        <VProgressLinear v-if="loading" indeterminate color="primary" />

        <div class="wbs-split">
          <!-- 계층 그리드 -->
          <div ref="gridEl" class="wbs-grid" @scroll="syncScroll('grid')">
            <table class="grid" :aria-label="t('nav.schedule.wbs')">
              <thead :style="{ height: `${HEADER_H}px` }">
                <tr>
                  <th class="c-no">{{ t('pj.wbs.colNo') }}</th>
                  <th class="c-name">{{ t('pj.wbs.colName') }}</th>
                  <th class="c-date">{{ t('pj.wbs.colStart') }}</th>
                  <th class="c-date">{{ t('pj.wbs.colEnd') }}</th>
                  <th class="c-num">{{ t('pj.wbs.colWeight') }}</th>
                  <th class="c-num">{{ t('pj.wbs.colProgress') }}</th>
                  <th class="c-who">{{ t('pj.wbs.colAssignees') }}</th>
                  <th class="c-status">{{ t('pj.wbs.colStatus') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="r in rows"
                  :key="r.wbsId"
                  :style="{ height: `${ROW_H}px` }"
                  :class="{ selected: r.wbsId === selectedId, parent: parentIds.has(r.wbsId), delayed: !parentIds.has(r.wbsId) && isDelayed(r, asOf) }"
                  :data-test="`wbs-row-${r.wbsId}`"
                  @click="select(r.wbsId)"
                >
                  <td class="c-no font-mono">{{ r.wbsNo }}</td>
                  <td class="c-name">
                    <div class="d-flex align-center" :style="{ paddingLeft: `${(depths.get(r.wbsId) ?? 0) * 16}px` }">
                      <button
                        v-if="parentIds.has(r.wbsId)"
                        type="button"
                        class="tree-toggle"
                        :aria-label="collapsed.has(r.wbsId) ? t('pj.wbs.expand') : t('pj.wbs.collapse')"
                        :aria-expanded="!collapsed.has(r.wbsId)"
                        @click.stop="toggle(r.wbsId)"
                      >
                        <VIcon :icon="collapsed.has(r.wbsId) ? 'ri-arrow-right-s-fill' : 'ri-arrow-down-s-fill'" size="16" />
                      </button>
                      <span v-else class="tree-toggle" />
                      <VIcon v-if="r.milestone" icon="ri-flag-2-fill" size="14" color="warning" class="mr-1" />
                      <VIcon v-if="r.critical && view.critical && !parentIds.has(r.wbsId)" icon="ri-fire-line" size="13" color="error" class="mr-1" :title="t('pj.wbs.critical')" />
                      <input
                        v-if="canEdit"
                        class="cell-input name"
                        :value="r.name"
                        :aria-label="t('pj.wbs.colName')"
                        @change="onCell(r.wbsId, 'name', $event)"
                      >
                      <span v-else class="text-truncate">{{ r.name }}</span>
                    </div>
                  </td>
                  <td class="c-date">
                    <input
                      v-if="canEdit && !parentIds.has(r.wbsId)"
                      type="date"
                      class="cell-input"
                      :value="r.planStart ?? ''"
                      :aria-label="t('pj.wbs.colStart')"
                      @change="onCell(r.wbsId, 'planStart', $event)"
                    >
                    <span v-else class="font-mono text-caption">{{ r.planStart ?? '-' }}</span>
                  </td>
                  <td class="c-date">
                    <input
                      v-if="canEdit && !parentIds.has(r.wbsId)"
                      type="date"
                      class="cell-input"
                      :value="r.planEnd ?? ''"
                      :aria-label="t('pj.wbs.colEnd')"
                      @change="onCell(r.wbsId, 'planEnd', $event)"
                    >
                    <span v-else class="font-mono text-caption">{{ r.planEnd ?? '-' }}</span>
                  </td>
                  <td class="c-num">
                    <input
                      v-if="canEdit && !parentIds.has(r.wbsId)"
                      type="number"
                      min="0"
                      class="cell-input num"
                      :value="r.weight"
                      :aria-label="t('pj.wbs.colWeight')"
                      @change="onCell(r.wbsId, 'weight', $event)"
                    >
                    <span v-else>{{ parentIds.has(r.wbsId) ? '' : r.weight }}</span>
                  </td>
                  <td class="c-num">
                    <input
                      v-if="canEdit && !parentIds.has(r.wbsId)"
                      type="number"
                      min="0"
                      max="100"
                      class="cell-input num"
                      :value="r.progress"
                      :aria-label="t('pj.wbs.colProgress')"
                      @change="onCell(r.wbsId, 'progress', $event)"
                    >
                    <span v-else class="font-weight-medium">{{ r.progress }}%</span>
                  </td>
                  <td class="c-who text-truncate" :title="r.assignees.map((a) => a.name).join(', ')">
                    {{ r.assignees.map((a) => a.name).join(', ') || (parentIds.has(r.wbsId) ? '' : '-') }}
                  </td>
                  <td class="c-status">
                    <select
                      v-if="canEdit && !parentIds.has(r.wbsId)"
                      class="cell-input"
                      :value="r.status"
                      :aria-label="t('pj.wbs.colStatus')"
                      @change="onCell(r.wbsId, 'status', $event)"
                    >
                      <option v-for="s in statusItems" :key="s.value" :value="s.value">{{ s.title }}</option>
                    </select>
                    <AppStatusChip v-else-if="!parentIds.has(r.wbsId)" :status="r.status" prefix="pj.status" size="x-small" />
                  </td>
                </tr>
                <tr v-if="!rows.length && !loading">
                  <td colspan="8" class="text-center text-muted pa-6">{{ t('pj.wbs.empty') }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <!-- 간트 -->
          <div ref="ganttEl" class="wbs-gantt" data-test="wbs-gantt" @scroll="syncScroll('gantt')">
            <WbsGantt
              :rows="rows"
              :range="range"
              :scale="view.scale"
              :today="asOf"
              :row-height="ROW_H"
              :header-height="HEADER_H"
              :editable="canEdit"
              :show-baseline="view.baseline"
              :show-critical="view.critical"
              :selected-id="selectedId"
              :parent-ids="parentIds"
              @change="onGanttChange"
              @select="select"
            />
          </div>
        </div>
        <div class="d-flex flex-wrap ga-4 px-4 py-2 border-t text-caption text-muted legend">
          <span><i class="lg bar" />{{ t('pj.wbs.legendTask') }}</span>
          <span><i class="lg crit" />{{ t('pj.wbs.legendCritical') }}</span>
          <span><i class="lg base" />{{ t('pj.wbs.legendBaseline') }}</span>
          <span><VIcon icon="ri-flag-2-fill" size="12" color="warning" /> {{ t('pj.wbs.legendMilestone') }}</span>
          <span><i class="lg today" />{{ t('pj.progress.today') }}</span>
          <span v-if="canEdit">{{ t('pj.wbs.dragHint') }}</span>
        </div>
      </VCard>
    </template>

    <WbsDepsDialog v-model="depsOpen" :item="selected" :items="items" @save="saveDeps" />

    <VDialog v-model="assignee.open" max-width="480">
      <VCard :title="t('pj.wbs.assignees')">
        <VCardText><UserMultiPick v-model="assignee.users" :label="t('pj.wbs.colAssignees')" /></VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="assignee.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" @click="applyAssignees">{{ t('common.apply') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>

    <VDialog v-model="gen.open" max-width="480">
      <VCard :title="t('pj.wbs.generate')">
        <VCardText>
          <VSelect v-model="gen.methodologyId" :items="methodologyItems" :label="t('pj.wbs.methodology')" class="mb-3" />
          <VTextField v-model="gen.startDate" type="date" :label="t('pj.wbs.startDate')" class="mb-1" />
          <VCheckbox v-model="gen.replace" :label="t('pj.wbs.replace')" density="compact" hide-details />
          <div class="text-caption text-muted">{{ t('pj.wbs.replaceHint') }}</div>
          <VAlert v-if="gen.error" type="error" variant="tonal" density="compact" class="mt-2">{{ gen.error }}</VAlert>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="gen.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :loading="gen.saving" :disabled="!gen.methodologyId" data-test="wbs-generate-run" @click="runGenerate">{{ t('pj.wbs.generateRun') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>

    <VDialog v-model="msp.open" max-width="480">
      <VCard :title="t('pj.wbs.importMsp')">
        <VCardText>
          <VFileInput :label="t('pj.wbs.mspFile')" accept=".xml" prepend-icon="" prepend-inner-icon="ri-file-code-line" @update:model-value="onMspFile" />
          <VCheckbox v-model="msp.replace" :label="t('pj.wbs.replace')" density="compact" hide-details />
          <VAlert v-if="msp.error" type="error" variant="tonal" density="compact" class="mt-2">{{ msp.error }}</VAlert>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="msp.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :loading="msp.saving" :disabled="!msp.file" @click="runMsp">{{ t('pj.wbs.importRun') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>

    <VDialog v-model="baseline.open" max-width="420">
      <VCard :title="t('pj.wbs.saveBaseline')">
        <VCardText>
          <VTextField v-model="baseline.name" :label="t('pj.wbs.baselineName')" :placeholder="t('pj.wbs.baselinePlaceholder')" autofocus />
          <div class="text-caption text-muted mt-2">{{ t('pj.wbs.baselineHint') }}</div>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="baseline.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :loading="baseline.saving" :disabled="!baseline.name.trim()" @click="saveBaseline">{{ t('common.save') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>

    <ExcelImportDialog v-if="projectId" v-model="excelOpen" dataset-id="wbs" :params="{ projectId }" :title="t('pj.wbs.importExcel')" @committed="reload" />
    <ConfirmDialog
      v-model="leave.open"
      :title="t('pj.wbs.leaveTitle')"
      :message="t('pj.wbs.leaveConfirm')"
      :confirm-text="t('pj.wbs.leaveOk')"
      color="warning"
      @confirm="confirmLeave"
    />
    <ConfirmDialog
      v-model="removeOpen"
      :title="t('pj.wbs.removeTitle')"
      :message="t('pj.wbs.removeMessage', { name: selected?.name ?? '' })"
      :confirm-text="t('common.delete')"
      @confirm="removeSelected"
    />
  </VContainer>
</template>

<style scoped>
.kpi {
  display: flex;
  flex-direction: column;
  min-width: 64px;
}

.kpi-label {
  font-size: 12px;
  color: rgb(var(--v-theme-muted));
}

.kpi b {
  font-size: 20px;
  font-variant-numeric: tabular-nums;
}

.toolbar {
  row-gap: 4px;
}

.wbs-split {
  display: flex;
  align-items: flex-start;
}

.wbs-grid {
  flex: 0 0 auto;
  width: 700px;
  max-width: 55%;
  overflow: auto;
  max-height: max(360px, calc(100vh - 320px));
  border-right: 1px solid rgb(var(--v-theme-line));
}

.wbs-gantt {
  flex: 1 1 auto;
  min-width: 0;
  overflow: auto;
  max-height: max(360px, calc(100vh - 320px));
}

/* 세로 스크롤 시 헤더 고정 (그리드 thead · 간트 눈금) */
.grid thead th {
  position: sticky;
  top: 0;
  z-index: 2;
}

.grid {
  border-collapse: collapse;
  width: 100%;
  min-width: 700px;
  font-size: 12.5px;
  table-layout: fixed;
}

.grid th {
  text-align: left;
  font-weight: 600;
  color: rgb(var(--v-theme-muted));
  padding: 0 6px;
  border-bottom: 1px solid rgb(var(--v-theme-line));
  background: rgb(var(--v-theme-surface));
  white-space: nowrap;
}

.grid td {
  padding: 0 6px;
  border-bottom: 1px solid rgba(var(--v-theme-line), 0.6);
  white-space: nowrap;
  overflow: hidden;
}

.grid tbody tr {
  cursor: pointer;
}

.grid tbody tr:nth-child(even) {
  background: rgba(var(--v-theme-on-surface), 0.025);
}

.grid tr.selected {
  background: rgba(var(--v-theme-primary), 0.08) !important;
}

.grid tr.parent {
  font-weight: 600;
}

.grid tr.delayed .c-date {
  color: rgb(var(--v-theme-error));
}

.c-no { width: 54px; }
.c-name { width: 232px; }
.c-date { width: 116px; }
.c-num { width: 50px; text-align: right; }
.c-who { width: 82px; }
.c-status { width: 84px; }

.tree-toggle {
  display: inline-flex;
  width: 18px;
  flex: 0 0 18px;
  justify-content: center;
  border: 0;
  background: none;
  color: inherit;
  cursor: pointer;
}

.cell-input {
  width: 100%;
  border: 1px solid transparent;
  border-radius: 4px;
  padding: 3px 4px;
  font: inherit;
  color: inherit;
  background: transparent;
}

.cell-input:hover {
  border-color: rgb(var(--v-theme-line));
}

.cell-input:focus {
  outline: none;
  border-color: rgb(var(--v-theme-primary));
  background: rgb(var(--v-theme-surface));
}

.cell-input.num {
  text-align: right;
  appearance: textfield;
  -moz-appearance: textfield;
}

.cell-input.num::-webkit-outer-spin-button,
.cell-input.num::-webkit-inner-spin-button {
  -webkit-appearance: none;
  margin: 0;
}

.cell-input.name {
  font-weight: inherit;
  min-width: 0;
}

select.cell-input option {
  background: rgb(var(--v-theme-surface));
  color: rgb(var(--v-theme-on-surface));
}

.legend .lg {
  display: inline-block;
  width: 18px;
  height: 8px;
  border-radius: 2px;
  margin-right: 4px;
  vertical-align: middle;
}

.lg.bar { background: rgb(var(--v-theme-primary)); }
.lg.crit { background: rgb(var(--v-theme-error)); }
.lg.base { background: rgba(var(--v-theme-on-surface), 0.28); height: 4px; }
.lg.today { border-top: 2px dashed rgb(var(--v-theme-error)); height: 0; }

@media (max-width: 959px) {
  .wbs-split {
    flex-direction: column;
  }

  .wbs-grid {
    width: 100%;
    max-width: 100%;
    border-right: 0;
    border-bottom: 1px solid rgb(var(--v-theme-line));
  }

  .wbs-gantt {
    width: 100%;
  }
}
</style>
