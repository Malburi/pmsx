<route lang="yaml">
meta:
  roles: [PMO, PM, PL]
</route>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { createAssignmentApi, getHeatmapApi, getUnassignedApi } from '@/api/resourceApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useAuthStore } from '@/stores/auth'
import type { ApiError } from '@/types/api'
import type { HeatmapRow, WorkItem, WorkTargetType } from '@/types/r2'
import { addDays, isoWeek, shortDay, today, weekStart } from '@/utils/date'
import { WORK_ASSIGN_ROLES } from '@/utils/permission'
import { allocLevel } from '@/utils/quality'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// SCR-RS-003 작업 배분 (FR-RS-005) — 미배정 작업을 인력 카드로 끌어다 놓아 배정한다
const { t } = useI18n()
const auth = useAuthStore()

const WEEKS = 6
const works = ref<WorkItem[]>([])
const people = ref<HeatmapRow[]>([])
const typeFilter = ref<WorkTargetType | null>(null)
const q = ref('')
const loading = ref(false)
const error = ref<string | null>(null)
const dragItem = ref<WorkItem | null>(null)
const overUser = ref<string | null>(null)
const dlg = reactive({ open: false, item: null as WorkItem | null, userId: null as string | null, allocPct: 50, saving: false, warning: null as string | null })

// ─────────── 헬퍼 ───────────
const load = async (projectId: string) => {
  loading.value = true
  error.value = null
  try {
    const ws = weekStart(today())
    const [w, h] = await Promise.all([
      getUnassignedApi(projectId),
      getHeatmapApi({ from: ws, to: addDays(ws, 7 * WEEKS - 1), unit: 'week' }),
    ])
    works.value = w
    people.value = h
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const { projectId } = useCurrentProject(load)

const openAssign = (item: WorkItem, userId: string | null) => {
  Object.assign(dlg, { open: true, item, userId, allocPct: 50, saving: false, warning: null })
}

const onDrop = (userId: string) => {
  overUser.value = null
  if (dragItem.value && canAssign.value) openAssign(dragItem.value, userId)
  dragItem.value = null
}

const assign = async () => {
  if (!dlg.item || !dlg.userId || !projectId.value) return
  dlg.saving = true
  try {
    const res = await createAssignmentApi({ targetType: dlg.item.type, targetId: dlg.item.id, userId: dlg.userId, allocPct: dlg.allocPct })
    if (res.warning) {
      dlg.warning = res.warning
      showErrorToast('rs.assign.overWarning')
    } else {
      showSuccessToast('rs.assign.done')
    }
    dlg.open = false
    await load(projectId.value)
  } catch {
    showErrorToast('common.saveFailed')
  } finally {
    dlg.saving = false
  }
}

const weekKey = (i: number) => addDays(weekStart(today()), 7 * i)
const projected = (userId: string) => {
  const cur = people.value.find((p) => p.userId === userId)?.cells[weekKey(0)] ?? 0
  return cur + dlg.allocPct
}

// ─────────── computed ───────────
const canAssign = computed(() => auth.hasAnyRole(WORK_ASSIGN_ROLES))
const shownWorks = computed(() => {
  const needle = q.value.trim().toLowerCase()
  return works.value.filter((w) => (!typeFilter.value || w.type === typeFilter.value) && (!needle || `${w.label} ${w.path}`.toLowerCase().includes(needle)))
})
const weekHeads = computed(() => Array.from({ length: WEEKS }, (_, i) => weekKey(i)))
const personItems = computed(() => people.value.map((p) => ({ title: p.name, value: p.userId })))
const typeItems = computed(() => (['WBS', 'SCREEN'] as const).map((v) => ({ title: t(`rs.assign.type.${v}`), value: v })))
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.resource.assign')" :subtitle="t('rs.assign.subtitle')" />
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <template v-else>
      <VAlert v-if="error" type="error" variant="tonal" density="compact" class="mb-3">{{ error }}</VAlert>
      <VProgressLinear v-if="loading" indeterminate color="primary" class="mb-2" />
      <VRow>
        <VCol cols="12" md="5">
          <VCard border flat>
            <VCardTitle class="d-flex align-center ga-2">
              {{ t('rs.assign.unassigned') }} <VChip size="x-small" variant="tonal">{{ shownWorks.length }}</VChip>
            </VCardTitle>
            <VCardText class="pb-2">
              <VRow dense>
                <VCol cols="6"><VSelect v-model="typeFilter" :items="typeItems" :label="t('rs.assign.typeLabel')" clearable hide-details /></VCol>
                <VCol cols="6"><VTextField v-model="q" :label="t('common.search')" prepend-inner-icon="ri-search-line" clearable hide-details /></VCol>
              </VRow>
            </VCardText>
            <div class="work-list" data-test="work-list">
              <div
                v-for="w in shownWorks"
                :key="`${w.type}-${w.id}`"
                class="work"
                :draggable="canAssign"
                tabindex="0"
                :data-test="`work-${w.id}`"
                @dragstart="dragItem = w"
                @dragend="dragItem = null"
                @keydown.enter="canAssign && openAssign(w, null)"
              >
                <VIcon :icon="w.type === 'WBS' ? 'ri-node-tree' : 'ri-layout-masonry-line'" size="16" class="mr-2" :color="w.type === 'WBS' ? 'primary' : 'info'" />
                <div class="flex-grow-1 min-w-0">
                  <div class="text-body-2 font-weight-medium text-truncate">{{ w.label }}</div>
                  <div class="text-caption text-muted text-truncate">{{ w.path }} · {{ shortDay(w.planStart) }} ~ {{ shortDay(w.planEnd) }}</div>
                </div>
                <VBtn v-if="canAssign" size="x-small" variant="text" icon :aria-label="t('rs.assign.assignTo')" @click="openAssign(w, null)"><VIcon icon="ri-user-add-line" /></VBtn>
              </div>
              <div v-if="!shownWorks.length" class="text-center text-muted text-body-2 pa-6">{{ t('rs.assign.allAssigned') }}</div>
            </div>
          </VCard>
        </VCol>
        <VCol cols="12" md="7">
          <VCard border flat>
            <VCardTitle>{{ t('rs.assign.people') }}</VCardTitle>
            <div class="people-wrap">
              <table class="people" data-test="people-table">
                <thead>
                  <tr>
                    <th class="sticky">{{ t('rs.heatmap.person') }}</th>
                    <th v-for="w in weekHeads" :key="w">{{ isoWeek(w).slice(5) }}<br><span class="text-muted">{{ shortDay(w) }}</span></th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="p in people"
                    :key="p.userId"
                    :class="{ over: overUser === p.userId }"
                    :data-test="`person-${p.userId}`"
                    @dragover.prevent="overUser = p.userId"
                    @dragleave="overUser = null"
                    @drop.prevent="onDrop(p.userId)"
                  >
                    <td class="sticky font-weight-medium">{{ p.name }}</td>
                    <td v-for="w in weekHeads" :key="w" :class="['cell', `lv-${allocLevel(p.cells[w])}`]">{{ p.cells[w] ?? 0 }}%</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div class="text-caption text-muted pa-3">{{ canAssign ? t('rs.assign.hint') : t('rs.assign.readonly') }}</div>
          </VCard>
        </VCol>
      </VRow>
    </template>

    <VDialog v-model="dlg.open" max-width="440">
      <VCard :title="t('rs.assign.dialogTitle')">
        <VCardText>
          <div class="text-body-2 mb-3">{{ dlg.item?.label }}</div>
          <VSelect v-model="dlg.userId" :items="personItems" :label="t('rs.assign.person')" class="mb-3" data-test="assign-person" />
          <div class="text-body-2">{{ t('rs.assign.alloc', { n: dlg.allocPct }) }}</div>
          <VSlider v-model="dlg.allocPct" :min="10" :max="100" :step="10" show-ticks="always" color="primary" hide-details :aria-label="t('rs.assign.allocLabel')" />
          <VAlert v-if="dlg.userId && projected(dlg.userId) > 100" type="warning" variant="tonal" density="compact" class="mt-2">
            {{ t('rs.assign.projectedOver', { n: projected(dlg.userId) }) }}
          </VAlert>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="dlg.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :disabled="!dlg.userId" :loading="dlg.saving" data-test="assign-confirm" @click="assign">{{ t('rs.assign.assign') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>
  </VContainer>
</template>

<style scoped>
.work-list {
  max-height: 560px;
  overflow-y: auto;
  border-top: 1px solid rgb(var(--v-theme-line));
}

.work {
  display: flex;
  align-items: center;
  padding: 8px 12px;
  border-bottom: 1px solid rgba(var(--v-theme-line), 0.7);
  cursor: grab;
}

.work:hover,
.work:focus-visible {
  background: rgba(var(--v-theme-primary), 0.05);
  outline: none;
}

.min-w-0 {
  min-width: 0;
}

.people-wrap {
  overflow-x: auto;
}

.people {
  border-collapse: collapse;
  font-size: 12px;
  width: 100%;
}

.people th,
.people td {
  border: 1px solid rgb(var(--v-theme-line));
  padding: 6px;
  text-align: center;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.people th {
  font-weight: 600;
  color: rgb(var(--v-theme-muted));
}

.people tr.over td {
  outline: 2px dashed rgb(var(--v-theme-primary));
  outline-offset: -2px;
}

.sticky {
  position: sticky;
  left: 0;
  background: rgb(var(--v-theme-surface));
  text-align: left !important;
  z-index: 1;
}

.lv-none { color: rgb(var(--v-theme-muted)); }
.lv-low { background: rgba(var(--v-theme-success), 0.14); }
.lv-normal { background: rgba(var(--v-theme-success), 0.32); }
.lv-full { background: rgba(var(--v-theme-warning), 0.38); }
.lv-over { background: rgba(var(--v-theme-error), 0.5); font-weight: 700; }
</style>
