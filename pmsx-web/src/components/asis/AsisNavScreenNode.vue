<script setup lang="ts">
import { Handle, Position } from '@vue-flow/core'
import { useI18n } from 'vue-i18n'
import type { NavigationMap } from '@/types/api'
import { statusColor } from '@/utils/asis'

// 화면 이동 맵 노드 — 화면명·화면 ID·분석 상태
defineProps<{ data: { screen: NavigationMap['nodes'][number] } }>()

const { t } = useI18n()
</script>

<template>
  <div class="nav-node" :class="{ popup: data.screen.kind === 'popup' }" :style="{ '--sc': `var(--v-theme-${statusColor(data.screen.status)})` }">
    <Handle type="target" :position="Position.Left" class="nav-handle" />
    <div class="nav-name" :title="data.screen.name">{{ data.screen.name }}</div>
    <div class="nav-meta">
      <span class="font-mono">{{ data.screen.screenCode }}</span>
      <span class="nav-status">{{ t(`asis.status.${data.screen.status}`) }}</span>
    </div>
    <Handle type="source" :position="Position.Right" class="nav-handle" />
  </div>
</template>

<style scoped>
.nav-node {
  width: 200px;
  height: 56px;
  padding: 7px 12px 7px 14px;
  border: 1px solid rgba(var(--v-theme-line), 0.8);
  border-radius: 14px;
  box-shadow: inset 3px 0 0 rgb(var(--sc)), var(--pmsx-shadow-soft);
  background: rgb(var(--v-theme-surface));
  color: rgb(var(--v-theme-on-surface));
  cursor: pointer;
}

.nav-node.popup {
  border-style: dashed;
}

.nav-node:hover {
  border-color: rgb(var(--v-theme-primary));
}

.nav-name {
  font-weight: 600;
  font-size: 12.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-meta {
  display: flex;
  justify-content: space-between;
  gap: 6px;
  font-size: 11px;
  color: rgb(var(--v-theme-muted));
}

.nav-status {
  color: rgb(var(--sc));
  font-weight: 600;
}

.nav-handle {
  opacity: 0;
}
</style>
