<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { createEdgeApi, searchNodesApi } from '@/api/asisApi'
import type { ApiError, EdgeCreate, EdgeInfo, EdgeKind, NodeSummary, NodeType } from '@/types/api'
import type { EdgeFrom } from '@/types/asisUi'
import { CRUD_LETTERS } from '@/utils/asis'

// 연결 추가 (FR-AS-020) — 요소나 워크플로우 단계에서 다음 노드로 수동 연결

const props = defineProps<{ modelValue: boolean; systemId: string; from: EdgeFrom | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; added: [edge: EdgeInfo] }>()

const { t } = useI18n()
const KINDS: EdgeKind[] = ['event', 'calls', 'request', 'handles', 'executes', 'accesses', 'navigates', 'binds']
const NODE_TYPES: NodeType[] = ['FN', 'API', 'SQL', 'SP', 'TABLE', 'SCREEN']
// 출발 레이어별 기본 연결 종류 — 가장 흔한 다음 단계
const DEFAULT_KIND: Record<string, EdgeKind> = {
  ELEMENT: 'event', UI: 'event', FRONT: 'request', API: 'handles', SERVER: 'executes', SQL: 'accesses', DB: 'accesses', POST: 'calls',
}
// 연결 종류별로 대상이 될 법한 노드 종류
const TARGET_TYPE: Record<EdgeKind, NodeType> = {
  event: 'FN', calls: 'FN', request: 'API', handles: 'FN', executes: 'SQL', accesses: 'TABLE', navigates: 'SCREEN', binds: 'TABLE',
}

const form = reactive({
  kind: 'event' as EdgeKind,
  mode: 'existing' as 'existing' | 'new',
  targetId: null as string | null,
  newType: 'FN' as NodeType,
  newName: '',
  newFile: '',
  event: 'click',
  condition: '',
  crud: [] as string[],
})
const searchType = ref<NodeType | null>('FN')
const results = ref<NodeSummary[]>([])
const searching = ref(false)
const saving = ref(false)
const error = ref<string | null>(null)
let timer: ReturnType<typeof setTimeout> | null = null
let seq = 0

// ─────────── 헬퍼 ───────────
const search = (text: string | null) => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(async () => {
    const mine = ++seq
    searching.value = true
    try {
      const page = await searchNodesApi(props.systemId, { q: text?.trim() || undefined, type: searchType.value ?? undefined, size: 30 })
      if (mine === seq) results.value = page.content
    } catch {
      if (mine === seq) results.value = []
    } finally {
      if (mine === seq) searching.value = false
    }
  }, 300)
}

const submit = async () => {
  const from = props.from
  if (!from) return
  if (form.mode === 'existing' && !form.targetId) { error.value = t('validation.required'); return }
  if (form.mode === 'new' && !form.newName.trim()) { error.value = t('validation.required'); return }
  const payload: EdgeCreate = {
    from: from.elementId ? { elementId: from.elementId } : { nodeId: from.nodeId },
    to: form.mode === 'existing'
      ? { nodeId: form.targetId as string }
      : { newNode: { nodeType: form.newType, name: form.newName.trim(), file: form.newFile.trim() || undefined } },
    kind: form.kind,
  }
  if (form.kind === 'event') payload.event = form.event.trim() || 'click'
  if (form.condition.trim()) payload.condition = form.condition.trim()
  if (form.kind === 'accesses' && form.crud.length) payload.crud = CRUD_LETTERS.filter((c) => form.crud.includes(c)).join('')
  saving.value = true
  error.value = null
  try {
    emit('added', await createEdgeApi(payload))
    emit('update:modelValue', false)
  } catch (e) {
    const err = e as ApiError
    error.value = err.code === 'FORBIDDEN' ? t('common.forbiddenAction') : (err.message ?? t('common.saveFailed'))
  } finally {
    saving.value = false
  }
}

// ─────────── computed ───────────
const kindItems = computed(() => KINDS.map((k) => ({ title: t(`asis.edge.kinds.${k}`), value: k })))
const typeItems = computed(() => NODE_TYPES.map((k) => ({ title: t(`asis.edge.nodeTypes.${k}`), value: k })))
const resultItems = computed(() => results.value.map((n) => ({
  title: n.name,
  subtitle: `${t(`asis.edge.nodeTypes.${n.nodeType}`)}${n.file ? ` · ${n.file}` : ''}`,
  value: n.nodeId,
})))

// ─────────── watch ───────────
watch(() => props.modelValue, (open) => {
  if (!open) return
  const lane = props.from?.elementId ? 'ELEMENT' : (props.from?.lane ?? 'FRONT')
  const kind = DEFAULT_KIND[lane] ?? 'calls'
  Object.assign(form, { kind, mode: 'existing', targetId: null, newType: TARGET_TYPE[kind], newName: '', newFile: '', event: 'click', condition: '', crud: [] })
  searchType.value = TARGET_TYPE[kind]
  error.value = null
  search('')
})

watch(() => form.kind, (k) => {
  searchType.value = TARGET_TYPE[k]
  form.newType = TARGET_TYPE[k]
})

watch(searchType, () => { if (props.modelValue) search('') })

// ─────────── 라이프사이클 ───────────
onBeforeUnmount(() => { if (timer) clearTimeout(timer) })
</script>

<template>
  <VDialog :model-value="modelValue" max-width="560" scrollable @update:model-value="(v: boolean) => emit('update:modelValue', v)">
    <VCard :title="t('asis.edge.addTitle')">
      <VCardText>
        <div class="text-caption text-muted mb-1">{{ t('asis.edge.from') }}</div>
        <div class="font-mono text-body-2 mb-4">{{ from?.label }}</div>
        <VRow dense>
          <VCol cols="12" sm="6">
            <VSelect v-model="form.kind" :items="kindItems" :label="t('asis.edge.kind')" data-test="edge-kind" />
          </VCol>
          <VCol v-if="form.kind === 'event'" cols="12" sm="6">
            <VTextField v-model="form.event" :label="t('asis.edge.event')" maxlength="50" />
          </VCol>
          <VCol cols="12">
            <VBtnToggle v-model="form.mode" mandatory density="compact" variant="outlined" divided color="primary">
              <VBtn value="existing" size="small">{{ t('asis.edge.targetExisting') }}</VBtn>
              <VBtn value="new" size="small">{{ t('asis.edge.targetNew') }}</VBtn>
            </VBtnToggle>
          </VCol>
          <template v-if="form.mode === 'existing'">
            <VCol cols="12" sm="4">
              <VSelect v-model="searchType" :items="typeItems" :label="t('asis.edge.nodeType')" clearable />
            </VCol>
            <VCol cols="12" sm="8">
              <VAutocomplete
                v-model="form.targetId"
                :items="resultItems"
                :label="t('asis.edge.nodeSearch')"
                :loading="searching"
                no-filter
                item-props
                :no-data-text="t('common.userLookup.noResult')"
                data-test="edge-target"
                @update:search="search"
              />
            </VCol>
          </template>
          <template v-else>
            <VCol cols="12" sm="4">
              <VSelect v-model="form.newType" :items="typeItems" :label="t('asis.edge.nodeType')" />
            </VCol>
            <VCol cols="12" sm="8">
              <VTextField v-model="form.newName" :label="t('asis.edge.nodeName')" :hint="t('asis.edge.nodeNameHint')" persistent-hint maxlength="300" data-test="edge-new-name" />
            </VCol>
            <VCol cols="12">
              <VTextField v-model="form.newFile" :label="t('asis.edge.nodeFile')" maxlength="500" />
            </VCol>
          </template>
          <VCol cols="12">
            <VTextField v-model="form.condition" :label="t('asis.edge.condition')" :hint="t('asis.edge.conditionHint')" maxlength="300" />
          </VCol>
          <VCol v-if="form.kind === 'accesses'" cols="12">
            <div class="text-caption text-muted">{{ t('asis.edge.crud') }}</div>
            <VChipGroup v-model="form.crud" multiple selected-class="text-primary" column>
              <VChip v-for="c in CRUD_LETTERS" :key="c" :value="c" filter variant="outlined" size="small">{{ t(`asis.crud.${c}`) }}</VChip>
            </VChipGroup>
          </VCol>
        </VRow>
        <VAlert v-if="error" type="error" variant="tonal" density="compact" class="mt-2" role="alert">{{ error }}</VAlert>
      </VCardText>
      <VCardActions>
        <VSpacer />
        <VBtn variant="text" @click="emit('update:modelValue', false)">{{ t('common.cancel') }}</VBtn>
        <VBtn color="primary" :loading="saving" data-test="edge-save" @click="submit">{{ t('common.add') }}</VBtn>
      </VCardActions>
    </VCard>
  </VDialog>
</template>
