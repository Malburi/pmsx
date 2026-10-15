<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import AsisCrudBadges from '@/components/asis/AsisCrudBadges.vue'
import type { WorkflowStep } from '@/types/api'
import type { EdgeAction } from '@/types/asisUi'
import { WEAK_CONFIDENCE, laneColor, sourceColor } from '@/utils/asis'
import { formatPercent } from '@/utils/format'

withDefaults(defineProps<{ steps: WorkflowStep[]; selectedIndex: number | null; editable?: boolean }>(), { editable: false })
const emit = defineEmits<{
  select: [index: number]
  /** 연결 보정 (FR-AS-020) — 편집 모드에서만 */
  edgeAction: [index: number, action: EdgeAction]
  addFrom: [index: number]
}>()

const { t } = useI18n()

// 깊이 들여쓰기 상한 — 좁은 패널에서 카드가 너무 좁아지지 않게 한다
const MAX_INDENT_DEPTH = 6

// ─────────── 헬퍼 ───────────
const indentOf = (s: WorkflowStep) => `${Math.min(s.depth, MAX_INDENT_DEPTH) * 12}px`
// 사람이 확정한 연결은 신뢰도와 관계없이 실선 (docs/11 §1)
const isWeak = (s: WorkflowStep) => !!s.edge && !s.edge.confirmed && s.edge.confidence < WEAK_CONFIDENCE
const isRejected = (s: WorkflowStep) => s.edge?.status === 'rejected'
const laneVar = (s: WorkflowStep) => ({ '--lc': `var(--v-theme-${laneColor(s.lane)})` })
</script>

<template>
  <ol class="flow" :aria-label="t('asis.viewer.workflow.label')">
    <li v-for="(s, i) in steps" :key="i" class="flow-item" :style="laneVar(s)">
      <div v-if="i > 0" class="edge" :class="{ weak: isWeak(s), rejected: isRejected(s) }">
        <span class="line" aria-hidden="true" :style="{ marginLeft: `calc(${indentOf(s)} + 14px)` }" />
        <span v-if="s.edge" class="edge-meta" :style="{ paddingLeft: `calc(${indentOf(s)} + 24px)` }">
          <span class="src" :class="`text-${sourceColor(s.edge.source)}`">{{ t(`asis.source.${s.edge.source}`) }}</span>
          <span>{{ t('asis.viewer.workflow.confidence', { v: formatPercent(s.edge.confidence) }) }}</span>
          <VIcon v-if="s.edge.confirmed" icon="ri-checkbox-circle-fill" size="12" color="success" :title="t('asis.edge.confirmed')" />
          <span v-if="isRejected(s)" class="text-error">{{ t('asis.edge.rejected') }}</span>
          <span v-if="s.edge.condition" class="cond" :title="s.edge.condition">· {{ s.edge.condition }}</span>
          <VMenu v-if="editable && s.edge.edgeId" location="bottom end">
            <template #activator="{ props: menuProps }">
              <button v-bind="menuProps" type="button" class="edge-menu" :aria-label="t('asis.edge.menu')" :data-test="`edge-menu-${i}`">
                <VIcon icon="ri-more-2-fill" size="14" />
              </button>
            </template>
            <VList density="compact">
              <VListItem
                v-if="!s.edge.confirmed"
                prepend-icon="ri-checkbox-circle-line"
                :title="t('asis.edge.confirm')"
                data-test="edge-confirm"
                @click="emit('edgeAction', i, 'confirm')"
              />
              <VListItem v-else prepend-icon="ri-checkbox-blank-circle-line" :title="t('asis.edge.unconfirm')" @click="emit('edgeAction', i, 'unconfirm')" />
              <VListItem v-if="!isRejected(s)" prepend-icon="ri-close-circle-line" :title="t('asis.edge.reject')" @click="emit('edgeAction', i, 'reject')" />
              <VListItem v-else prepend-icon="ri-refresh-line" :title="t('asis.edge.activate')" @click="emit('edgeAction', i, 'activate')" />
              <VListItem prepend-icon="ri-edit-line" :title="t('asis.edge.editCondition')" @click="emit('edgeAction', i, 'edit')" />
              <VListItem
                v-if="s.edge.source === 'manual'"
                prepend-icon="ri-delete-bin-line"
                :title="t('asis.edge.delete')"
                base-color="error"
                @click="emit('edgeAction', i, 'delete')"
              />
            </VList>
          </VMenu>
        </span>
      </div>
      <div class="step">
        <span class="lane">{{ t(`asis.lane.${s.lane}`) }}</span>
        <button
          type="button"
          :style="{ marginLeft: indentOf(s), width: `calc(100% - ${indentOf(s)})` }"
          class="card"
          :class="{ sel: selectedIndex === i, clickable: !!s.nodeId, 'is-rejected': isRejected(s) }"
          :disabled="!s.nodeId"
          :aria-pressed="selectedIndex === i"
          @click="emit('select', i)"
        >
          <span class="title font-mono">
            {{ s.title }}
            <AsisCrudBadges v-if="s.crud" :crud="s.crud" class="ml-1" />
            <VIcon v-if="s.hasSource" icon="ri-code-s-slash-line" size="13" class="ml-1 text-muted" :title="t('asis.viewer.workflow.hasSource')" />
          </span>
          <span v-if="s.subtitle" class="sub">{{ s.subtitle }}</span>
          <span v-if="s.columns?.length" class="cols font-mono">{{ s.columns.join(', ') }}</span>
        </button>
      </div>
      <div v-if="editable && s.nodeId" class="add-row" :style="{ paddingLeft: `calc(60px + ${indentOf(s)})` }">
        <button type="button" class="add-btn" :data-test="`edge-add-${i}`" @click="emit('addFrom', i)">
          <VIcon icon="ri-add-line" size="12" />{{ t('asis.edge.addFromStep') }}
        </button>
      </div>
    </li>
  </ol>
</template>

<style scoped>
.flow {
  list-style: none;
  margin: 0;
  padding: 0;
}

.step {
  display: grid;
  grid-template-columns: 52px minmax(0, 1fr);
  gap: 8px;
  align-items: start;
}

.lane {
  font-size: 10.5px;
  font-weight: 700;
  color: rgb(var(--lc));
  text-align: right;
  padding-top: 8px;
  white-space: nowrap;
}

.card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  width: 100%;
  min-width: 0;
  text-align: left;
  padding: 8px 12px 8px 14px;
  border: 0;
  border-radius: 12px;
  /* 클린 벤토: 테두리 대신 왼쪽 레이어 색 띠(inset) + 연회색 면 */
  box-shadow: inset 3px 0 0 rgb(var(--lc));
  background: rgb(var(--v-theme-surface-2));
  color: inherit;
  font: inherit;
  transition: background 0.15s ease;
}

.card.clickable {
  cursor: pointer;
}

.card.clickable:hover {
  background: rgba(var(--lc), 0.1);
}

.card:disabled {
  cursor: default;
  opacity: 1;
}

.card.sel {
  outline: 2px solid rgb(var(--lc));
  outline-offset: 1px;
  background: rgba(var(--lc), 0.1);
}

.title {
  font-weight: 600;
  font-size: 12.5px;
  word-break: break-all;
}

.sub {
  font-size: 11.5px;
  color: rgb(var(--v-theme-muted));
}

.cols {
  font-size: 11px;
  color: rgb(var(--v-theme-muted));
  word-break: break-all;
}

.edge {
  position: relative;
  display: grid;
  grid-template-columns: 52px minmax(0, 1fr);
  gap: 8px;
  min-height: 22px;
}

.edge .line {
  grid-column: 2;
  grid-row: 1;
  border-left: 2px solid rgb(var(--v-theme-muted));
}

.edge.weak .line {
  border-left-style: dashed;
}

.edge.rejected .line {
  border-left-style: dotted;
  opacity: 0.5;
}

.card.is-rejected {
  opacity: 0.55;
}

.card.is-rejected .title {
  text-decoration: line-through;
}

.edge-menu {
  display: inline-flex;
  align-items: center;
  border: 0;
  background: none;
  color: inherit;
  cursor: pointer;
  padding: 0 2px;
  border-radius: 3px;
}

.edge-menu:hover,
.edge-menu:focus-visible {
  background: rgba(var(--v-theme-primary), 0.12);
}

.add-row {
  margin-top: 2px;
}

.add-btn {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  border: 1px dashed rgb(var(--v-theme-line));
  border-radius: 4px;
  background: none;
  color: rgb(var(--v-theme-muted));
  font-size: 11px;
  padding: 0 6px;
  cursor: pointer;
}

.add-btn:hover {
  color: rgb(var(--v-theme-primary));
  border-color: rgb(var(--v-theme-primary));
}

.edge-meta {
  grid-column: 2;
  grid-row: 1;
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 10.5px;
  color: rgb(var(--v-theme-muted));
  min-width: 0;
}

.edge-meta > span:not(.cond) {
  flex: none;
  white-space: nowrap;
}

.src {
  border: 1px solid currentColor;
  border-radius: 3px;
  padding: 0 4px;
  line-height: 14px;
}

.cond {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
