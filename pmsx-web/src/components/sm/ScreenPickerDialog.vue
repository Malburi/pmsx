<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { getMenuTreeApi, getScreenApi } from '@/api/asisApi'
import AsisMenuTree from '@/components/asis/AsisMenuTree.vue'
import type { ElementSummary, MenuNode, ScreenDetail, SystemSummary } from '@/types/api'
import type { ScreenPick } from '@/types/r3'

// SR 대상 화면 선택 (FR-SM-003) — 시스템 → 메뉴 트리/검색 → 화면 → (선택) 요소
const props = defineProps<{
  modelValue: boolean
  systems: SystemSummary[]
  initial?: { systemId: string | null; screenId: string | null; elementId: string | null } | null
}>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean]; pick: [value: ScreenPick] }>()

const { t } = useI18n()
const state = reactive({ systemId: null as string | null, screenId: null as string | null, elementId: null as string | null })
const tree = ref<MenuNode[]>([])
const treeLoading = ref(false)
const screen = ref<ScreenDetail | null>(null)
const screenLoading = ref(false)

// ─────────── 헬퍼 ───────────
const loadTree = async (systemId: string) => {
  treeLoading.value = true
  try {
    tree.value = await getMenuTreeApi(systemId)
  } catch {
    tree.value = []
  } finally {
    treeLoading.value = false
  }
}

const loadScreen = async (screenId: string) => {
  screenLoading.value = true
  try {
    screen.value = await getScreenApi(screenId)
  } catch {
    screen.value = null
  } finally {
    screenLoading.value = false
  }
}

const onSelect = (screenId: string, elementId: string | null) => {
  state.screenId = screenId
  state.elementId = elementId
  void loadScreen(screenId)
}

const confirm = () => {
  if (!state.systemId || !state.screenId || !screen.value) return
  const el = screen.value.elements.find((e) => e.elementId === state.elementId) ?? null
  emit('pick', {
    systemId: state.systemId, screenId: state.screenId, elementId: el?.elementId ?? null,
    screenName: screen.value.name, elementLabel: el?.label ?? null,
  })
  emit('update:modelValue', false)
}

const elementTitle = (e: ElementSummary) => `${e.seqNo}. ${e.label} (${e.elementCode})`

// ─────────── computed ───────────
const systemItems = computed(() => props.systems.map((s) => ({ title: `${s.name} (${s.code})`, value: s.systemId })))
const selectedSystem = computed(() => props.systems.find((s) => s.systemId === state.systemId) ?? null)
const elementItems = computed(() => (screen.value?.elements ?? []).filter((e) => !e.hidden).map((e) => ({ title: elementTitle(e), value: e.elementId })))

// ─────────── watch ───────────
watch(() => props.modelValue, (open) => {
  if (!open) return
  const init = props.initial
  Object.assign(state, {
    systemId: init?.systemId ?? props.systems[0]?.systemId ?? null,
    screenId: init?.screenId ?? null,
    elementId: init?.elementId ?? null,
  })
  screen.value = null
  if (state.screenId) void loadScreen(state.screenId)
})
watch(() => state.systemId, (id, prev) => {
  if (!id) { tree.value = []; return }
  if (prev && prev !== id) Object.assign(state, { screenId: null, elementId: null })
  void loadTree(id)
})
</script>

<template>
  <VDialog :model-value="modelValue" max-width="860" scrollable @update:model-value="emit('update:modelValue', $event)">
    <VCard :title="t('sm.screenPicker.title')">
      <VCardText>
        <VRow dense>
          <VCol cols="12" md="5">
            <VSelect v-model="state.systemId" :items="systemItems" :label="t('sm.screenPicker.system')" :no-data-text="t('sm.screenPicker.noSystem')" class="mb-2" data-test="picker-system" />
            <div class="picker-tree border rounded-lg">
              <AsisMenuTree
                v-if="state.systemId"
                :system-id="state.systemId"
                :system="selectedSystem"
                :nodes="tree"
                :loading="treeLoading"
                :selected-screen-id="state.screenId"
                @select="onSelect"
              />
            </div>
          </VCol>
          <VCol cols="12" md="7">
            <VProgressLinear v-if="screenLoading" indeterminate color="primary" />
            <template v-else-if="screen">
              <div class="text-subtitle-1 font-weight-medium">{{ screen.name }} <span class="font-mono text-caption text-muted">{{ screen.screenCode }}</span></div>
              <div class="text-caption text-muted mb-3">{{ screen.menuPath.join(' › ') }}</div>
              <VAutocomplete
                v-model="state.elementId"
                :items="elementItems"
                :label="t('sm.screenPicker.element')"
                :hint="t('sm.screenPicker.elementHint')"
                persistent-hint
                clearable
                data-test="picker-element"
              />
            </template>
            <VAlert v-else type="info" variant="tonal" density="compact">{{ t('sm.screenPicker.pickScreen') }}</VAlert>
          </VCol>
        </VRow>
      </VCardText>
      <VCardActions>
        <VSpacer />
        <VBtn variant="text" @click="emit('update:modelValue', false)">{{ t('common.cancel') }}</VBtn>
        <VBtn color="primary" :disabled="!screen" data-test="picker-ok" @click="confirm">{{ t('common.apply') }}</VBtn>
      </VCardActions>
    </VCard>
  </VDialog>
</template>

<style scoped>
.picker-tree {
  height: 380px;
  overflow: hidden;
}
</style>
