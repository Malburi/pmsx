<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { HeatmapRow } from '@/types/r2'
import { allocLevel, overAllocated } from '@/utils/quality'

// 인력 × 기간 배정률 히트맵 (FR-RS-004). 100% 초과 칸은 빨갛게 강조한다.
const props = defineProps<{ rows: HeatmapRow[]; periods: string[]; periodLabel?: (p: string) => string; highlightUserId?: string | null }>()

const { t } = useI18n()

const label = (p: string) => props.periodLabel?.(p) ?? p
const overUsers = computed(() => props.rows.filter((r) => overAllocated(r.cells).length > 0).length)
</script>

<template>
  <div>
    <div class="heatmap-wrap" role="region" :aria-label="t('rs.heatmap.title')" tabindex="0">
      <table class="heatmap" data-test="heatmap">
        <thead>
          <tr>
            <th class="sticky">{{ t('rs.heatmap.person') }}</th>
            <th v-for="p in periods" :key="p">{{ label(p) }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rows" :key="r.userId" :class="{ hl: r.userId === highlightUserId }">
            <td class="sticky">
              {{ r.name }}
              <VIcon v-if="overAllocated(r.cells).length" icon="ri-error-warning-fill" size="14" color="error" :title="t('rs.heatmap.overTip', { n: overAllocated(r.cells).length })" />
            </td>
            <td
              v-for="p in periods"
              :key="p"
              :class="['cell', `lv-${allocLevel(r.cells[p])}`]"
              :title="`${r.name} · ${label(p)} · ${r.cells[p] ?? 0}%`"
            >{{ r.cells[p] ? r.cells[p] : '' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div class="d-flex flex-wrap align-center ga-3 text-caption text-muted mt-2">
      <span><i class="sw lv-low" />~49%</span>
      <span><i class="sw lv-normal" />50~89%</span>
      <span><i class="sw lv-full" />90~100%</span>
      <span><i class="sw lv-over" />{{ t('rs.heatmap.over') }}</span>
      <VSpacer />
      <span v-if="overUsers" class="text-error font-weight-medium">{{ t('rs.heatmap.overUsers', { n: overUsers }) }}</span>
    </div>
  </div>
</template>

<style scoped>
.heatmap-wrap {
  overflow-x: auto;
}

.heatmap {
  border-collapse: collapse;
  font-size: 12px;
  min-width: 100%;
}

.heatmap th,
.heatmap td {
  border: 1px solid rgb(var(--v-theme-line));
  padding: 5px 6px;
  text-align: center;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.heatmap th {
  color: rgb(var(--v-theme-muted));
  font-weight: 600;
}

.sticky {
  position: sticky;
  left: 0;
  background: rgb(var(--v-theme-surface));
  text-align: left !important;
  z-index: 1;
  min-width: 96px;
}

tr.hl td {
  outline: 2px solid rgba(var(--v-theme-primary), 0.5);
  outline-offset: -2px;
}

.cell {
  min-width: 44px;
}

.lv-low { background: rgba(var(--v-theme-success), 0.14); }
.lv-normal { background: rgba(var(--v-theme-success), 0.32); }
.lv-full { background: rgba(var(--v-theme-warning), 0.38); }
.lv-over { background: rgba(var(--v-theme-error), 0.5); font-weight: 700; }

.sw {
  display: inline-block;
  width: 14px;
  height: 10px;
  border-radius: 2px;
  margin-right: 4px;
  vertical-align: middle;
}
</style>
