<script setup lang="ts">
import { Handle, Position } from '@vue-flow/core'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ErdTable } from '@/types/api'
import { ERD_MAX_COLUMNS } from '@/utils/graph'

// ERD 테이블 노드 — 헤더(테이블명·코멘트) + 컬럼(PK·FK 표시). 왼쪽 = 참조받는 쪽, 오른쪽 = 참조하는 쪽
const props = defineProps<{ data: { table: ErdTable; highlight?: boolean } }>()

const { t } = useI18n()

// ─────────── computed ───────────
const shown = computed(() => props.data.table.columns.slice(0, ERD_MAX_COLUMNS))
const more = computed(() => Math.max(0, props.data.table.columns.length - ERD_MAX_COLUMNS))
</script>

<template>
  <div class="erd-node" :class="{ highlight: data.highlight }">
    <Handle type="target" :position="Position.Left" class="erd-handle" />
    <div class="erd-head">
      <div class="erd-name font-mono" :title="data.table.name">{{ data.table.name }}</div>
      <div class="erd-comment" :title="data.table.comment ?? ''">{{ data.table.comment ?? '' }}</div>
    </div>
    <ul class="erd-cols">
      <li v-for="c in shown" :key="c.name" class="erd-col">
        <span class="erd-key">
          <b v-if="c.pk" class="pk">PK</b>
          <b v-else-if="c.fk" class="fk">FK</b>
        </span>
        <span class="font-mono erd-col-name" :class="{ 'is-pk': c.pk }">{{ c.name }}</span>
        <span class="erd-type font-mono">{{ c.dataType }}</span>
      </li>
      <li v-if="more" class="erd-more">{{ t('asis.erd.moreColumns', { n: more }) }}</li>
    </ul>
    <Handle type="source" :position="Position.Right" class="erd-handle" />
  </div>
</template>

<style scoped>
.erd-node {
  width: 240px;
  border: 1px solid rgba(var(--v-theme-line), 0.8);
  border-radius: 14px;
  background: rgb(var(--v-theme-surface));
  color: rgb(var(--v-theme-on-surface));
  font-size: 11.5px;
  box-shadow: var(--pmsx-shadow-soft);
  overflow: hidden;
}

.erd-node.highlight {
  border-color: rgb(var(--v-theme-primary));
  box-shadow: 0 0 0 2px rgba(var(--v-theme-primary), 0.35);
}

.erd-head {
  padding: 7px 10px;
  background: rgb(var(--v-theme-pastel-blue));
  border-bottom: 1px solid rgb(var(--v-theme-line));
  height: 46px;
}

.erd-name {
  font-weight: 700;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.erd-comment {
  color: rgb(var(--v-theme-muted));
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.erd-cols {
  list-style: none;
  margin: 0;
  padding: 3px 0 5px;
}

.erd-col,
.erd-more {
  display: flex;
  align-items: center;
  gap: 4px;
  height: 21px;
  padding: 0 8px;
}

.erd-key {
  flex: none;
  width: 20px;
}

.erd-key b {
  font-size: 9px;
  padding: 0 2px;
  border-radius: 2px;
}

.pk {
  color: rgb(var(--v-theme-warning));
}

.fk {
  color: rgb(var(--v-theme-info));
}

.erd-col-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.erd-col-name.is-pk {
  font-weight: 700;
}

.erd-type {
  flex: none;
  max-width: 90px;
  color: rgb(var(--v-theme-muted));
  font-size: 10.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.erd-more {
  color: rgb(var(--v-theme-muted));
}

.erd-handle {
  opacity: 0;
}
</style>
