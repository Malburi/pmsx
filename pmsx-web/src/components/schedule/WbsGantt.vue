<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { WbsItem } from '@/types/r2'
import {
  applyDrag, barRect, DAY_WIDTH, depPath, scaleTicks, todayX, totalWidth, type DragMode, type GanttRange, type GanttScale,
} from '@/utils/gantt'

// SCR-PJ-001 간트 (의존성 없는 SVG 자체 구현). 행 높이는 왼쪽 그리드와 같아야 한다.
const props = withDefaults(defineProps<{
  rows: WbsItem[]
  range: GanttRange
  scale: GanttScale
  today: string
  rowHeight?: number
  headerHeight?: number
  editable?: boolean
  showBaseline?: boolean
  showCritical?: boolean
  selectedId?: string | null
  /** 하위가 있는 항목 — 요약 막대로 그리고 드래그하지 않는다 */
  parentIds: ReadonlySet<string>
}>(), { rowHeight: 36, headerHeight: 48, editable: false, showBaseline: true, showCritical: true, selectedId: null })

const emit = defineEmits<{
  change: [wbsId: string, start: string, end: string]
  select: [wbsId: string]
}>()

const { t } = useI18n()
const drag = ref<{ id: string; mode: DragMode; x0: number; dx: number; start: string; end: string } | null>(null)

// ─────────── 헬퍼 ───────────
const isParent = (r: WbsItem) => props.parentIds.has(r.wbsId)
const canDrag = (r: WbsItem) => props.editable && !isParent(r) && !!r.planStart && !!r.planEnd

const datesOf = (r: WbsItem) => {
  if (drag.value?.id === r.wbsId) return applyDrag(drag.value.mode, drag.value.start, drag.value.end, drag.value.dx, props.scale)
  return { start: r.planStart, end: r.planEnd }
}

const onPointerDown = (e: PointerEvent, r: WbsItem, mode: DragMode) => {
  emit('select', r.wbsId)
  if (!canDrag(r)) return
  e.preventDefault()
  ;(e.target as Element).setPointerCapture?.(e.pointerId)
  drag.value = { id: r.wbsId, mode, x0: e.clientX, dx: 0, start: r.planStart as string, end: r.planEnd as string }
}

const onPointerMove = (e: PointerEvent) => {
  if (drag.value) drag.value.dx = e.clientX - drag.value.x0
}

const onPointerUp = () => {
  const d = drag.value
  drag.value = null
  if (!d) return
  const next = applyDrag(d.mode, d.start, d.end, d.dx, props.scale)
  if (next.start !== d.start || next.end !== d.end) emit('change', d.id, next.start, next.end)
}

// 키보드 조작: ←/→ 하루 이동, Shift+←/→ 종료일만 조절 (접근성)
const onKeyDown = (e: KeyboardEvent, r: WbsItem) => {
  if (!canDrag(r) || (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight')) return
  e.preventDefault()
  const dx = (e.key === 'ArrowLeft' ? -1 : 1) * DAY_WIDTH[props.scale]
  const next = applyDrag(e.shiftKey ? 'end' : 'move', r.planStart as string, r.planEnd as string, dx, props.scale)
  emit('change', r.wbsId, next.start, next.end)
}

const barTitle = (r: WbsItem) => `${r.wbsNo} ${r.name} · ${r.planStart ?? '-'} ~ ${r.planEnd ?? '-'} · ${r.progress}%`

// ─────────── computed ───────────
const width = computed(() => totalWidth(props.range, props.scale))
const height = computed(() => props.rows.length * props.rowHeight)
const ticks = computed(() => scaleTicks(props.range, props.scale))
const todayPos = computed(() => todayX(props.today, props.range, props.scale))
const rowIndex = computed(() => new Map(props.rows.map((r, i) => [r.wbsId, i])))

const bars = computed(() => props.rows.map((r, i) => {
  const { start, end } = datesOf(r)
  const y = i * props.rowHeight
  const rect = start && end ? barRect(start, end, props.range, props.scale) : null
  const base = props.showBaseline && r.baselineStart && r.baselineEnd ? barRect(r.baselineStart, r.baselineEnd, props.range, props.scale) : null
  return { r, y, rect, base, parent: isParent(r), critical: props.showCritical && r.critical }
}))

const arrows = computed(() => bars.value.flatMap((b) => {
  const rect = b.rect
  if (!rect) return []
  return b.r.deps.flatMap((d) => {
    const pi = rowIndex.value.get(d.predId)
    const pb = pi === undefined ? null : bars.value[pi]
    if (!pb?.rect) return []
    const mid = props.rowHeight / 2
    return [{
      key: `${d.predId}-${b.r.wbsId}`,
      d: depPath(d.type, { x: pb.rect.x, w: pb.rect.w, y: pb.y + mid }, { x: rect.x, w: rect.w, y: b.y + mid }),
      critical: props.showCritical && pb.r.critical && b.r.critical,
    }]
  })
}))
</script>

<template>
  <div class="gantt" :aria-label="t('pj.wbs.ganttLabel')" role="group">
    <svg :width="width" :height="headerHeight" class="gantt-header" aria-hidden="true">
      <g v-for="tk in ticks" :key="`${tk.major}-${tk.x}`">
        <rect :x="tk.x" :y="tk.major ? 0 : headerHeight / 2" :width="tk.w" :height="headerHeight / 2" class="tick-bg" />
        <text :x="tk.x + 6" :y="tk.major ? 17 : headerHeight / 2 + 17" class="tick-label">{{ tk.label }}</text>
      </g>
      <line v-if="scale === 'month'" x1="0" :x2="width" :y1="headerHeight / 2" :y2="headerHeight / 2" class="grid-line" />
    </svg>
    <svg
      :width="width"
      :height="height"
      class="gantt-body"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerUp"
    >
      <defs>
        <marker id="gantt-arrow" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto">
          <path d="M0,0 L8,4 L0,8 z" class="arrow-head" />
        </marker>
        <marker id="gantt-arrow-crit" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto">
          <path d="M0,0 L8,4 L0,8 z" class="arrow-head crit" />
        </marker>
      </defs>
      <!-- 행 배경·주 구분선 -->
      <rect
        v-for="(b, i) in bars"
        :key="`bg-${b.r.wbsId}`"
        x="0"
        :y="b.y"
        :width="width"
        :height="rowHeight"
        :class="['row-bg', { odd: i % 2 === 1, selected: b.r.wbsId === selectedId }]"
      />
      <line v-for="tk in ticks.filter((x) => !x.major || scale === 'month')" :key="`gl-${tk.x}`" :x1="tk.x" :x2="tk.x" y1="0" :y2="height" class="grid-line" />

      <!-- 선후행 화살표 -->
      <path
        v-for="a in arrows"
        :key="a.key"
        :d="a.d"
        :class="['dep', { crit: a.critical }]"
        :marker-end="a.critical ? 'url(#gantt-arrow-crit)' : 'url(#gantt-arrow)'"
      />

      <g v-for="b in bars" :key="b.r.wbsId" :data-test="`gantt-row-${b.r.wbsId}`">
        <title>{{ barTitle(b.r) }}</title>
        <!-- 베이스라인 -->
        <rect v-if="b.base" :x="b.base.x" :y="b.y + rowHeight - 9" :width="b.base.w" height="4" rx="2" class="baseline" />
        <template v-if="b.rect">
          <!-- 마일스톤: 마름모 -->
          <path
            v-if="b.r.milestone"
            :d="`M${b.rect.x + b.rect.w / 2},${b.y + 8} l9,10 l-9,10 l-9,-10 z`"
            :class="['milestone', { crit: b.critical, done: b.r.status === 'DONE' }]"
            @pointerdown="onPointerDown($event, b.r, 'move')"
          />
          <!-- 상위 항목: 요약 막대 -->
          <g v-else-if="b.parent" @pointerdown="emit('select', b.r.wbsId)">
            <rect :x="b.rect.x" :y="b.y + 12" :width="b.rect.w" height="7" class="summary" />
            <rect :x="b.rect.x" :y="b.y + 12" :width="b.rect.w * b.r.progress / 100" height="7" class="summary-progress" />
            <path :d="`M${b.rect.x},${b.y + 12} v12 l6,-5 z`" class="summary" />
            <path :d="`M${b.rect.x + b.rect.w},${b.y + 12} v12 l-6,-5 z`" class="summary" />
          </g>
          <!-- 작업 막대 -->
          <g
            v-else
            :class="['task', { crit: b.critical, draggable: canDrag(b.r), dragging: drag?.id === b.r.wbsId }]"
            :tabindex="canDrag(b.r) ? 0 : -1"
            role="slider"
            :aria-label="barTitle(b.r)"
            :aria-valuenow="b.r.progress"
            aria-valuemin="0"
            aria-valuemax="100"
            @keydown="onKeyDown($event, b.r)"
          >
            <rect
              :x="b.rect.x"
              :y="b.y + 7"
              :width="b.rect.w"
              :height="rowHeight - 18"
              :rx="(rowHeight - 18) / 2"
              class="task-bar"
              @pointerdown="onPointerDown($event, b.r, 'move')"
            />
            <rect
              :x="b.rect.x"
              :y="b.y + 7"
              :width="b.rect.w * b.r.progress / 100"
              :height="rowHeight - 18"
              :rx="(rowHeight - 18) / 2"
              class="task-progress"
              pointer-events="none"
            />
            <template v-if="canDrag(b.r)">
              <rect :x="b.rect.x - 3" :y="b.y + 7" width="7" :height="rowHeight - 18" class="handle" @pointerdown.stop="onPointerDown($event, b.r, 'start')" />
              <rect :x="b.rect.x + b.rect.w - 4" :y="b.y + 7" width="7" :height="rowHeight - 18" class="handle" @pointerdown.stop="onPointerDown($event, b.r, 'end')" />
            </template>
            <text v-if="b.rect.w > 60" :x="b.rect.x + 6" :y="b.y + rowHeight / 2 + 4" class="bar-label" pointer-events="none">{{ b.r.progress }}%</text>
          </g>
          <text
            v-if="b.r.assignees.length && !b.parent"
            :x="b.rect.x + b.rect.w + (b.r.milestone ? 14 : 6)"
            :y="b.y + rowHeight / 2 + 4"
            class="assignee-label"
            pointer-events="none"
          >{{ b.r.assignees.map((a) => a.name).join(', ') }}</text>
        </template>
      </g>

      <!-- 오늘선 -->
      <g v-if="todayPos !== null">
        <line :x1="todayPos" :x2="todayPos" y1="0" :y2="height" class="today" />
      </g>
    </svg>
  </div>
</template>

<style scoped>
.gantt {
  display: inline-block;
  min-width: 100%;
  user-select: none;
}

.gantt-header {
  display: block;
  position: sticky;
  top: 0;
  z-index: 2;
  background: rgb(var(--v-theme-surface));
  border-bottom: 1px solid rgb(var(--v-theme-line));
}

.gantt-body {
  display: block;
  touch-action: none;
}

.tick-bg {
  fill: rgb(var(--v-theme-surface));
  stroke: rgb(var(--v-theme-line));
  stroke-width: 0.5;
}

.tick-label {
  font-size: 11px;
  fill: rgb(var(--v-theme-muted));
  font-variant-numeric: tabular-nums;
}

.grid-line {
  stroke: rgb(var(--v-theme-line));
  stroke-width: 0.5;
}

.row-bg {
  fill: transparent;
}

.row-bg.odd {
  fill: rgba(var(--v-theme-on-surface), 0.025);
}

.row-bg.selected {
  fill: rgba(var(--v-theme-primary), 0.08);
}

.baseline {
  fill: rgba(var(--v-theme-on-surface), 0.28);
}

.summary {
  fill: rgba(var(--v-theme-on-surface), 0.2);
}

.summary-progress {
  fill: rgb(var(--v-theme-primary));
}

/* 클린 벤토: 파스텔 pill 막대 + 진한 진척 채움, 테두리는 포커스·드래그 때만 */
.task-bar {
  fill: rgb(var(--v-theme-pastel-blue));
  stroke: rgba(var(--v-theme-primary), 0.35);
  stroke-width: 1;
}

.task.crit .task-bar {
  fill: rgb(var(--v-theme-pastel-peach));
  stroke: rgba(var(--v-theme-error), 0.45);
}

.task-progress {
  fill: rgb(var(--v-theme-primary));
}

.task.crit .task-progress {
  fill: rgb(var(--v-theme-error));
}

.task.draggable .task-bar {
  cursor: grab;
}

.task.dragging .task-bar {
  cursor: grabbing;
  stroke-dasharray: 4 2;
}

.task:focus-visible {
  outline: none;
}

.task:focus-visible .task-bar {
  stroke-width: 2.5;
}

.handle {
  fill: transparent;
  cursor: ew-resize;
}

.bar-label {
  font-size: 10px;
  fill: rgb(var(--v-theme-on-primary));
  font-weight: 600;
}

.assignee-label {
  font-size: 11px;
  fill: rgb(var(--v-theme-muted));
}

.milestone {
  fill: rgb(var(--v-theme-warning));
  stroke: rgba(var(--v-theme-on-surface), 0.5);
}

.milestone.crit {
  fill: rgb(var(--v-theme-error));
}

.milestone.done {
  fill: rgb(var(--v-theme-success));
}

.dep {
  fill: none;
  stroke: rgba(var(--v-theme-on-surface), 0.45);
  stroke-width: 1;
}

.dep.crit {
  stroke: rgb(var(--v-theme-error));
}

.arrow-head {
  fill: rgba(var(--v-theme-on-surface), 0.55);
}

.arrow-head.crit {
  fill: rgb(var(--v-theme-error));
}

.today {
  stroke: rgb(var(--v-theme-error));
  stroke-width: 1.5;
  stroke-dasharray: 5 3;
}
</style>
