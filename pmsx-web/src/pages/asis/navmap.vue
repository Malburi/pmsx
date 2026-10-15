<route lang="yaml">
meta:
  compactNav: true
</route>

<script setup lang="ts">
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MarkerType, VueFlow, useVueFlow, type Edge, type Node, type NodeMouseEvent } from '@vue-flow/core'
import { MiniMap } from '@vue-flow/minimap'
import { storeToRefs } from 'pinia'
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useTheme } from 'vuetify'
import { getNavigationApi } from '@/api/asisApi'
import AsisNavScreenNode from '@/components/asis/AsisNavScreenNode.vue'
import AsisSystemSelect from '@/components/asis/AsisSystemSelect.vue'
import { useGraphLayout } from '@/composables/useGraphLayout'
import { useProjectStore } from '@/stores/project'
import type { ApiError, NavigationMap } from '@/types/api'
import { saveBlob } from '@/utils/download'
import { exportFlowPng, pngFileName } from '@/utils/flowImage'
import { navToFlow, type FlowNode } from '@/utils/graph'
import { showErrorToast } from '@/utils/toast'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'

// SCR-AS-008 화면 이동 맵 (FR-AS-014)
const FLOW_ID = 'asis-navmap'

const { t } = useI18n()
const router = useRouter()
const theme = useTheme()
const { systems } = storeToRefs(useProjectStore())
const { fitView, getNodes } = useVueFlow(FLOW_ID)
const { layouting, layout } = useGraphLayout()

const systemId = ref<string | null>(null)
const map = ref<NavigationMap | null>(null)
const hideIsolated = ref(true)
const nodes = ref<Node[]>([])
const edges = ref<Edge[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)
const exporting = ref(false)
const flowEl = ref<HTMLElement | null>(null)

// ─────────── 헬퍼 ───────────
const visibleMap = (m: NavigationMap): NavigationMap => {
  if (!hideIsolated.value) return m
  const linked = new Set(m.links.flatMap((l) => [l.fromScreenId, l.toScreenId]))
  return { nodes: m.nodes.filter((n) => linked.has(n.screenId)), links: m.links }
}

const render = async () => {
  if (!map.value) return
  const flow = navToFlow(visibleMap(map.value))
  nodes.value = (await layout(flow.nodes as FlowNode[], flow.edges, 'RIGHT')) as Node[]
  edges.value = flow.edges.map((e) => ({ ...e, markerEnd: MarkerType.ArrowClosed, labelBgPadding: [4, 2] as [number, number], labelBgBorderRadius: 3 }))
  await nextTick()
  void fitView({ padding: 0.15 })
}

const load = async () => {
  if (!systemId.value) return
  loading.value = true
  loadError.value = null
  try {
    map.value = await getNavigationApi(systemId.value)
    await render()
  } catch (e) {
    loadError.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}

const onNodeClick = ({ node }: NodeMouseEvent) => {
  void router.push(`/asis/viewer/${systemId.value}/${node.id}`)
}

const exportPng = async () => {
  if (!flowEl.value) return
  exporting.value = true
  try {
    const blob = await exportFlowPng(flowEl.value, getNodes.value, theme.current.value.colors.background)
    saveBlob(blob, pngFileName(`${t('nav.asis.navmap')}_${systemCode.value}`))
  } catch {
    showErrorToast('asis.erd.exportFailed')
  } finally {
    exporting.value = false
  }
}

// ─────────── computed ───────────
const systemCode = computed(() => systems.value.find((s) => s.systemId === systemId.value)?.code ?? '')

// ─────────── watch ───────────
watch(systemId, () => { void load() })
watch(hideIsolated, () => { void render() })
</script>

<template>
  <div class="nav-page">
    <div class="px-4 py-3 bg-surface border-b border-line">
      <div class="d-flex flex-wrap align-center ga-3">
        <div class="mr-2">
          <h1 class="pmsx-page-title">{{ t('nav.asis.navmap') }}</h1>
          <div class="text-caption text-muted">{{ t('asis.navmap.subtitle') }}</div>
        </div>
        <AsisSystemSelect v-model="systemId" />
        <VSwitch v-model="hideIsolated" :label="t('asis.navmap.isolated')" color="primary" hide-details density="compact" class="flex-grow-0" />
        <VSpacer />
        <div class="legend d-flex flex-wrap ga-4 text-caption text-muted" aria-hidden="true">
          <span><i class="ln ln-popup" />{{ t('asis.navmap.legendPopup') }}</span>
          <span><i class="ln ln-link" />{{ t('asis.navmap.legendLink') }}</span>
          <span><i class="ln ln-tab" />{{ t('asis.navmap.legendTab') }}</span>
        </div>
        <VBtn size="small" variant="tonal" prepend-icon="ri-image-line" :loading="exporting" :disabled="!nodes.length" @click="exportPng">
          {{ t('asis.erd.exportPng') }}
        </VBtn>
      </div>
    </div>

    <VAlert v-if="!systemId" type="info" variant="tonal" class="ma-4">{{ t('asis.selectSystemFirst') }}</VAlert>
    <div v-else ref="flowEl" class="nav-canvas" data-test="navmap-canvas">
      <VProgressLinear v-if="loading || layouting" indeterminate color="primary" class="canvas-progress" />
      <VAlert v-if="loadError" type="error" variant="tonal" density="compact" class="canvas-msg">{{ loadError }}</VAlert>
      <div v-if="!loading && map && !nodes.length" class="canvas-empty text-body-2 text-muted">{{ t('asis.navmap.empty') }}</div>
      <VueFlow
        :id="FLOW_ID"
        v-model:nodes="nodes"
        v-model:edges="edges"
        :min-zoom="0.1"
        :max-zoom="2"
        :nodes-connectable="false"
        class="nav-flow"
        @nodes-initialized="fitView({ padding: 0.15 })"
        @node-click="onNodeClick"
      >
        <template #node-screen="nodeProps">
          <AsisNavScreenNode :data="nodeProps.data" />
        </template>
        <Background :gap="20" pattern-color="rgba(128,128,128,0.25)" />
        <Controls position="bottom-left" />
        <MiniMap pannable zoomable position="bottom-right" class="nav-minimap" />
      </VueFlow>
    </div>
  </div>
</template>

<style scoped>
.nav-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - var(--v-layout-top, 48px));
  min-height: 520px;
}

.nav-canvas {
  position: relative;
  flex: 1;
  min-height: 420px;
  background: rgb(var(--v-theme-background));
}

.nav-flow {
  width: 100%;
  height: 100%;
}

.canvas-progress {
  position: absolute;
  top: 0;
  left: 0;
  z-index: 5;
}

.canvas-msg {
  position: absolute;
  top: 12px;
  left: 12px;
  z-index: 5;
  max-width: 480px;
}

.canvas-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}

.legend .ln {
  display: inline-block;
  width: 26px;
  height: 0;
  margin-right: 6px;
  vertical-align: middle;
  border-top: 2px solid rgb(var(--v-theme-muted));
}

.legend .ln-popup {
  border-top-style: dashed;
  border-top-color: rgb(var(--v-theme-type-popup));
}

.legend .ln-tab {
  border-top-style: dotted;
}

.nav-flow :deep(.vue-flow__edge.nav-popup .vue-flow__edge-path) {
  stroke: rgb(var(--v-theme-type-popup));
  stroke-dasharray: 6 4;
  stroke-width: 1.6;
}

.nav-flow :deep(.vue-flow__edge.nav-link .vue-flow__edge-path) {
  stroke: rgb(var(--v-theme-muted));
  stroke-width: 1.6;
}

.nav-flow :deep(.vue-flow__edge.nav-tab .vue-flow__edge-path) {
  stroke: rgb(var(--v-theme-muted));
  stroke-dasharray: 2 3;
}

.nav-flow :deep(.vue-flow__edge-textbg) {
  fill: rgb(var(--v-theme-surface));
}

.nav-flow :deep(.vue-flow__edge-text) {
  fill: rgb(var(--v-theme-on-surface));
  font-size: 10px;
}

.nav-flow :deep(.vue-flow__controls-button) {
  background: rgb(var(--v-theme-surface));
  border-bottom-color: rgb(var(--v-theme-line));
  fill: rgb(var(--v-theme-on-surface));
}

.nav-minimap {
  background: rgb(var(--v-theme-surface));
}

@media (max-width: 959px) {
  .nav-page {
    height: auto;
  }

  .nav-canvas {
    height: 70vh;
  }
}
</style>
