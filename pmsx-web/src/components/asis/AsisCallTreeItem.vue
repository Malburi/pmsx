<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { CallTreeNode } from '@/types/api'

defineOptions({ name: 'AsisCallTreeItem' })

const props = defineProps<{ node: CallTreeNode; level: number }>()
const emit = defineEmits<{ select: [nodeId: string] }>()

const { t } = useI18n()
// 첫 두 단계만 펼쳐 둔다 — 깊은 트리가 패널을 다 덮지 않게
const open = ref(props.level < 2)
</script>

<template>
  <li class="ct-item" role="treeitem" :aria-expanded="node.children.length ? open : undefined">
    <div class="ct-row" :style="{ paddingLeft: `${level * 14}px` }">
      <button
        v-if="node.children.length"
        type="button"
        class="ct-toggle"
        :aria-label="open ? t('asis.viewer.tree.collapse') : t('asis.viewer.tree.expand')"
        @click="open = !open"
      >
        <VIcon :icon="open ? 'ri-arrow-down-s-line' : 'ri-arrow-right-s-line'" size="14" />
      </button>
      <span v-else class="ct-toggle" aria-hidden="true" />
      <button type="button" class="ct-name font-mono" :title="node.file ?? node.name" @click="emit('select', node.nodeId)">
        <span class="ct-type">{{ t(`asis.edge.nodeTypes.${node.nodeType}`, node.nodeType) }}</span>
        {{ node.name }}
      </button>
      <span v-if="node.edgeKind" class="text-caption text-muted ml-1">{{ node.edgeKind }}</span>
    </div>
    <div v-if="node.truncated" class="text-caption text-muted" :style="{ paddingLeft: `${(level + 1) * 14 + 18}px` }">
      {{ t('asis.callTree.truncated') }}
    </div>
    <ul v-if="open && node.children.length" role="group" class="ct-list">
      <AsisCallTreeItem
        v-for="(c, i) in node.children"
        :key="`${c.nodeId}-${i}`"
        :node="c"
        :level="level + 1"
        @select="(id: string) => emit('select', id)"
      />
    </ul>
  </li>
</template>

<style scoped>
.ct-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.ct-row {
  display: flex;
  align-items: center;
  min-width: 0;
  padding-top: 1px;
  padding-bottom: 1px;
}

.ct-toggle {
  flex: none;
  width: 18px;
  height: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  background: none;
  color: inherit;
  cursor: pointer;
}

.ct-name {
  min-width: 0;
  border: 0;
  background: none;
  color: inherit;
  font-size: 12px;
  text-align: left;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ct-name:hover {
  color: rgb(var(--v-theme-primary));
}

.ct-type {
  font-size: 10px;
  padding: 0 4px;
  margin-right: 4px;
  border: 1px solid rgb(var(--v-theme-line));
  border-radius: 3px;
  color: rgb(var(--v-theme-muted));
}
</style>
