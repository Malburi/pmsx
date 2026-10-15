<route lang="yaml">
meta:
  compactNav: true
</route>

<script setup lang="ts">
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MarkerType, VueFlow, useVueFlow, type Edge, type Node } from '@vue-flow/core'
import { MiniMap } from '@vue-flow/minimap'
import { storeToRefs } from 'pinia'
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useTheme } from 'vuetify'
import { decideRelationApi, getErdApi, getSubjectAreasApi, getTablesApi } from '@/api/tableApi'
import AsisErdTableNode from '@/components/asis/AsisErdTableNode.vue'
import AsisRelationAddDialog from '@/components/asis/AsisRelationAddDialog.vue'
import AsisRelationItem from '@/components/asis/AsisRelationItem.vue'
import AsisSystemSelect from '@/components/asis/AsisSystemSelect.vue'
import { useApi } from '@/composables/useApi'
import { useGraphLayout } from '@/composables/useGraphLayout'
import { useAuthStore } from '@/stores/auth'
import { useProjectStore } from '@/stores/project'
import type { ApiError, Erd, Relation, RelationDecision } from '@/types/api'
import { saveBlob } from '@/utils/download'
import { exportFlowPng, pngFileName } from '@/utils/flowImage'
import { erdToFlow, type FlowNode } from '@/utils/graph'
import { RELATION_ROLES } from '@/utils/permission'
import { showErrorToast, showSuccessToast } from '@/utils/toast'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'

// SCR-AS-006 ERD (FR-AS-018)
const FLOW_ID = 'asis-erd'
const MAX_TABLES = 300

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const theme = useTheme()
const auth = useAuthStore()
const { systems } = storeToRefs(useProjectStore())
const { fitView, getNodes } = useVueFlow(FLOW_ID)
const { layouting, layout } = useGraphLayout()

const systemId = ref<string | null>(null)
const subjectArea = ref<string | null>(typeof route.query.subjectArea === 'string' ? route.query.subjectArea : null)
const tableIds = ref<string[]>(typeof route.query.tableIds === 'string' && route.query.tableIds ? route.query.tableIds.split(',') : [])
const includeInferred = ref(true)
const showRejected = ref(false)
const erd = ref<Erd | null>(null)
const nodes = ref<Node[]>([])
const edges = ref<Edge[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)
const selectedRelationId = ref<string | null>(null)
const deciding = ref(false)
const addOpen = ref(false)
const exporting = ref(false)
const flowEl = ref<HTMLElement | null>(null)
const tableOptions = ref<{ title: string; value: string }[]>([])
const { data: areas, execute: loadAreas } = useApi<{ name: string; tableCount: number }[]>((id: string) => getSubjectAreasApi(id))

// ─────────── 헬퍼 ───────────
const buildEdges = () => {
  if (!erd.value) return
  const { edges: list } = erdToFlow(erd.value, { showInferred: includeInferred.value, showRejected: showRejected.value })
  edges.value = list.map((e) => ({
    ...e,
    markerEnd: MarkerType.ArrowClosed,
    selected: e.id === selectedRelationId.value,
    labelBgPadding: [4, 2] as [number, number],
    labelBgBorderRadius: 3,
  }))
}

const load = async () => {
  if (!systemId.value) return
  loading.value = true
  loadError.value = null
  selectedRelationId.value = null
  try {
    erd.value = await getErdApi(systemId.value, {
      subjectArea: subjectArea.value ?? undefined,
      tableIds: tableIds.value.length ? tableIds.value.join(',') : undefined,
      includeInferred: true, // 화면에서 켜고 끄므로 항상 받는다
    })
    await relayout()
  } catch (e) {
    loadError.value = (e as ApiError).message ?? t('common.error.unknown')
    erd.value = null
    nodes.value = []
    edges.value = []
  } finally {
    loading.value = false
  }
}

const relayout = async () => {
  if (!erd.value) return
  const flow = erdToFlow(erd.value, { showInferred: includeInferred.value, showRejected: showRejected.value })
  const placed = await layout(flow.nodes as FlowNode[], flow.edges, 'RIGHT')
  const focus = new Set(tableIds.value)
  nodes.value = placed.map((n) => ({ ...n, data: { ...(n.data as object), highlight: focus.size > 0 && focus.has(n.id) && tableIds.value.length < 4 } })) as Node[]
  buildEdges()
  await nextTick()
  void fitView({ padding: 0.12 })
}

const syncQuery = () => {
  void router.replace({
    query: { ...route.query, subjectArea: subjectArea.value ?? undefined, tableIds: tableIds.value.length ? tableIds.value.join(',') : undefined },
  })
}

// 테이블 선택 목록은 주제영역 기준으로 최대 200개를 가져온다
const loadTableOptions = async () => {
  if (!systemId.value) return
  try {
    const page = await getTablesApi(systemId.value, { subjectArea: subjectArea.value ?? undefined, size: 200 })
    tableOptions.value = page.content.map((tb) => ({ title: tb.name, value: tb.tableId }))
  } catch {
    tableOptions.value = []
  }
}

const onEdgeClick = ({ edge }: { edge: Edge }) => {
  selectedRelationId.value = edge.id
  buildEdges()
}

const decide = async (decision: RelationDecision) => {
  const rel = selectedRelation.value
  if (!rel || !erd.value) return
  deciding.value = true
  try {
    const updated = await decideRelationApi(rel.relationId, decision)
    erd.value.relations = erd.value.relations.map((r) => (r.relationId === updated.relationId ? updated : r))
    buildEdges()
    showSuccessToast('asis.relation.decided')
  } catch (e) {
    showErrorToast((e as ApiError).code === 'FORBIDDEN' ? 'common.forbiddenAction' : 'common.saveFailed')
  } finally {
    deciding.value = false
  }
}

const onRelationAdded = (rel: Relation) => {
  if (!erd.value) return
  erd.value.relations = [...erd.value.relations, rel]
  selectedRelationId.value = rel.relationId
  buildEdges()
  showSuccessToast('asis.relation.added')
}

const openTable = (tableId: string) => {
  void router.push({ path: '/asis/tables', query: { systemId: systemId.value ?? undefined, tableId } })
}

const exportPng = async () => {
  if (!flowEl.value) return
  exporting.value = true
  try {
    const blob = await exportFlowPng(flowEl.value, getNodes.value, theme.current.value.colors.background)
    saveBlob(blob, pngFileName(t('asis.erd.fileName', { system: systemCode.value })))
  } catch {
    showErrorToast('asis.erd.exportFailed')
  } finally {
    exporting.value = false
  }
}

// ─────────── computed ───────────
const canDecide = computed(() => auth.hasAnyRole(RELATION_ROLES))
const systemCode = computed(() => systems.value.find((s) => s.systemId === systemId.value)?.code ?? '')
const areaItems = computed(() => (areas.value ?? []).map((a) => ({ title: `${a.name} (${a.tableCount})`, value: a.name })))
const selectedRelation = computed(() => erd.value?.relations.find((r) => r.relationId === selectedRelationId.value) ?? null)
const tooMany = computed(() => (erd.value?.tables.length ?? 0) >= MAX_TABLES)

// ─────────── watch ───────────
watch(systemId, (id) => {
  if (!id) return
  void loadAreas(id)
  void loadTableOptions()
  void load()
})

watch(subjectArea, () => {
  tableIds.value = []
  syncQuery()
  void loadTableOptions()
  void load()
})

watch([includeInferred, showRejected], () => { void relayout() })
</script>

<template>
  <div class="erd-page">
    <div class="erd-toolbar px-4 py-3 bg-surface border-b border-line">
      <div class="d-flex flex-wrap align-center ga-3">
        <div class="mr-2">
          <h1 class="pmsx-page-title">{{ t('nav.asis.erd') }}</h1>
          <div class="text-caption text-muted">{{ t('asis.erd.subtitle') }}</div>
        </div>
        <AsisSystemSelect v-model="systemId" />
        <VSelect
          v-model="subjectArea"
          :items="areaItems"
          :label="t('asis.erd.subjectArea')"
          clearable
          hide-details
          class="field-md"
          data-test="erd-subject"
        />
        <VAutocomplete
          v-model="tableIds"
          :items="tableOptions"
          :label="t('asis.erd.tables')"
          :placeholder="t('asis.erd.tablesHint')"
          multiple
          chips
          closable-chips
          clearable
          hide-details
          class="field-lg"
          data-test="erd-tables"
          @update:menu="(open: boolean) => { if (!open) { syncQuery(); load() } }"
        />
        <VSwitch v-model="includeInferred" :label="t('asis.erd.includeInferred')" color="primary" hide-details density="compact" class="flex-grow-0" />
        <VSwitch v-model="showRejected" :label="t('asis.erd.showRejected')" color="primary" hide-details density="compact" class="flex-grow-0" />
        <VSpacer />
        <VBtn size="small" variant="text" prepend-icon="ri-refresh-line" :loading="layouting" @click="relayout">{{ t('asis.erd.relayout') }}</VBtn>
        <VBtn v-if="canDecide" size="small" variant="tonal" prepend-icon="ri-links-line" :disabled="!erd?.tables.length" data-test="erd-add-relation" @click="addOpen = true">
          {{ t('asis.relation.add') }}
        </VBtn>
        <VBtn size="small" variant="tonal" prepend-icon="ri-image-line" :loading="exporting" :disabled="!nodes.length" @click="exportPng">
          {{ t('asis.erd.exportPng') }}
        </VBtn>
      </div>
      <div class="legend d-flex flex-wrap ga-4 mt-2 text-caption text-muted" aria-hidden="true">
        <span><i class="ln ln-solid" />{{ t('asis.erd.legendFk') }}</span>
        <span><i class="ln ln-inferred" />{{ t('asis.erd.legendInferred') }}</span>
        <span><i class="ln ln-manual" />{{ t('asis.erd.legendManual') }}</span>
        <span><i class="ln ln-rejected" />{{ t('asis.erd.legendRejected') }}</span>
      </div>
    </div>

    <VAlert v-if="!systemId" type="info" variant="tonal" class="ma-4">{{ t('asis.selectSystemFirst') }}</VAlert>
    <div v-else ref="flowEl" class="erd-canvas" data-test="erd-canvas">
      <VProgressLinear v-if="loading || layouting" indeterminate color="primary" class="canvas-progress" />
      <VAlert v-if="loadError" type="error" variant="tonal" density="compact" class="canvas-msg">{{ loadError }}</VAlert>
      <VAlert v-else-if="tooMany" type="warning" variant="tonal" density="compact" class="canvas-msg">{{ t('asis.erd.tooMany') }}</VAlert>
      <div v-if="!loading && erd && !erd.tables.length" class="canvas-empty text-body-2 text-muted">{{ t('asis.erd.empty') }}</div>
      <VueFlow
        :id="FLOW_ID"
        v-model:nodes="nodes"
        v-model:edges="edges"
        :min-zoom="0.1"
        :max-zoom="2"
        :default-edge-options="{ type: 'smoothstep' }"
        :nodes-connectable="false"
        :elevate-edges-on-select="true"
        class="erd-flow"
        @nodes-initialized="fitView({ padding: 0.12 })"
        @edge-click="onEdgeClick"
        @pane-click="selectedRelationId = null; buildEdges()"
      >
        <template #node-table="nodeProps">
          <AsisErdTableNode :data="nodeProps.data" />
        </template>
        <Background :gap="20" pattern-color="rgba(128,128,128,0.25)" />
        <Controls position="bottom-left" />
        <MiniMap pannable zoomable position="bottom-right" class="erd-minimap" />
      </VueFlow>

      <VCard v-if="selectedRelation" class="relation-panel" border elevation="4" data-test="erd-relation-panel">
        <VCardTitle class="d-flex align-center text-subtitle-2">
          {{ t('asis.erd.relationPanel') }}
          <VSpacer />
          <VBtn icon="ri-close-line" size="x-small" variant="text" :aria-label="t('common.close')" @click="selectedRelationId = null; buildEdges()" />
        </VCardTitle>
        <VCardText>
          <AsisRelationItem
            :relation="selectedRelation"
            :can-decide="canDecide"
            :busy="deciding"
            @decide="decide"
            @open-table="openTable"
          />
        </VCardText>
      </VCard>
    </div>

    <AsisRelationAddDialog v-model="addOpen" :tables="erd?.tables ?? []" @added="onRelationAdded" />
  </div>
</template>

<style scoped>
.erd-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - var(--v-layout-top, 48px));
  min-height: 520px;
}

.field-md {
  min-width: 160px;
  max-width: 220px;
}

.field-lg {
  min-width: 220px;
  max-width: 420px;
}

.erd-canvas {
  position: relative;
  flex: 1;
  min-height: 420px;
  background: rgb(var(--v-theme-background));
}

.erd-flow {
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
  z-index: 1;
  pointer-events: none;
}

.relation-panel {
  position: absolute;
  top: 12px;
  right: 12px;
  width: min(380px, calc(100% - 24px));
  z-index: 6;
}

.legend .ln {
  display: inline-block;
  width: 26px;
  height: 0;
  margin-right: 6px;
  vertical-align: middle;
  border-top: 2px solid rgb(var(--v-theme-muted));
}

.legend .ln-inferred {
  border-top-style: dashed;
  border-top-color: rgb(var(--v-theme-warning));
}

.legend .ln-manual {
  border-top-color: rgb(var(--v-theme-src-manual));
}

.legend .ln-rejected {
  border-top-style: dotted;
  opacity: 0.5;
}

/* 관계 선 스타일 (utils/graph relationClass) */
.erd-flow :deep(.vue-flow__edge.rel-solid .vue-flow__edge-path) {
  stroke: rgb(var(--v-theme-muted));
  stroke-width: 1.6;
}

.erd-flow :deep(.vue-flow__edge.rel-inferred .vue-flow__edge-path) {
  stroke: rgb(var(--v-theme-warning));
  stroke-width: 1.6;
  stroke-dasharray: 6 4;
}

.erd-flow :deep(.vue-flow__edge.rel-manual .vue-flow__edge-path) {
  stroke: rgb(var(--v-theme-src-manual));
  stroke-width: 1.8;
}

.erd-flow :deep(.vue-flow__edge.rel-rejected .vue-flow__edge-path) {
  stroke: rgb(var(--v-theme-muted));
  stroke-dasharray: 2 4;
  opacity: 0.45;
}

.erd-flow :deep(.vue-flow__edge.selected .vue-flow__edge-path) {
  stroke: rgb(var(--v-theme-primary));
  stroke-width: 2.6;
}

.erd-flow :deep(.vue-flow__edge-textbg) {
  fill: rgb(var(--v-theme-surface));
}

.erd-flow :deep(.vue-flow__edge-text) {
  fill: rgb(var(--v-theme-on-surface));
  font-size: 10px;
  font-family: var(--pmsx-mono);
}

.erd-flow :deep(.vue-flow__controls-button) {
  background: rgb(var(--v-theme-surface));
  border-bottom-color: rgb(var(--v-theme-line));
  fill: rgb(var(--v-theme-on-surface));
}

.erd-minimap {
  background: rgb(var(--v-theme-surface));
}

@media (max-width: 959px) {
  .erd-page {
    height: auto;
  }

  .erd-canvas {
    height: 70vh;
  }
}
</style>
