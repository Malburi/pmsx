<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { searchAsisApi } from '@/api/asisApi'
import AsisMenuTreeItem from '@/components/asis/AsisMenuTreeItem.vue'
import type { MenuNode, SearchHit, SystemSummary } from '@/types/api'

const props = defineProps<{
  systemId: string
  system: SystemSummary | null
  nodes: MenuNode[]
  loading: boolean
  selectedScreenId: string | null
}>()
const emit = defineEmits<{ select: [screenId: string, elementId: string | null] }>()

const { t } = useI18n()
const query = ref('')
const hits = ref<SearchHit[]>([])
const searching = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null
let seq = 0

// ─────────── 헬퍼 ───────────
const runSearch = async (q: string) => {
  const mine = ++seq
  if (!q.trim()) { hits.value = []; return }
  searching.value = true
  try {
    const result = await searchAsisApi(props.systemId, q.trim())
    if (mine === seq) hits.value = result
  } catch {
    if (mine === seq) hits.value = []
  } finally {
    if (mine === seq) searching.value = false
  }
}

const pickHit = (hit: SearchHit) => {
  if (hit.screenId) emit('select', hit.screenId, hit.elementId)
}

const hitIcon = (kind: SearchHit['kind']) =>
  kind === 'SCREEN' ? 'ri-window-line' : kind === 'ELEMENT' ? 'ri-cursor-line' : 'ri-function-line'

// ─────────── computed ───────────
const isSearching = computed(() => query.value.trim().length > 0)
const donePct = computed(() => {
  const s = props.system
  return s && s.screenCount ? Math.round((s.doneCount / s.screenCount) * 100) : 0
})

// ─────────── watch ───────────
// 입력할 때마다 호출하지 않도록 300ms debounce (forms-validation §2-4)
watch(query, (q) => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => { void runSearch(q) }, 300)
})

watch(() => props.systemId, () => { query.value = ''; hits.value = [] })

// ─────────── 라이프사이클 ───────────
onBeforeUnmount(() => { if (timer) clearTimeout(timer) })
</script>

<template>
  <div class="menu-tree d-flex flex-column">
    <div class="pa-3 border-b border-line">
      <VTextField
        v-model="query"
        :placeholder="t('asis.viewer.searchPlaceholder')"
        prepend-inner-icon="ri-search-line"
        clearable
        :loading="searching"
        data-test="asis-search"
      />
    </div>

    <div class="tree-body flex-grow-1 py-2 pr-2">
      <template v-if="isSearching">
        <VList density="compact" class="py-0 bg-transparent">
          <VListItem
            v-for="hit in hits"
            :key="`${hit.kind}-${hit.id}`"
            :prepend-icon="hitIcon(hit.kind)"
            :title="hit.label"
            :subtitle="hit.sub"
            :disabled="!hit.screenId"
            rounded="lg"
            @click="pickHit(hit)"
          />
        </VList>
        <div v-if="!searching && hits.length === 0" class="pa-4 text-body-2 text-muted">
          {{ t('asis.viewer.searchEmpty') }}
        </div>
      </template>
      <template v-else>
        <VProgressLinear v-if="loading" indeterminate color="primary" />
        <ul v-else-if="nodes.length" role="tree" :aria-label="t('asis.viewer.tree.label')" class="tree-root">
          <AsisMenuTreeItem
            v-for="node in nodes"
            :key="node.menuId"
            :node="node"
            :selected-screen-id="selectedScreenId"
            :depth="0"
            @select="(id: string) => emit('select', id, null)"
          />
        </ul>
        <div v-else class="pa-4 text-body-2 text-muted">{{ t('asis.viewer.noMenu') }}</div>
      </template>
    </div>

    <div v-if="system" class="pa-3 border-t border-line text-caption text-muted">
      {{ t('asis.viewer.progress', { name: system.name, done: system.doneCount, total: system.screenCount, pct: donePct }) }}
      <VProgressLinear :model-value="donePct" color="primary" height="6" rounded class="mt-1" />
    </div>
  </div>
</template>

<style scoped>
.menu-tree {
  height: 100%;
  min-height: 0;
  font-size: 0.875rem;
}

.tree-body {
  overflow: auto;
  min-height: 0;
}

.tree-root {
  list-style: none;
  margin: 0;
  padding: 0;
}
</style>
