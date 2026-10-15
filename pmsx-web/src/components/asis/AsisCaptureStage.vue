<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { getCaptureImageApi } from '@/api/asisApi'
import { useBlobUrl } from '@/composables/useBlobUrl'
import type { Box, ElementSummary, ScreenDetail } from '@/types/api'
import { TYPE_LEGEND, boxToPercent, isButtonLike, typeColor } from '@/utils/asis'
import {
  RESIZE_HANDLES, clientToCapture, deltaToCapture, handleCursor, handlePosition, isDrawable, moveBox, rectFromPoints,
  resizeBox, roundBox, sameBox, type Point, type ResizeHandle, type StageRect,
} from '@/utils/hotspot'

const props = withDefaults(defineProps<{
  screen: ScreenDetail
  selectedElementId: string | null
  /** 핫스팟 편집 모드 (FR-AS-019) — 권한자에게만 켜진다 */
  editable?: boolean
}>(), { editable: false })
const emit = defineEmits<{
  select: [elementId: string]
  /** 캡처 위에 새 영역을 그렸다 (캡처 원본 좌표) */
  create: [box: Box]
  /** 요소를 옮기거나 크기를 바꿨다 */
  move: [elementId: string, box: Box]
  uploadCapture: []
}>()

const { t } = useI18n()
// 드래그와 클릭을 구분하는 최소 이동량(화면 px)
const CLICK_SLOP = 3
const NUDGE_COMMIT_MS = 600

const onlyButtons = ref(false)
const hideBadges = ref(false)
const actualSize = ref(false)
const showHidden = ref(false)
const stageEl = ref<HTMLDivElement | null>(null)
// 드래그 중 미리보기 박스 (저장 전)
const drafts = reactive<Record<string, Box>>({})
const drawBox = ref<Box | null>(null)
let drag: {
  mode: 'move' | 'resize' | 'draw'
  elementId: string | null
  handle: ResizeHandle | null
  start: Point
  startBox: Box | null
  rect: StageRect
  moved: boolean
} | null = null
let nudgeTimer: ReturnType<typeof setTimeout> | null = null

const captureId = computed(() => props.screen.capture?.captureId ?? null)
const { url: imageUrl, loading: imageLoading, failed: imageFailed } = useBlobUrl(captureId, getCaptureImageApi)

// ─────────── 헬퍼 ───────────
const isUnmatched = (e: ElementSummary) => e.matched === 'dynamic-only'

const tooltipOf = (e: ElementSummary) => {
  const handler = e.events[0]?.handler
  const base = `${e.seqNo}. ${e.label} · ${e.elementCode}`
  if (isUnmatched(e)) return `${base} — ${t('asis.viewer.capture.unmatched')}`
  return handler ? `${base} → ${handler}` : base
}

const capSize = () => ({ width: props.screen.capture?.width ?? 1, height: props.screen.capture?.height ?? 1 })

const boxOf = (e: ElementSummary) => drafts[e.elementId] ?? e.box

const hotspotStyle = (e: ElementSummary) => {
  const cap = props.screen.capture
  const box = boxOf(e)
  if (!cap || !box) return {}
  return { ...boxToPercent(box, cap.width, cap.height), '--hc': `var(--v-theme-${typeColor(e.type)})`, '--hco': `var(--v-theme-on-${typeColor(e.type)})` }
}

const drawStyle = computed(() => {
  const cap = props.screen.capture
  return cap && drawBox.value ? boxToPercent(drawBox.value, cap.width, cap.height) : {}
})

const stageRect = (): StageRect | null => {
  const r = stageEl.value?.getBoundingClientRect()
  return r ? { left: r.left, top: r.top, width: r.width, height: r.height } : null
}

const endDrag = () => {
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
  window.removeEventListener('pointercancel', onPointerCancel)
  drag = null
}

const onPointerMove = (ev: PointerEvent) => {
  if (!drag) return
  const dxPx = ev.clientX - drag.start.x
  const dyPx = ev.clientY - drag.start.y
  if (!drag.moved && Math.abs(dxPx) < CLICK_SLOP && Math.abs(dyPx) < CLICK_SLOP) return
  drag.moved = true
  const cap = capSize()
  if (drag.mode === 'draw') {
    const a = clientToCapture(drag.start.x, drag.start.y, drag.rect, cap)
    const b = clientToCapture(ev.clientX, ev.clientY, drag.rect, cap)
    drawBox.value = rectFromPoints(a, b)
    return
  }
  if (!drag.elementId || !drag.startBox) return
  const d = deltaToCapture(dxPx, dyPx, drag.rect, cap)
  drafts[drag.elementId] = drag.mode === 'move'
    ? moveBox(drag.startBox, d.x, d.y, cap)
    : resizeBox(drag.startBox, drag.handle as ResizeHandle, d.x, d.y, cap)
}

const onPointerUp = () => {
  const d = drag
  endDrag()
  if (!d) return
  if (d.mode === 'draw') {
    const box = drawBox.value
    drawBox.value = null
    if (d.moved && box && isDrawable(box)) emit('create', roundBox(box))
    return
  }
  if (!d.elementId) return
  const draft = drafts[d.elementId]
  delete drafts[d.elementId]
  if (!d.moved) { emit('select', d.elementId); return }
  if (draft && !sameBox(roundBox(draft), d.startBox)) emit('move', d.elementId, roundBox(draft))
}

const onPointerCancel = () => {
  if (drag?.elementId) delete drafts[drag.elementId]
  drawBox.value = null
  endDrag()
}

const beginDrag = (ev: PointerEvent, mode: 'move' | 'resize' | 'draw', e: ElementSummary | null, handle: ResizeHandle | null = null) => {
  const rect = stageRect()
  if (!rect || ev.button !== 0) return
  ev.preventDefault()
  drag = { mode, elementId: e?.elementId ?? null, handle, start: { x: ev.clientX, y: ev.clientY }, startBox: e?.box ?? null, rect, moved: false }
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
  window.addEventListener('pointercancel', onPointerCancel)
}

const onHotspotPointerDown = (ev: PointerEvent, e: ElementSummary) => {
  if (!props.editable || !e.box) return
  ev.stopPropagation()
  beginDrag(ev, 'move', e)
}

const onHandlePointerDown = (ev: PointerEvent, e: ElementSummary, h: ResizeHandle) => {
  ev.stopPropagation()
  beginDrag(ev, 'resize', e, h)
}

// 편집 모드에서 빈 곳을 드래그하면 새 요소 영역을 그린다
const onStagePointerDown = (ev: PointerEvent) => {
  if (!props.editable || !props.screen.capture) return
  beginDrag(ev, 'draw', null)
}

const onHotspotClick = (e: ElementSummary) => {
  // 편집 모드의 클릭은 pointerup 에서 처리한다 (드래그와 구분)
  if (!props.editable || !e.box) emit('select', e.elementId)
}

// 방향키로 1px(Shift 10px) 이동 — 마지막 입력 후 잠시 뒤 한 번만 저장
const onHotspotKeydown = (ev: KeyboardEvent, e: ElementSummary) => {
  if (!props.editable || !e.box) return
  const step = ev.shiftKey ? 10 : 1
  const delta: Record<string, [number, number]> = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] }
  const d = delta[ev.key]
  if (!d) return
  ev.preventDefault()
  const base = drafts[e.elementId] ?? e.box
  drafts[e.elementId] = moveBox(base, d[0], d[1], capSize())
  if (nudgeTimer) clearTimeout(nudgeTimer)
  nudgeTimer = setTimeout(() => {
    const draft = drafts[e.elementId]
    delete drafts[e.elementId]
    if (draft && !sameBox(draft, e.box)) emit('move', e.elementId, roundBox(draft))
  }, NUDGE_COMMIT_MS)
}

// ─────────── computed ───────────
const visibleHotspots = computed(() =>
  props.screen.elements.filter((e) =>
    e.box
    && (!e.hidden || (props.editable && showHidden.value))
    && (!onlyButtons.value || isButtonLike(e.type))),
)
const isWireframe = computed(() => props.screen.capture?.state === 'wireframe')
const isRendered = computed(() => props.screen.capture?.state === 'rendered')
const offCapture = computed(() => props.screen.elements.filter((e) => !e.box && !e.hidden))
const stageStyle = computed(() => {
  const cap = props.screen.capture
  if (!cap) return {}
  // 화면 맞춤: 컨테이너 폭에 맞추되 원본보다 크게 늘리지 않음 / 원본 크기: 가로 스크롤
  return actualSize.value ? { width: `${cap.width}px` } : { maxWidth: `${cap.width}px` }
})

// ─────────── 라이프사이클 ───────────
onBeforeUnmount(() => {
  endDrag()
  if (nudgeTimer) clearTimeout(nudgeTimer)
})
</script>

<template>
  <div class="capture-stage">
    <div class="toolbar px-4 py-2 text-caption text-muted">
      <div class="legend d-flex flex-wrap ga-3" aria-hidden="true">
        <span v-for="lg in TYPE_LEGEND" :key="lg.key" class="d-inline-flex align-center ga-1">
          <i class="swatch" :class="`bg-${lg.color}`" />{{ t(`asis.type.${lg.key}`) }}
        </span>
      </div>
      <div class="controls d-flex flex-wrap align-center ga-2">
        <VCheckbox v-model="onlyButtons" :label="t('asis.viewer.capture.onlyButtons')" density="compact" hide-details class="flex-grow-0" />
        <VCheckbox v-model="hideBadges" :label="t('asis.viewer.capture.hideBadges')" density="compact" hide-details class="flex-grow-0" />
        <VCheckbox
          v-if="editable"
          v-model="showHidden"
          :label="t('asis.edit.showHidden')"
          density="compact"
          hide-details
          class="flex-grow-0"
          data-test="show-hidden"
        />
        <VBtnToggle v-model="actualSize" density="compact" variant="outlined" divided mandatory>
          <VBtn :value="false" size="small">{{ t('asis.viewer.capture.fit') }}</VBtn>
          <VBtn :value="true" size="small">{{ t('asis.viewer.capture.actual') }}</VBtn>
        </VBtnToggle>
      </div>
    </div>

    <div v-if="isWireframe" class="px-4 pb-2">
      <VAlert type="info" variant="tonal" density="compact" icon="ri-layout-4-line" data-test="wireframe-notice">
        <VChip size="x-small" color="info" variant="flat" label class="mr-2">{{ t('asis.viewer.capture.wireframe') }}</VChip>
        {{ t('asis.viewer.capture.wireframeHint') }}
      </VAlert>
    </div>
    <div v-else-if="isRendered" class="px-4 pb-2">
      <VAlert type="info" variant="tonal" density="compact" icon="ri-file-code-line" data-test="rendered-notice">
        <VChip size="x-small" color="info" variant="flat" label class="mr-2">{{ t('asis.viewer.capture.rendered') }}</VChip>
        {{ t('asis.viewer.capture.renderedHint') }}
      </VAlert>
    </div>

    <div v-if="editable" class="px-4 pb-2 d-flex flex-wrap align-center ga-2">
      <VAlert type="info" variant="tonal" density="compact" icon="ri-drag-drop-line" class="flex-grow-1 edit-hint">
        {{ screen.capture ? t('asis.edit.onHint') : t('asis.edit.noCaptureHint') }}
      </VAlert>
      <VBtn size="small" variant="tonal" prepend-icon="ri-image-add-line" data-test="capture-upload-open" @click="emit('uploadCapture')">
        {{ t('asis.edit.uploadCapture') }}
      </VBtn>
    </div>

    <div class="stage-scroll px-4 pb-4">
      <VAlert v-if="!screen.capture" type="info" variant="tonal" density="compact" class="mb-3">
        {{ t('asis.viewer.capture.none') }}
      </VAlert>
      <template v-else>
        <div
          ref="stageEl"
          class="stage"
          :class="{ 'hide-badges': hideBadges, editing: editable, wireframe: isWireframe }"
          :style="stageStyle"
          data-test="capture-stage"
          @pointerdown="onStagePointerDown"
        >
          <VSkeletonLoader v-if="imageLoading" type="image" class="capture-skeleton" />
          <VAlert v-else-if="imageFailed" type="warning" variant="tonal" density="compact">{{ t('asis.viewer.capture.loadFailed') }}</VAlert>
          <img
            v-if="imageUrl"
            :src="imageUrl"
            :width="screen.capture.width"
            :height="screen.capture.height"
            :alt="t('asis.viewer.capture.alt', { name: screen.name })"
            class="capture-img"
            draggable="false"
          />
          <button
            v-for="e in visibleHotspots"
            :key="e.elementId"
            type="button"
            class="hotspot"
            :class="{ sel: e.elementId === selectedElementId, unmatched: isUnmatched(e), 'is-hidden': e.hidden, 'is-manual': e.manual }"
            :style="hotspotStyle(e)"
            :aria-label="tooltipOf(e)"
            :aria-pressed="e.elementId === selectedElementId"
            :data-test="`hotspot-${e.elementCode}`"
            @pointerdown="onHotspotPointerDown($event, e)"
            @click="onHotspotClick(e)"
            @keydown="onHotspotKeydown($event, e)"
          >
            <b class="badge">{{ e.seqNo }}</b>
            <template v-if="editable && e.elementId === selectedElementId">
              <span
                v-for="h in RESIZE_HANDLES"
                :key="h"
                class="handle"
                :style="{ ...handlePosition(h), cursor: handleCursor(h) }"
                :data-test="`handle-${h}`"
                @pointerdown="onHandlePointerDown($event, e, h)"
              />
            </template>
            <VTooltip v-if="!editable" activator="parent" location="top" :text="tooltipOf(e)" />
          </button>
          <div v-if="drawBox" class="draw-box" :style="drawStyle" aria-hidden="true" />
        </div>
      </template>

      <div v-if="offCapture.length" class="mt-3 text-caption">
        <span class="text-muted mr-2">{{ t('asis.viewer.capture.offCapture', { n: offCapture.length }) }}</span>
        <VChip
          v-for="e in offCapture"
          :key="e.elementId"
          size="small"
          variant="tonal"
          class="mr-1 mb-1"
          :color="e.elementId === selectedElementId ? 'primary' : undefined"
          @click="emit('select', e.elementId)"
        >
          {{ e.seqNo }}. {{ e.label }}
        </VChip>
      </div>
    </div>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 4px 16px;
}

.swatch {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 2px;
}

.edit-hint {
  min-width: 0;
}

.stage-scroll {
  overflow-x: auto;
}

.stage {
  position: relative;
  width: 100%;
  border: 1px solid rgb(var(--v-theme-line));
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.08);
  background: rgb(var(--v-theme-surface-2));
  touch-action: pan-y;
}

.stage.wireframe {
  border-style: dashed;
}

.stage.editing {
  cursor: crosshair;
  outline: 2px dashed rgba(var(--v-theme-primary), 0.5);
  outline-offset: 2px;
  touch-action: none;
}

.capture-img {
  display: block;
  width: 100%;
  height: auto;
  user-select: none;
  pointer-events: none;
}

.capture-skeleton {
  aspect-ratio: 900 / 560;
}

.hotspot {
  position: absolute;
  padding: 0;
  border: 2px solid rgb(var(--hc));
  background: rgba(var(--hc), 0.1);
  border-radius: 3px;
  cursor: pointer;
  transition: background-color 0.12s;
}

.editing .hotspot {
  cursor: move;
  transition: none;
}

.hotspot:hover {
  background: rgba(var(--hc), 0.26);
}

.hotspot.sel {
  background: rgba(var(--hc), 0.3);
  box-shadow: 0 0 0 3px rgba(var(--hc), 0.45);
}

.hotspot.unmatched {
  border-style: dashed;
}

.hotspot.is-manual {
  border-style: double;
  border-width: 3px;
}

.hotspot.is-hidden {
  opacity: 0.45;
  border-style: dotted;
  filter: grayscale(1);
}

.badge {
  position: absolute;
  left: -9px;
  top: -9px;
  min-width: 18px;
  height: 18px;
  padding: 0 4px;
  border-radius: 9px;
  background: rgb(var(--hc));
  color: rgb(var(--hco));
  font: 700 11px/18px var(--pmsx-font);
  text-align: center;
  pointer-events: none;
}

.hide-badges .badge {
  display: none;
}

.handle {
  position: absolute;
  width: 10px;
  height: 10px;
  margin: -5px 0 0 -5px;
  border: 2px solid rgb(var(--hc));
  background: rgb(var(--v-theme-surface));
  border-radius: 2px;
  z-index: 2;
}

.draw-box {
  position: absolute;
  border: 2px dashed rgb(var(--v-theme-primary));
  background: rgba(var(--v-theme-primary), 0.12);
  pointer-events: none;
}

@media (prefers-reduced-motion: reduce) {
  .hotspot {
    transition: none;
  }
}
</style>
