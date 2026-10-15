<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { globalSearchApi } from '@/api/dashboardApi'
import { useProjectStore } from '@/stores/project'
import type { GlobalSearchHit } from '@/types/r2'

// GNB 통합 검색 (FR-CM-009) — 요구사항·WBS·이슈·결함·CR·산출물·회의록·테스트
const { t } = useI18n()
const router = useRouter()
const store = useProjectStore()
const hits = ref<GlobalSearchHit[]>([])
const loading = ref(false)
const model = ref<GlobalSearchHit | null>(null)
let timer: ReturnType<typeof setTimeout> | null = null
let seq = 0

const ROUTES: Partial<Record<GlobalSearchHit['type'], (id: string) => string | { path: string; query: Record<string, string> }>> = {
  REQ: () => '/deliverable/requirements',
  WBS: () => '/schedule/wbs',
  ISSUE: (id) => ({ path: '/quality/issues', query: { id } }),
  DEFECT: (id) => ({ path: '/quality/defects', query: { id } }),
  CR: () => '/quality/change-requests',
  DELIVERABLE: () => '/deliverable/list',
  MEETING: () => '/quality/meetings',
  TC: () => '/quality/tests',
}
const ICONS: Partial<Record<GlobalSearchHit['type'], string>> = {
  REQ: 'ri-file-list-2-line', WBS: 'ri-node-tree', ISSUE: 'ri-error-warning-line', DEFECT: 'ri-bug-line', CR: 'ri-git-pull-request-line',
  DELIVERABLE: 'ri-file-text-line', MEETING: 'ri-team-line', TC: 'ri-test-tube-line',
}

const search = (q: string | null) => {
  if (timer) clearTimeout(timer)
  const text = (q ?? '').trim()
  if (text.length < 2) { hits.value = []; return }
  timer = setTimeout(async () => {
    const mine = ++seq
    loading.value = true
    try {
      const res = await globalSearchApi(text, store.currentProjectId ?? undefined)
      if (mine === seq) hits.value = res
    } catch {
      if (mine === seq) hits.value = []
    } finally {
      if (mine === seq) loading.value = false
    }
  }, 300)
}

const go = async (hit: GlobalSearchHit | null) => {
  if (!hit) return
  const to = ROUTES[hit.type]?.(hit.id)
  model.value = null
  if (to) await router.push(to)
}

// Ctrl+K / ⌘K 로 검색창 포커스
const fieldRef = ref<{ focus: () => void } | null>(null)
const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)
const onShortcut = (e: KeyboardEvent) => {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    fieldRef.value?.focus()
  }
}

onMounted(() => window.addEventListener('keydown', onShortcut))
onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
  window.removeEventListener('keydown', onShortcut)
})
</script>

<template>
  <VAutocomplete
    ref="fieldRef"
    v-model="model"
    :items="hits"
    item-title="label"
    return-object
    :loading="loading"
    :placeholder="t('gsearch.placeholder')"
    :no-data-text="t('gsearch.noResult')"
    no-filter
    hide-details
    density="compact"
    variant="solo-filled"
    flat
    rounded="pill"
    prepend-inner-icon="ri-search-line"
    class="global-search"
    :aria-label="t('gsearch.label')"
    data-test="global-search"
    @update:search="search"
    @update:model-value="go"
  >
    <template #append-inner>
      <kbd class="gs-kbd" aria-hidden="true">{{ isMac ? '⌘K' : 'Ctrl K' }}</kbd>
    </template>
    <template #item="{ props: ip, item }">
      <VListItem v-bind="ip" :subtitle="item.raw.sub">
        <template #prepend><VIcon :icon="ICONS[item.raw.type] ?? 'ri-search-line'" size="16" /></template>
      </VListItem>
    </template>
  </VAutocomplete>
</template>

<style scoped>
.global-search {
  width: 300px;
  max-width: 340px;
  min-width: 200px;
}

.global-search :deep(.v-field) {
  border-radius: 999px !important;
  background: rgb(var(--v-theme-surface-2)) !important;
  font-size: 0.875rem;
}

.gs-kbd {
  font-family: var(--pmsx-font);
  font-size: 0.6875rem;
  font-weight: 600;
  color: rgb(var(--v-theme-muted));
  background: rgb(var(--v-theme-surface));
  border-radius: 6px;
  padding: 1px 6px;
  box-shadow: inset 0 0 0 1px rgb(var(--v-theme-line));
  white-space: nowrap;
}
</style>
