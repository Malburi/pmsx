<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { deleteEdgeApi, getNodeApi, getWorkflowApi, patchEdgeApi } from '@/api/asisApi'
import AsisCrudBadges from '@/components/asis/AsisCrudBadges.vue'
import AsisEdgeAddDialog from '@/components/asis/AsisEdgeAddDialog.vue'
import AsisEdgeEditDialog from '@/components/asis/AsisEdgeEditDialog.vue'
import AsisLinksPanel from '@/components/asis/AsisLinksPanel.vue'
import AsisNodeDetail from '@/components/asis/AsisNodeDetail.vue'
import AsisNotesPanel from '@/components/asis/AsisNotesPanel.vue'
import AsisWorkflow from '@/components/asis/AsisWorkflow.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { useApi } from '@/composables/useApi'
import type { ApiError, EdgePatch, ElementSummary, NodeDetail, Workflow, WorkflowStep } from '@/types/api'
import type { EdgeAction, EdgeFrom } from '@/types/asisUi'
import { CRUD_LETTERS, typeColor } from '@/utils/asis'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

const props = withDefaults(defineProps<{
  element: ElementSummary | null
  systemId: string
  /** 편집 모드 + 편집 권한 (핫스팟·연결 수동 보정) */
  editable?: boolean
  /** 연결 이력 탭(R2)에서 결함 등록 시 화면 정보 */
  screenId?: string | null
  screenName?: string | null
  /** 화면에 캡처(실제·와이어프레임·정적 렌더)가 있는지 — 없으면 '캡처에서 못 찾음' 안내를 띄우지 않는다 */
  hasCapture?: boolean
}>(), { editable: false, screenId: null, screenName: null, hasCapture: true })
const emit = defineEmits<{
  openElement: [screenId: string, elementId: string]
  editElement: [element: ElementSummary]
  hideElement: [element: ElementSummary]
  restoreElement: [element: ElementSummary]
}>()

const { t } = useI18n()
const tab = ref<'flow' | 'tables' | 'notes' | 'links'>('flow')
const eventIndex = ref(0)
const selectedStep = ref<number | null>(null)
const selectedTableNodeId = ref<string | null>(null)
const addFrom = ref<EdgeFrom | null>(null)
const addOpen = ref(false)
const editStep = ref<WorkflowStep | null>(null)
const editSaving = ref(false)
const deleteStep = ref<WorkflowStep | null>(null)
const deleting = ref(false)
const { data: workflow, loading: wfLoading, error: wfError, execute: loadWorkflow } = useApi<Workflow>((id: string) => getWorkflowApi(id))
const { data: node, loading: nodeLoading, execute: loadNode } = useApi<NodeDetail>((id: string) => getNodeApi(id))

// ─────────── 헬퍼 ───────────
const firstSourceStep = (steps: WorkflowStep[]) => {
  const i = steps.findIndex((s) => s.hasSource && s.nodeId)
  return i >= 0 ? i : null
}

const selectStep = (i: number) => {
  selectedStep.value = i
  const nodeId = steps.value[i]?.nodeId
  if (nodeId) void loadNode(nodeId)
}

const selectTable = (nodeId: string) => {
  selectedTableNodeId.value = nodeId
  void loadNode(nodeId)
}

const reload = async (elementId: string | undefined, keepEvent = false) => {
  node.value = null
  selectedStep.value = null
  selectedTableNodeId.value = null
  if (!keepEvent) eventIndex.value = 0
  if (!elementId) { workflow.value = null; return }
  const wf = await loadWorkflow(elementId)
  // 소스가 있는 첫 단계를 미리 열어 둔다 — 버튼을 누르자마자 함수가 보이게
  const first = wf ? firstSourceStep(wf.events[eventIndex.value]?.steps ?? []) : null
  if (first !== null) selectStep(first)
}

const failKey = (e: unknown) => ((e as ApiError).code === 'FORBIDDEN' ? 'common.forbiddenAction' : 'common.saveFailed')

// 엣지 변경 결과를 화면의 단계에 바로 반영 (워크플로우 전체를 다시 읽지 않음)
const patchStep = async (step: WorkflowStep, patch: EdgePatch) => {
  if (!step.edge) return false
  try {
    const res = await patchEdgeApi(step.edge.edgeId, patch)
    step.edge.confirmed = res.confirmed
    step.edge.status = res.status
    step.edge.condition = res.condition
    if (patch.crud !== undefined) step.crud = res.crud
    showSuccessToast('asis.edge.saved')
    return true
  } catch (e) {
    showErrorToast(failKey(e))
    return false
  }
}

const onEdgeAction = (i: number, action: EdgeAction) => {
  const step = steps.value[i]
  if (!step?.edge) return
  if (action === 'confirm') void patchStep(step, { confirmed: true })
  else if (action === 'unconfirm') void patchStep(step, { confirmed: false })
  else if (action === 'reject') void patchStep(step, { status: 'rejected' })
  else if (action === 'activate') void patchStep(step, { status: 'active' })
  else if (action === 'edit') editStep.value = step
  else if (action === 'delete') deleteStep.value = step
}

const saveEdgeEdit = async (patch: EdgePatch) => {
  const step = editStep.value
  if (!step) return
  editSaving.value = true
  if (await patchStep(step, patch)) editStep.value = null
  editSaving.value = false
}

const confirmDeleteEdge = async () => {
  const step = deleteStep.value
  if (!step?.edge) return
  deleting.value = true
  try {
    await deleteEdgeApi(step.edge.edgeId)
    deleteStep.value = null
    showSuccessToast('asis.edge.deleted')
    void reload(props.element?.elementId, true)
  } catch (e) {
    showErrorToast((e as ApiError).code === 'FORBIDDEN' ? 'common.forbiddenAction' : 'common.deleteFailed')
  } finally {
    deleting.value = false
  }
}

const openAddFromElement = () => {
  if (!props.element) return
  addFrom.value = { elementId: props.element.elementId, label: `${props.element.seqNo}. ${props.element.label} (${props.element.elementCode})`, lane: null }
  addOpen.value = true
}

const openAddFromStep = (i: number) => {
  const s = steps.value[i]
  if (!s?.nodeId) return
  addFrom.value = { nodeId: s.nodeId, label: s.title, lane: s.lane }
  addOpen.value = true
}

const onEdgeAdded = () => {
  showSuccessToast('asis.edge.added')
  void reload(props.element?.elementId, true)
}

// ─────────── computed ───────────
const steps = computed(() => workflow.value?.events[eventIndex.value]?.steps ?? [])
const tables = computed(() => {
  const map = new Map<string, { nodeId: string; name: string; crud: string; columns: Set<string> }>()
  steps.value.filter((s) => s.lane === 'DB' && s.nodeId && s.edge?.status !== 'rejected').forEach((s) => {
    const cur = map.get(s.nodeId as string) ?? { nodeId: s.nodeId as string, name: s.title, crud: '', columns: new Set<string>() }
    cur.crud = CRUD_LETTERS.filter((c) => cur.crud.includes(c) || (s.crud ?? '').includes(c)).join('')
    s.columns?.forEach((c) => cur.columns.add(c))
    map.set(cur.nodeId, cur)
  })
  return [...map.values()].map((v) => ({ ...v, columns: [...v.columns] }))
})
const matchNotice = computed(() => {
  const m = props.element?.matched
  if (m === 'dynamic-only') return t('asis.viewer.detail.dynamicOnly')
  if (m === 'static-only' && props.hasCapture) return t('asis.viewer.detail.staticOnly')
  return null
})

// ─────────── watch ───────────
watch(() => props.element?.elementId, (id) => { void reload(id) }, { immediate: true })

watch(eventIndex, () => {
  selectedStep.value = null
  node.value = null
  const first = firstSourceStep(steps.value)
  if (first !== null) selectStep(first)
})
</script>

<template>
  <div class="element-panel">
    <div v-if="!element" class="empty pa-8 text-center text-body-2 text-muted">
      <VIcon icon="ri-cursor-line" size="32" class="mb-3" />
      <div>{{ t('asis.viewer.detail.emptyTitle') }}</div>
      <div class="text-caption mt-1">{{ t('asis.viewer.detail.emptyHint') }}</div>
    </div>

    <template v-else>
      <div class="pa-4 border-b border-line">
        <div class="d-flex flex-wrap align-center ga-2 mb-2">
          <span class="seq" :class="`bg-${typeColor(element.type)}`">{{ element.seqNo }}</span>
          <h3 class="text-subtitle-1 font-weight-bold">{{ element.label }}</h3>
          <VChip size="x-small" variant="outlined" label>{{ t(`asis.type.${element.type}`) }}</VChip>
          <VChip v-if="element.manual" size="x-small" color="src-manual" variant="tonal">{{ t('asis.edit.manualChip') }}</VChip>
          <VChip v-if="element.hidden" size="x-small" variant="tonal">{{ t('asis.edit.hiddenChip') }}</VChip>
          <VChip v-if="element.danger" size="x-small" color="warning" variant="tonal" prepend-icon="ri-alarm-warning-line">
            {{ t('asis.viewer.detail.danger') }}
          </VChip>
          <VSpacer />
          <template v-if="editable">
            <VBtn icon="ri-edit-line" size="x-small" variant="text" :aria-label="t('asis.edit.editElement')" data-test="el-edit" @click="emit('editElement', element)" />
            <VBtn
              v-if="element.hidden"
              icon="ri-eye-line"
              size="x-small"
              variant="text"
              :aria-label="t('asis.edit.restore')"
              @click="emit('restoreElement', element)"
            />
            <VBtn
              v-else
              :icon="element.manual ? 'ri-delete-bin-line' : 'ri-eye-off-line'"
              size="x-small"
              variant="text"
              :aria-label="element.manual ? t('asis.edit.delete') : t('asis.edit.hide')"
              data-test="el-hide"
              @click="emit('hideElement', element)"
            />
          </template>
        </div>
        <dl class="kv text-caption">
          <dt>{{ t('asis.viewer.detail.elementId') }}</dt><dd class="font-mono">{{ element.elementCode }}</dd>
          <dt>{{ t('asis.viewer.detail.binding') }}</dt><dd>{{ element.binding ?? '-' }}</dd>
          <dt>{{ t('asis.viewer.detail.events') }}</dt>
          <dd class="font-mono">
            <template v-if="element.events.length">
              <div v-for="(ev, i) in element.events" :key="i">{{ ev.event }} → {{ ev.handler }}</div>
            </template>
            <template v-else>{{ t('asis.viewer.detail.noEvent') }}</template>
          </dd>
          <dt>{{ t('asis.viewer.detail.box') }}</dt>
          <dd class="font-mono">
            {{ element.box ? `x ${element.box.x}, y ${element.box.y}, ${element.box.w}×${element.box.h}` : t('asis.viewer.detail.noBox') }}
          </dd>
        </dl>
      </div>

      <VTabs v-model="tab" density="compact" color="primary" class="border-b border-line panel-tabs">
        <VTab value="flow">{{ t('asis.viewer.detail.tabFlow') }}</VTab>
        <VTab value="tables">{{ t('asis.viewer.detail.tabTables') }}</VTab>
        <VTab value="notes" data-test="panel-tab-notes">{{ t('asis.notes.tab') }}</VTab>
        <VTab value="links">{{ t('asis.viewer.detail.tabLinksOnly') }}</VTab>
      </VTabs>

      <div class="pa-4">
        <VAlert v-if="matchNotice && tab === 'flow'" type="warning" variant="tonal" density="compact" class="mb-3">{{ matchNotice }}</VAlert>

        <template v-if="tab === 'flow'">
          <div v-if="editable" class="d-flex mb-3">
            <VBtn size="small" variant="tonal" color="primary" prepend-icon="ri-git-branch-line" data-test="edge-add-element" @click="openAddFromElement">
              {{ t('asis.edge.addFromElement') }}
            </VBtn>
          </div>
          <VProgressLinear v-if="wfLoading" indeterminate color="primary" />
          <VAlert v-else-if="wfError" type="error" variant="tonal" density="compact">
            {{ wfError.message }}
            <VBtn variant="text" size="small" @click="reload(element.elementId)">{{ t('common.retry') }}</VBtn>
          </VAlert>
          <template v-else-if="workflow">
            <div v-if="workflow.events.length > 1" class="d-flex flex-wrap ga-1 mb-3">
              <VChip
                v-for="(ev, i) in workflow.events"
                :key="i"
                size="small"
                :color="i === eventIndex ? 'primary' : undefined"
                :variant="i === eventIndex ? 'flat' : 'outlined'"
                @click="eventIndex = i"
              >{{ ev.event }}</VChip>
            </div>
            <div v-if="steps.length === 0" class="text-body-2 text-muted py-4">{{ t('asis.viewer.workflow.empty') }}</div>
            <AsisWorkflow
              v-else
              :steps="steps"
              :selected-index="selectedStep"
              :editable="editable"
              @select="selectStep"
              @edge-action="onEdgeAction"
              @add-from="openAddFromStep"
            />
            <div v-if="selectedStep !== null" class="mt-4">
              <AsisNodeDetail
                :node="node"
                :system-id="systemId"
                :loading="nodeLoading"
                @open-element="(s: string, e: string) => emit('openElement', s, e)"
                @select-node="(id: string) => loadNode(id)"
              />
            </div>
          </template>
        </template>

        <template v-else-if="tab === 'tables'">
          <div v-if="tables.length === 0" class="text-body-2 text-muted py-4">{{ t('asis.viewer.detail.noTables') }}</div>
          <VList v-else density="compact" class="py-0 border rounded-lg mb-3">
            <VListItem
              v-for="tb in tables"
              :key="tb.nodeId"
              :active="tb.nodeId === selectedTableNodeId"
              @click="selectTable(tb.nodeId)"
            >
              <VListItemTitle class="font-mono">
                {{ tb.name }} <AsisCrudBadges :crud="tb.crud" class="ml-1" />
              </VListItemTitle>
              <VListItemSubtitle v-if="tb.columns.length" class="font-mono">{{ tb.columns.join(', ') }}</VListItemSubtitle>
            </VListItem>
          </VList>
          <AsisNodeDetail
            v-if="selectedTableNodeId"
            :node="node"
            :system-id="systemId"
            :loading="nodeLoading"
            @open-element="(s: string, e: string) => emit('openElement', s, e)"
            @select-node="(id: string) => loadNode(id)"
          />
        </template>

        <AsisNotesPanel v-else-if="tab === 'notes'" target-type="ELEMENT" :target-id="element.elementId" />

        <AsisLinksPanel v-else :element="element" :screen-id="screenId" :screen-name="screenName" />
      </div>
    </template>

    <AsisEdgeAddDialog v-model="addOpen" :system-id="systemId" :from="addFrom" @added="onEdgeAdded" />
    <AsisEdgeEditDialog
      :model-value="!!editStep"
      :step="editStep"
      :saving="editSaving"
      @update:model-value="(v: boolean) => { if (!v) editStep = null }"
      @save="saveEdgeEdit"
    />
    <ConfirmDialog
      :model-value="!!deleteStep"
      :title="t('asis.edge.deleteConfirmTitle')"
      :message="t('asis.edge.deleteConfirm')"
      :confirm-text="t('common.delete')"
      :loading="deleting"
      @update:model-value="(v: boolean) => { if (!v) deleteStep = null }"
      @confirm="confirmDeleteEdge"
    />
  </div>
</template>

<style scoped>
.seq {
  min-width: 22px;
  height: 22px;
  padding: 0 6px;
  border-radius: 11px;
  font: 700 12px/22px var(--pmsx-font);
  text-align: center;
}

.kv {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr);
  gap: 3px 8px;
  margin: 0;
}

.kv dt {
  color: rgb(var(--v-theme-muted));
}

.kv dd {
  margin: 0;
  word-break: break-all;
}

.panel-tabs {
  position: sticky;
  top: 0;
  z-index: 2;
  background: rgb(var(--v-theme-surface));
}
</style>
