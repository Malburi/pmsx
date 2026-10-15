<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { getNodeUsageApi } from '@/api/asisApi'
import AsisCallTree from '@/components/asis/AsisCallTree.vue'
import AsisNotesPanel from '@/components/asis/AsisNotesPanel.vue'
import { useApi } from '@/composables/useApi'
import type { NodeDetail, NodeUsage } from '@/types/api'

// 노드(함수·SQL·테이블 등) 상세 — 소스 / 호출 트리 / 사용처 / 메모
// systemId 를 주면 영향도 분석(SCR-AS-009) 진입 버튼을 보여 준다
const props = defineProps<{ node: NodeDetail | null; loading: boolean; showUsedBy?: boolean; systemId?: string | null }>()
const emit = defineEmits<{ openElement: [screenId: string, elementId: string]; selectNode: [nodeId: string] }>()

const { t } = useI18n()
const tab = ref<'source' | 'calls' | 'usage' | 'notes'>('source')
const { data: usage, loading: usageLoading, execute: loadUsage } = useApi<NodeUsage[]>((id: string) => getNodeUsageApi(id))

// ─────────── computed ───────────
const lines = computed(() => {
  const n = props.node
  if (!n?.snippet) return []
  const start = n.lineStart ?? 1
  return n.snippet.split('\n').map((text, i) => ({ no: start + i, text }))
})
const location = computed(() => {
  const n = props.node
  if (!n?.file) return n?.key ?? ''
  return n.lineStart ? `${n.file}:${n.lineStart}` : n.file
})
// 호출 트리는 함수·API·SQL·프로시저에서만 의미가 있다
const hasCallTree = computed(() => !!props.node && props.node.nodeType !== 'TABLE' && props.node.nodeType !== 'SCREEN')

// ─────────── watch ───────────
watch(() => props.node?.nodeId, () => {
  usage.value = null
  if (tab.value === 'calls' && !hasCallTree.value) tab.value = 'source'
  if (tab.value === 'usage' && props.node) void loadUsage(props.node.nodeId)
})

watch(tab, (v) => {
  if (v === 'usage' && props.node && !usage.value) void loadUsage(props.node.nodeId)
})
</script>

<template>
  <div class="node-detail">
    <VProgressLinear v-if="loading" indeterminate color="primary" class="mb-2" />
    <template v-else-if="node">
      <div class="d-flex align-center ga-2 mb-1">
        <VChip size="x-small" variant="outlined" label>{{ t(`asis.edge.nodeTypes.${node.nodeType}`) }}</VChip>
        <span class="font-mono text-body-2 font-weight-medium text-truncate" :title="node.name">{{ node.name }}</span>
        <VSpacer />
        <VBtn
          v-if="systemId && node.nodeType !== 'SCREEN'"
          :to="{ path: '/asis/impact', query: { systemId, targetId: node.nodeId } }"
          size="x-small"
          variant="tonal"
          prepend-icon="ri-git-branch-line"
          data-test="node-impact"
        >{{ t('impact.open') }}</VBtn>
      </div>
      <VTabs v-model="tab" density="compact" color="primary" class="mb-2 node-tabs">
        <VTab value="source" size="small">{{ node.nodeType === 'TABLE' ? t('asis.usage.title') : t('asis.viewer.node.sourceTab') }}</VTab>
        <VTab v-if="hasCallTree" value="calls" size="small" data-test="node-tab-calls">{{ t('asis.callTree.title') }}</VTab>
        <VTab v-if="node.nodeType !== 'TABLE'" value="usage" size="small">{{ t('asis.usage.title') }}</VTab>
        <VTab value="notes" size="small">{{ t('asis.notes.tab') }}</VTab>
      </VTabs>

      <template v-if="tab === 'source'">
        <div v-if="node.nodeType !== 'TABLE'" class="snip border rounded-lg">
          <div class="snip-head d-flex justify-space-between ga-2 px-3 py-1 text-caption text-muted">
            <span class="font-mono text-truncate" :title="location">{{ location }}</span>
            <span v-if="node.lang">{{ node.lang.toUpperCase() }}</span>
          </div>
          <pre v-if="lines.length" class="code font-mono"><code><span v-for="l in lines" :key="l.no" class="ln"><span class="no">{{ l.no }}</span>{{ l.text || ' ' }}</span></code></pre>
          <div v-else class="pa-3 text-caption text-muted">{{ t('asis.viewer.node.noSnippet') }}</div>
        </div>

        <div v-if="showUsedBy !== false && node.usedBy.length" class="mt-3">
          <div class="text-caption text-muted mb-1">{{ t('asis.viewer.node.usedBy', { name: node.name, n: node.usedBy.length }) }}</div>
          <VList density="compact" class="py-0 border rounded-lg">
            <VListItem
              v-for="u in node.usedBy"
              :key="u.elementId"
              :title="`${u.screenName} › ${u.elementLabel}`"
              prepend-icon="ri-cursor-line"
              @click="emit('openElement', u.screenId, u.elementId)"
            />
          </VList>
        </div>
      </template>

      <AsisCallTree v-else-if="tab === 'calls'" :node-id="node.nodeId" @select="(id: string) => emit('selectNode', id)" />

      <template v-else-if="tab === 'usage'">
        <VProgressLinear v-if="usageLoading" indeterminate color="primary" />
        <div v-else-if="!usage?.length" class="text-body-2 text-muted">{{ t('asis.usage.empty') }}</div>
        <template v-else>
          <div class="text-caption text-muted mb-1">{{ t('asis.usage.count', { n: usage.length }) }}</div>
          <VList density="compact" class="py-0 border rounded-lg" data-test="node-usage">
            <VListItem
              v-for="(u, i) in usage"
              :key="`${u.elementId}-${i}`"
              :title="`${u.screenName} › ${u.elementLabel}`"
              :subtitle="u.path.join(' → ')"
              prepend-icon="ri-cursor-line"
              @click="emit('openElement', u.screenId, u.elementId)"
            />
          </VList>
        </template>
      </template>

      <AsisNotesPanel v-else target-type="NODE" :target-id="node.nodeId" />
    </template>
  </div>
</template>

<style scoped>
.node-tabs {
  border-bottom: 1px solid rgb(var(--v-theme-line));
}

.snip-head {
  background: rgb(var(--v-theme-surface-2));
}

.code {
  margin: 0;
  padding: 10px 0;
  background: rgb(var(--v-theme-code-bg));
  color: rgb(var(--v-theme-code-fg));
  font-size: 12px;
  line-height: 1.55;
  overflow: auto;
  max-height: 300px;
  border-radius: 0 0 8px 8px;
}

.ln {
  display: block;
  padding-right: 12px;
  white-space: pre;
}

.no {
  display: inline-block;
  width: 3.2em;
  padding-right: 0.8em;
  text-align: right;
  opacity: 0.45;
  user-select: none;
}
</style>
