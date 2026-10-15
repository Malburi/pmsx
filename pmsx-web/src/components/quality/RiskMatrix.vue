<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { Risk } from '@/types/r2'
import { riskLevel } from '@/utils/quality'

// 확률 × 영향 5×5 히트맵 (FR-QA-002). 칸을 누르면 그 칸의 리스크로 거른다.
const props = defineProps<{ risks: Risk[]; selected: { p: number; i: number } | null }>()
const emit = defineEmits<{ select: [cell: { p: number; i: number } | null] }>()

const { t } = useI18n()
const LEVELS = [5, 4, 3, 2, 1]

const inCell = (p: number, i: number) => props.risks.filter((r) => r.probability === p && r.impact === i && r.status !== 'CLOSED')
const isSel = (p: number, i: number) => props.selected?.p === p && props.selected?.i === i
const toggle = (p: number, i: number) => emit('select', isSel(p, i) ? null : { p, i })
</script>

<template>
  <div class="risk-matrix" role="grid" :aria-label="t('qa.risk.matrix')">
    <div class="y-label">{{ t('qa.risk.probability') }}</div>
    <div class="grid">
      <template v-for="p in LEVELS" :key="`row-${p}`">
        <div class="axis">{{ p }}</div>
        <button
          v-for="i in [1, 2, 3, 4, 5]"
          :key="`${p}-${i}`"
          type="button"
          :class="['cell', `lv-${riskLevel(p * i)}`, { sel: isSel(p, i) }]"
          :aria-label="t('qa.risk.cellLabel', { p, i, n: inCell(p, i).length })"
          :data-test="`risk-cell-${p}-${i}`"
          @click="toggle(p, i)"
        >
          <span class="score">{{ p * i }}</span>
          <span class="ids">
            <span v-for="r in inCell(p, i)" :key="r.riskId" class="id">{{ r.riskNo.replace('RSK-', '') }}</span>
          </span>
        </button>
      </template>
      <div />
      <div v-for="i in [1, 2, 3, 4, 5]" :key="`x-${i}`" class="axis">{{ i }}</div>
    </div>
    <div class="x-label">{{ t('qa.risk.impact') }}</div>
  </div>
</template>

<style scoped>
.risk-matrix {
  display: grid;
  grid-template-columns: 18px 1fr;
  grid-template-rows: 1fr auto;
  gap: 4px;
}

.y-label {
  writing-mode: vertical-rl;
  transform: rotate(180deg);
  text-align: center;
  font-size: 12px;
  color: rgb(var(--v-theme-muted));
}

.x-label {
  grid-column: 2;
  text-align: center;
  font-size: 12px;
  color: rgb(var(--v-theme-muted));
}

.grid {
  display: grid;
  grid-template-columns: 20px repeat(5, minmax(44px, 1fr));
  gap: 3px;
}

.axis {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  color: rgb(var(--v-theme-muted));
}

.cell {
  position: relative;
  min-height: 52px;
  border: 1px solid transparent;
  border-radius: 6px;
  padding: 4px;
  cursor: pointer;
  text-align: left;
  color: inherit;
  font: inherit;
}

.cell.sel {
  border-color: rgb(var(--v-theme-on-surface));
  box-shadow: 0 0 0 2px rgba(var(--v-theme-primary), 0.4);
}

.cell:focus-visible {
  outline: 2px solid rgb(var(--v-theme-primary));
}

.score {
  position: absolute;
  right: 5px;
  top: 3px;
  font-size: 10px;
  opacity: 0.6;
}

.ids {
  display: flex;
  flex-wrap: wrap;
  gap: 2px;
  margin-top: 12px;
}

.id {
  font-size: 10px;
  font-weight: 700;
  background: rgba(var(--v-theme-surface), 0.85);
  border-radius: 3px;
  padding: 0 3px;
}

.lv-low { background: rgba(var(--v-theme-success), 0.22); }
.lv-medium { background: rgba(var(--v-theme-info), 0.26); }
.lv-high { background: rgba(var(--v-theme-warning), 0.38); }
.lv-critical { background: rgba(var(--v-theme-error), 0.45); }
</style>
