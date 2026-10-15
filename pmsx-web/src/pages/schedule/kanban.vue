<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { getWbsApi, patchWbsApi } from '@/api/scheduleApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useAuthStore } from '@/stores/auth'
import type { ApiError } from '@/types/api'
import type { WbsItem, WbsStatus } from '@/types/r2'
import { shortDay, today } from '@/utils/date'
import { WBS_EDIT_ROLES } from '@/utils/permission'
import { statusColorOf, WBS_STATUSES } from '@/utils/quality'
import { showErrorToast } from '@/utils/toast'
import { hasChildren, isDelayed } from '@/utils/wbs'

// SCR-PJ-002 칸반 (FR-PJ-007) — 하위 작업(잎)만 카드로, 열 사이를 끌어 상태를 바꾼다
const { t } = useI18n()
const auth = useAuthStore()

const items = ref<WbsItem[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const q = ref('')
const mine = ref(false)
const dragId = ref<string | null>(null)
const overCol = ref<WbsStatus | null>(null)

// ─────────── 헬퍼 ───────────
const load = async (projectId: string) => {
  loading.value = true
  error.value = null
  try {
    items.value = await getWbsApi(projectId)
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const { projectId } = useCurrentProject(load)

const moveTo = async (id: string, status: WbsStatus) => {
  const item = items.value.find((i) => i.wbsId === id)
  if (!item || item.status === status) return
  const prev = item.status
  item.status = status // 낙관적 반영
  try {
    const updated = await patchWbsApi(id, { status })
    items.value = items.value.map((i) => (i.wbsId === id ? updated : i))
  } catch {
    item.status = prev
    showErrorToast('common.saveFailed')
  }
}

const onDragStart = (e: DragEvent, id: string) => {
  dragId.value = id
  e.dataTransfer?.setData('text/plain', id)
  if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move'
}

const onDrop = (status: WbsStatus) => {
  if (dragId.value) void moveTo(dragId.value, status)
  dragId.value = null
  overCol.value = null
}

// 키보드 대안: 카드에서 Alt+←/→ 로 이웃 열로 옮긴다
const onCardKey = (e: KeyboardEvent, item: WbsItem) => {
  if (!e.altKey || !canEdit.value) return
  const idx = WBS_STATUSES.indexOf(item.status)
  const next = e.key === 'ArrowRight' ? WBS_STATUSES[idx + 1] : e.key === 'ArrowLeft' ? WBS_STATUSES[idx - 1] : undefined
  if (next) { e.preventDefault(); void moveTo(item.wbsId, next) }
}

// ─────────── computed ───────────
const canEdit = computed(() => auth.hasAnyRole(WBS_EDIT_ROLES))
const leaves = computed(() => items.value.filter((i) => !hasChildren(items.value, i.wbsId) && !i.milestone))
const filtered = computed(() => {
  const needle = q.value.trim().toLowerCase()
  return leaves.value.filter((i) => (!needle || `${i.wbsNo} ${i.name}`.toLowerCase().includes(needle))
    && (!mine.value || i.assignees.some((a) => a.userId === auth.userId)))
})
const columns = computed(() => WBS_STATUSES.map((s) => ({ status: s, cards: filtered.value.filter((i) => i.status === s) })))
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.schedule.kanban')" :subtitle="t('pj.kanban.subtitle')">
      <VTextField v-model="q" :label="t('common.search')" prepend-inner-icon="ri-search-line" clearable hide-details class="search" />
      <VCheckbox v-model="mine" :label="t('pj.kanban.mine')" density="compact" hide-details />
    </AppPageHeader>
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <template v-else>
      <VAlert v-if="error" type="error" variant="tonal" density="compact" class="mb-3">{{ error }}</VAlert>
      <VProgressLinear v-if="loading" indeterminate color="primary" class="mb-2" />
      <div class="board" data-test="kanban-board">
        <section
          v-for="col in columns"
          :key="col.status"
          :class="['column', { over: overCol === col.status }]"
          :aria-label="t(`pj.status.${col.status}`)"
          :data-test="`kanban-col-${col.status}`"
          @dragover.prevent="overCol = col.status"
          @dragleave="overCol = null"
          @drop.prevent="onDrop(col.status)"
        >
          <header class="d-flex align-center ga-2 mb-2">
            <span class="dot" :class="`bg-${statusColorOf(col.status)}`" />
            <b>{{ t(`pj.status.${col.status}`) }}</b>
            <VChip size="x-small" variant="tonal">{{ col.cards.length }}</VChip>
          </header>
          <article
            v-for="c in col.cards"
            :key="c.wbsId"
            class="card"
            :class="{ crit: c.critical, delayed: isDelayed(c, today()) }"
            :draggable="canEdit"
            tabindex="0"
            :data-test="`kanban-card-${c.wbsId}`"
            @dragstart="onDragStart($event, c.wbsId)"
            @dragend="dragId = null"
            @keydown="onCardKey($event, c)"
          >
            <div class="d-flex align-center ga-1 text-caption text-muted">
              <span class="font-mono">{{ c.wbsNo }}</span>
              <VIcon v-if="c.critical" icon="ri-fire-line" size="13" color="error" :title="t('pj.wbs.critical')" />
              <VSpacer />
              <span :class="{ 'text-error': isDelayed(c, today()) }">{{ shortDay(c.planStart) }} ~ {{ shortDay(c.planEnd) }}</span>
            </div>
            <div class="font-weight-medium my-1">{{ c.name }}</div>
            <VProgressLinear :model-value="c.progress" height="4" rounded :color="c.progress >= 100 ? 'success' : 'primary'" />
            <div class="d-flex align-center mt-2 text-caption">
              <VIcon icon="ri-user-line" size="13" class="mr-1" />
              <span class="text-truncate">{{ c.assignees.map((a) => a.name).join(', ') || t('pj.kanban.unassigned') }}</span>
              <VSpacer />
              <span>{{ c.progress }}%</span>
            </div>
          </article>
          <div v-if="!col.cards.length" class="empty text-caption text-muted">{{ t('pj.kanban.empty') }}</div>
        </section>
      </div>
      <div v-if="canEdit" class="text-caption text-muted mt-2">{{ t('pj.kanban.hint') }}</div>
    </template>
  </VContainer>
</template>

<style scoped>
.search {
  min-width: 200px;
}

.board {
  display: grid;
  grid-template-columns: repeat(4, minmax(220px, 1fr));
  gap: 12px;
  overflow-x: auto;
  padding-bottom: 4px;
}

.column {
  background: rgba(var(--v-theme-on-surface), 0.03);
  border: 1px solid rgb(var(--v-theme-line));
  border-radius: 10px;
  padding: 10px;
  min-height: 240px;
  transition: background 0.15s;
}

.column.over {
  background: rgba(var(--v-theme-primary), 0.08);
  border-color: rgb(var(--v-theme-primary));
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.card {
  background: rgb(var(--v-theme-surface));
  border: 1px solid rgb(var(--v-theme-line));
  border-left: 3px solid rgb(var(--v-theme-primary));
  border-radius: 8px;
  padding: 8px 10px;
  margin-bottom: 8px;
  cursor: grab;
}

.card:focus-visible {
  outline: 2px solid rgb(var(--v-theme-primary));
}

.card.crit {
  border-left-color: rgb(var(--v-theme-error));
}

.empty {
  text-align: center;
  padding: 24px 0;
}

@media (max-width: 599px) {
  .board {
    grid-template-columns: repeat(4, minmax(240px, 1fr));
  }
}
</style>
