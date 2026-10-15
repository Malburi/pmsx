<route lang="yaml">
meta:
  compactNav: true
</route>

<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import {
  createElementApi, deleteElementApi, getMenuTreeApi, getScreenApi, getScreenCrudApi, patchAnalysisApi, restoreElementApi,
  updateElementApi,
} from '@/api/asisApi'
import AsisAnalysisHistory from '@/components/asis/AsisAnalysisHistory.vue'
import AsisCaptureStage from '@/components/asis/AsisCaptureStage.vue'
import AsisCaptureUploadDialog from '@/components/asis/AsisCaptureUploadDialog.vue'
import AsisCrudTable from '@/components/asis/AsisCrudTable.vue'
import AsisElementEditDialog from '@/components/asis/AsisElementEditDialog.vue'
import AsisElementPanel from '@/components/asis/AsisElementPanel.vue'
import AsisElementTable from '@/components/asis/AsisElementTable.vue'
import AsisMenuTree from '@/components/asis/AsisMenuTree.vue'
import AsisNotesPanel from '@/components/asis/AsisNotesPanel.vue'
import AsisScreenHeader from '@/components/asis/AsisScreenHeader.vue'
import AsisStatusDialog from '@/components/asis/AsisStatusDialog.vue'
import AttachmentPanel from '@/components/common/AttachmentPanel.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import ExcelExportButton from '@/components/common/ExcelExportButton.vue'
import { useApi } from '@/composables/useApi'
import { useAuthStore } from '@/stores/auth'
import { useProjectStore } from '@/stores/project'
import type {
  AnalysisPatch, AnalysisStatus, ApiError, Box, CaptureInfo, CrudMatrix, ElementPayload, ElementSummary, MenuNode, ScreenDetail,
} from '@/types/api'
import { allowedStatuses, canEditScreen } from '@/utils/permission'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

const { t } = useI18n()
const route = useRoute('/asis/viewer/[systemId]/[[screenId]]')
const router = useRouter()
const projectStore = useProjectStore()
const auth = useAuthStore()
const { systems } = storeToRefs(projectStore)

const centerTab = ref<'capture' | 'components' | 'events' | 'crud'>('capture')
const savingStatus = ref(false)
const attachOpen = ref(false)
// 첨부 개수는 패널이 목록을 읽을 때 갱신된다 (null = 아직 모름)
const attachCount = ref<number | null>(null)
// 핫스팟 편집 모드 (FR-AS-019·020)
const editMode = ref(false)
const elDialog = ref<{ open: boolean; element: ElementSummary | null; box: Box | null; saving: boolean; error: string | null }>(
  { open: false, element: null, box: null, saving: false, error: null },
)
const hideTarget = ref<ElementSummary | null>(null)
const hiding = ref(false)
const captureUploadOpen = ref(false)
// 상태 변경 대화상자 (코멘트·검토자)
const statusDialog = ref<{ open: boolean; status: AnalysisStatus | null; error: string | null }>({ open: false, status: null, error: null })
const infoTab = ref<'notes' | 'history'>('notes')
const historyKey = ref(0)
const { data: menu, loading: menuLoading, execute: loadMenu } = useApi<MenuNode[]>((id: string) => getMenuTreeApi(id))
const { data: screen, loading: screenLoading, error: screenError, execute: loadScreen } = useApi<ScreenDetail>((id: string) => getScreenApi(id))
const { data: crud, loading: crudLoading, execute: loadCrud } = useApi<CrudMatrix>((id: string) => getScreenCrudApi(id))

// ─────────── 헬퍼 ───────────
const firstScreenId = (nodes: MenuNode[]): string | null => {
  for (const n of nodes) {
    if (n.screen) return n.screen.screenId
    const child = firstScreenId(n.children)
    if (child) return child
  }
  return null
}

const updateTreeStatus = (nodes: MenuNode[], screenId: string, status: AnalysisStatus) => {
  nodes.forEach((n) => {
    if (n.screen?.screenId === screenId) n.screen.status = status
    updateTreeStatus(n.children, screenId, status)
  })
}

const openScreen = (screenId: string, elementId: string | null = null) => {
  void router.push({
    path: `/asis/viewer/${route.params.systemId}/${screenId}`,
    query: elementId ? { el: elementId } : {},
  })
}

const selectElement = (elementId: string) => {
  void router.replace({ query: { ...route.query, el: elementId } })
}

const changeSystem = (systemId: string) => {
  void router.push(`/asis/viewer/${systemId}`)
}

const changeStatus = (status: AnalysisStatus) => {
  if (!screen.value || status === screen.value.analysis.status) return
  statusDialog.value = { open: true, status, error: null }
}

const saveStatus = async (patch: AnalysisPatch) => {
  if (!screen.value) return
  savingStatus.value = true
  statusDialog.value.error = null
  try {
    screen.value.analysis = await patchAnalysisApi(screen.value.screenId, patch)
    if (menu.value && patch.status) updateTreeStatus(menu.value, screen.value.screenId, patch.status)
    statusDialog.value.open = false
    historyKey.value += 1
    showSuccessToast('asis.statusDialog.saved')
    void projectStore.loadSystems()
  } catch (e) {
    const err = e as ApiError
    statusDialog.value.error = err.code === 'FORBIDDEN' ? t('common.forbiddenAction') : (err.message ?? t('asis.viewer.header.statusFailed'))
  } finally {
    savingStatus.value = false
  }
}

// ─────────── 핫스팟 편집 ───────────
const replaceElement = (updated: ElementSummary) => {
  if (!screen.value) return
  const list = screen.value.elements
  const i = list.findIndex((e) => e.elementId === updated.elementId)
  if (i >= 0) list.splice(i, 1, updated)
  else list.push(updated)
}

const failText = (e: unknown, fallbackKey: string) => {
  const err = e as ApiError
  if (err.code === 'FORBIDDEN') return t('common.forbiddenAction')
  if (err.code === 'CONFLICT') return t('asis.edit.conflict')
  return err.message ?? t(fallbackKey)
}

const openCreate = (box: Box) => {
  elDialog.value = { open: true, element: null, box, saving: false, error: null }
}

const openEditElement = (el: ElementSummary) => {
  elDialog.value = { open: true, element: el, box: el.box, saving: false, error: null }
}

const saveElement = async (payload: ElementPayload) => {
  if (!screen.value) return
  const editing = elDialog.value.element
  elDialog.value.saving = true
  elDialog.value.error = null
  try {
    if (editing) {
      replaceElement(await updateElementApi(editing.elementId, { label: payload.label, type: payload.type, box: payload.box, binding: payload.binding }))
      showSuccessToast('asis.edit.updated')
    } else {
      const created = await createElementApi(screen.value.screenId, payload)
      replaceElement(created)
      selectElement(created.elementId)
      showSuccessToast('asis.edit.created')
    }
    elDialog.value.open = false
  } catch (e) {
    elDialog.value.error = failText(e, 'common.saveFailed')
  } finally {
    elDialog.value.saving = false
  }
}

// 드래그 이동·크기 조절 — 먼저 화면에 반영하고, 실패하면 되돌린다
const moveElement = async (id: string, box: Box) => {
  const el = screen.value?.elements.find((e) => e.elementId === id)
  if (!el) return
  const before = el.box
  el.box = box
  try {
    replaceElement(await updateElementApi(id, { box }))
    showSuccessToast('asis.edit.moved')
  } catch (e) {
    el.box = before
    showErrorToast((e as ApiError).code === 'FORBIDDEN' ? 'common.forbiddenAction' : 'common.saveFailed')
  }
}

const confirmHide = async () => {
  const target = hideTarget.value
  if (!target || !screen.value) return
  hiding.value = true
  try {
    await deleteElementApi(target.elementId)
    if (target.manual) {
      screen.value.elements = screen.value.elements.filter((e) => e.elementId !== target.elementId)
      if (elementId.value === target.elementId) void router.replace({ query: { ...route.query, el: undefined } })
      showSuccessToast('asis.edit.deleted')
    } else {
      replaceElement({ ...target, hidden: true })
      showSuccessToast('asis.edit.hidden')
    }
    hideTarget.value = null
  } catch (e) {
    showErrorToast((e as ApiError).code === 'FORBIDDEN' ? 'common.forbiddenAction' : 'common.deleteFailed')
  } finally {
    hiding.value = false
  }
}

const restoreElement = async (el: ElementSummary) => {
  try {
    replaceElement(await restoreElementApi(el.elementId))
    showSuccessToast('asis.edit.restored')
  } catch (e) {
    showErrorToast((e as ApiError).code === 'FORBIDDEN' ? 'common.forbiddenAction' : 'common.saveFailed')
  }
}

const toggleEdit = () => {
  editMode.value = !editMode.value
  if (editMode.value) centerTab.value = 'capture'
}

const onCaptureUploaded = (capture: CaptureInfo) => {
  if (screen.value) screen.value.capture = capture
  showSuccessToast('asis.edit.captureUploaded')
}

// ─────────── computed ───────────
const systemId = computed(() => route.params.systemId)
const screenId = computed(() => route.params.screenId || null)
const elementId = computed(() => (typeof route.query.el === 'string' ? route.query.el : null))
const currentSystem = computed(() => systems.value.find((s) => s.systemId === systemId.value) ?? null)
const systemItems = computed(() => systems.value.map((s) => ({ title: `${s.name} (${s.code})`, value: s.systemId })))
const selectedElement = computed(() => screen.value?.elements.find((e) => e.elementId === elementId.value) ?? null)
const canEdit = computed(() => canEditScreen(auth.hasAnyRole, screen.value?.analysis ?? null, auth.userId))
const editable = computed(() => editMode.value && canEdit.value)
const statuses = computed(() => allowedStatuses(auth.hasAnyRole))
const existingCodes = computed(() => screen.value?.elements.map((e) => e.elementCode) ?? [])

// ─────────── watch ───────────
watch(systemId, async (id) => {
  if (!id) return
  const tree = await loadMenu(id)
  // 화면을 지정하지 않았으면 첫 화면을 연다
  if (!screenId.value && tree) {
    const first = firstScreenId(tree)
    if (first) void router.replace(`/asis/viewer/${id}/${first}`)
  }
}, { immediate: true })

watch(screenId, (id) => {
  crud.value = null
  attachCount.value = null
  infoTab.value = 'notes'
  if (id) void loadScreen(id)
  else screen.value = null
}, { immediate: true })

watch([centerTab, screenId], ([tab, id]) => {
  if (tab === 'crud' && id && !crud.value) void loadCrud(id)
})
</script>

<template>
  <VContainer fluid class="pa-0 viewer">
    <VRow no-gutters>
      <VCol cols="12" md="3" lg="2" class="viewer-col col-tree bg-surface">
        <div class="pa-3 pb-0">
          <VSelect
            :model-value="systemId"
            :items="systemItems"
            :label="t('asis.viewer.system')"
            prepend-inner-icon="ri-server-line"
            data-test="viewer-system"
            @update:model-value="changeSystem"
          />
        </div>
        <AsisMenuTree
          :system-id="systemId"
          :system="currentSystem"
          :nodes="menu ?? []"
          :loading="menuLoading"
          :selected-screen-id="screenId"
          class="tree-fill"
          @select="openScreen"
        />
      </VCol>

      <VCol cols="12" md="9" lg="6" class="viewer-col col-center">
        <VProgressLinear v-if="screenLoading" indeterminate color="primary" />
        <VAlert v-if="screenError" type="error" variant="tonal" class="ma-4">
          {{ screenError.message }}
          <VBtn variant="text" size="small" @click="screenId && loadScreen(screenId)">{{ t('common.retry') }}</VBtn>
        </VAlert>
        <div v-else-if="!screen && !screenLoading" class="pa-8 text-center text-muted">{{ t('asis.viewer.selectScreen') }}</div>
        <template v-if="screen">
          <div class="bg-surface">
            <AsisScreenHeader :screen="screen" :saving="savingStatus" :statuses="statuses" @change-status="changeStatus">
              <template #actions>
                <VBtn
                  v-if="canEdit"
                  size="small"
                  :variant="editMode ? 'flat' : 'tonal'"
                  :color="editMode ? 'primary' : undefined"
                  :prepend-icon="editMode ? 'ri-check-line' : 'ri-edit-box-line'"
                  data-test="edit-toggle"
                  @click="toggleEdit"
                >
                  {{ editMode ? t('asis.edit.toggleOff') : t('asis.edit.toggle') }}
                </VBtn>
                <VBtn size="small" variant="tonal" prepend-icon="ri-attachment-2" data-test="screen-attach" @click="attachOpen = true">
                  {{ t('asis.viewer.attachments') }}<template v-if="attachCount !== null"> ({{ attachCount }})</template>
                </VBtn>
              </template>
            </AsisScreenHeader>
            <VTabs v-model="centerTab" density="compact" color="primary" class="border-b border-line">
              <VTab value="capture" data-test="tab-capture">{{ t('asis.viewer.tabs.capture') }}</VTab>
              <VTab value="components">{{ t('asis.viewer.tabs.components') }}</VTab>
              <VTab value="events">{{ t('asis.viewer.tabs.events') }}</VTab>
              <VTab value="crud">{{ t('asis.viewer.tabs.crud') }}</VTab>
            </VTabs>
          </div>
          <AsisCaptureStage
            v-if="centerTab === 'capture'"
            :screen="screen"
            :selected-element-id="elementId"
            :editable="editable"
            @select="selectElement"
            @create="openCreate"
            @move="moveElement"
            @upload-capture="captureUploadOpen = true"
          />
          <div v-if="centerTab === 'components'" class="d-flex px-4 pt-3">
            <span class="text-caption text-muted align-self-center">{{ t('asis.viewer.exportHint') }}</span>
            <VSpacer />
            <ExcelExportButton
              dataset-id="as-elements"
              :file-name="t('asis.viewer.exportName', { system: currentSystem?.code ?? '' })"
              :params="{ systemId }"
              :label="t('asis.viewer.exportElements')"
            />
          </div>
          <AsisElementTable
            v-if="centerTab === 'components' || centerTab === 'events'"
            :elements="screen.elements"
            :selected-element-id="elementId"
            :mode="centerTab"
            @select="selectElement"
          />
          <AsisCrudTable
            v-else-if="centerTab === 'crud'"
            :matrix="crud"
            :loading="crudLoading"
            :selected-element-id="elementId"
            @select="selectElement"
          />
        </template>
      </VCol>

      <VCol cols="12" lg="4" class="viewer-col col-detail bg-surface">
        <AsisElementPanel
          v-if="selectedElement || !screen"
          :element="selectedElement"
          :system-id="systemId"
          :editable="editable"
          :screen-id="screen?.screenId ?? null"
          :screen-name="screen ? `${screen.name} (${screen.screenCode})` : null"
          :has-capture="!!screen?.capture"
          @open-element="openScreen"
          @edit-element="openEditElement"
          @hide-element="(el: ElementSummary) => (hideTarget = el)"
          @restore-element="restoreElement"
        />
        <div v-else class="screen-info">
          <div class="pa-4 border-b border-line">
            <h3 class="text-subtitle-1 font-weight-bold mb-1">{{ t('asis.screenInfo.title') }}</h3>
            <div class="text-caption text-muted">{{ t('asis.screenInfo.hint') }}</div>
          </div>
          <VTabs v-model="infoTab" density="compact" color="primary" class="border-b border-line">
            <VTab value="notes" data-test="info-tab-notes">{{ t('asis.notes.screenTitle') }}</VTab>
            <VTab value="history" data-test="info-tab-history">{{ t('asis.history.title') }}</VTab>
          </VTabs>
          <div class="pa-4">
            <AsisNotesPanel v-if="infoTab === 'notes'" target-type="SCREEN" :target-id="screen.screenId" />
            <AsisAnalysisHistory v-else :screen-id="screen.screenId" :refresh-key="historyKey" />
          </div>
        </div>
      </VCol>
    </VRow>

    <AsisStatusDialog
      v-model="statusDialog.open"
      :status="statusDialog.status"
      :analysis="screen?.analysis ?? null"
      :saving="savingStatus"
      :error="statusDialog.error"
      @save="saveStatus"
    />
    <AsisElementEditDialog
      v-model="elDialog.open"
      :element="elDialog.element"
      :initial-box="elDialog.box"
      :existing-codes="existingCodes"
      :saving="elDialog.saving"
      :error="elDialog.error"
      @save="saveElement"
    />
    <AsisCaptureUploadDialog v-if="screen" v-model="captureUploadOpen" :screen-id="screen.screenId" @uploaded="onCaptureUploaded" />
    <ConfirmDialog
      :model-value="!!hideTarget"
      :title="hideTarget?.manual ? t('asis.edit.deleteConfirmTitle') : t('asis.edit.hideConfirmTitle')"
      :message="hideTarget?.manual ? t('asis.edit.deleteConfirm', { label: hideTarget?.label ?? '' }) : t('asis.edit.hideConfirm', { label: hideTarget?.label ?? '' })"
      :confirm-text="hideTarget?.manual ? t('asis.edit.delete') : t('asis.edit.hide')"
      :loading="hiding"
      @update:model-value="(v: boolean) => { if (!v) hideTarget = null }"
      @confirm="confirmHide"
    />

    <VDialog v-model="attachOpen" max-width="640" scrollable>
      <VCard v-if="screen">
        <VCardTitle class="d-flex align-center">
          {{ t('asis.viewer.attachmentsTitle', { name: screen.name }) }}
          <VSpacer />
          <VBtn icon="ri-close-line" variant="text" size="small" :aria-label="t('common.close')" @click="attachOpen = false" />
        </VCardTitle>
        <VDivider />
        <VCardText>
          <AttachmentPanel target-type="AS_SCREEN" :target-id="screen.screenId" @changed="(n: number) => (attachCount = n)" />
        </VCardText>
      </VCard>
    </VDialog>
  </VContainer>
</template>

<style scoped>
.col-tree {
  display: flex;
  flex-direction: column;
  border-right: 1px solid rgb(var(--v-theme-line));
}

.col-detail {
  border-left: 1px solid rgb(var(--v-theme-line));
}

/* 긴 코드·경로가 열 폭을 밀어내지 않도록 (flex 항목의 min-width:auto 무력화) */
.viewer-col {
  min-width: 0;
}

/* 데스크톱(lg+)에서는 세 영역이 각자 스크롤된다 */
@media (min-width: 1280px) {
  .viewer-col {
    height: calc(100vh - var(--v-layout-top, 48px));
    overflow-y: auto;
  }

  .tree-fill {
    flex: 1;
    min-height: 0;
  }
}

@media (max-width: 1279px) {
  .col-tree {
    max-height: 420px;
    border-right: 0;
    border-bottom: 1px solid rgb(var(--v-theme-line));
  }

  .tree-fill {
    flex: 1;
    min-height: 0;
  }

  .col-detail {
    border-left: 0;
    border-top: 1px solid rgb(var(--v-theme-line));
  }
}
</style>
