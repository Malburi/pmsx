<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { MenuNode } from '@/types/api'
import { statusColor } from '@/utils/asis'

const props = defineProps<{ node: MenuNode; selectedScreenId: string | null; depth: number }>()
const emit = defineEmits<{ select: [screenId: string] }>()

const { t } = useI18n()
const expanded = ref(true)

// ─────────── 헬퍼 ───────────
const onClick = () => {
  if (props.node.screen) emit('select', props.node.screen.screenId)
  else expanded.value = !expanded.value
}

// ─────────── computed ───────────
const hasChildren = computed(() => props.node.children.length > 0)
const isSelected = computed(() => !!props.node.screen && props.node.screen.screenId === props.selectedScreenId)
</script>

<template>
  <li role="treeitem" :aria-expanded="hasChildren ? expanded : undefined" :aria-selected="isSelected">
    <div class="tnode" :class="{ sel: isSelected }" :style="{ paddingLeft: `${8 + depth * 14}px` }">
      <button
        v-if="hasChildren"
        type="button"
        class="toggle"
        :aria-label="expanded ? t('asis.viewer.tree.collapse') : t('asis.viewer.tree.expand')"
        @click.stop="expanded = !expanded"
      >
        <VIcon :icon="expanded ? 'ri-arrow-down-s-line' : 'ri-arrow-right-s-line'" size="16" />
      </button>
      <span v-else class="toggle-space" />
      <button type="button" class="label" @click="onClick">
        <span
          v-if="node.screen"
          class="dot"
          :class="`bg-${statusColor(node.screen.status)}`"
          :title="t(`asis.status.${node.screen.status}`)"
        />
        <VIcon v-else icon="ri-folder-3-line" size="15" class="text-muted" />
        <span class="name">{{ node.name }}</span>
        <span v-if="node.screen" class="code font-mono">{{ node.screen.screenCode }}</span>
      </button>
    </div>
    <ul v-if="hasChildren && expanded" role="group">
      <AsisMenuTreeItem
        v-for="child in node.children"
        :key="child.menuId"
        :node="child"
        :selected-screen-id="selectedScreenId"
        :depth="depth + 1"
        @select="(id: string) => emit('select', id)"
      />
    </ul>
  </li>
</template>

<style scoped>
ul {
  list-style: none;
  margin: 0;
  padding: 0;
}

.tnode {
  display: flex;
  align-items: center;
  border-radius: 6px;
  min-height: 30px;
}

.tnode:hover {
  background: rgb(var(--v-theme-surface-2));
}

.tnode.sel {
  background: rgba(var(--v-theme-primary), 0.12);
  color: rgb(var(--v-theme-primary));
  font-weight: 600;
}

.toggle,
.toggle-space {
  width: 20px;
  flex: none;
  display: inline-flex;
  justify-content: center;
  background: none;
  border: 0;
  color: inherit;
  cursor: pointer;
}

.label {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  background: none;
  border: 0;
  color: inherit;
  font: inherit;
  text-align: left;
  padding: 4px 6px 4px 2px;
  cursor: pointer;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: none;
}

.name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.code {
  font-size: 11px;
  color: rgb(var(--v-theme-muted));
  flex: none;
}
</style>
