<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { createTraceLinkApi, deleteTraceLinkApi, getTraceLinksApi } from '@/api/traceApi'
import { LINKABLE_TYPES, useTraceTargets, type TraceTarget } from '@/composables/useTraceTargets'
import type { ApiError } from '@/types/api'
import type { TraceLink, TraceType } from '@/types/r2'
import { showErrorToast } from '@/utils/toast'

// 대상(요구사항·요소 등)에 걸린 추적 링크 목록 + 추가·삭제 (FR-DL-004)
const props = withDefaults(defineProps<{ type: TraceType; id: string; readonly?: boolean }>(), { readonly: false })
const emit = defineEmits<{ changed: [] }>()

const { t } = useI18n()
const { load: loadTargets } = useTraceTargets()
const links = ref<TraceLink[]>([])
const loading = ref(false)
const add = reactive({ open: false, toType: 'SCREEN' as TraceType, toId: null as string | null, items: [] as TraceTarget[], loading: false, saving: false, error: null as string | null })

// ─────────── 헬퍼 ───────────
const load = async () => {
  loading.value = true
  try {
    links.value = await getTraceLinksApi(props.type, props.id)
  } catch {
    links.value = []
  } finally {
    loading.value = false
  }
}

const other = (l: TraceLink) => (l.fromType === props.type && l.fromId === props.id
  ? { type: l.toType, id: l.toId, label: l.toLabel }
  : { type: l.fromType, id: l.fromId, label: l.fromLabel })

const loadItems = async () => {
  add.loading = true
  add.toId = null
  try {
    add.items = (await loadTargets(add.toType)).filter((x) => !(add.toType === props.type && x.id === props.id))
  } catch {
    add.items = []
  } finally {
    add.loading = false
  }
}

const openAdd = () => {
  Object.assign(add, { open: true, error: null, saving: false })
  void loadItems()
}

const save = async () => {
  if (!add.toId) return
  add.saving = true
  add.error = null
  try {
    await createTraceLinkApi({ fromType: props.type, fromId: props.id, toType: add.toType, toId: add.toId })
    add.open = false
    await load()
    emit('changed')
  } catch (e) {
    const err = e as ApiError
    add.error = err.code === 'CONFLICT' ? t('dl.trace.duplicate') : (err.message ?? t('common.saveFailed'))
  } finally {
    add.saving = false
  }
}

const remove = async (l: TraceLink) => {
  try {
    await deleteTraceLinkApi(l.linkId)
    await load()
    emit('changed')
  } catch {
    showErrorToast('common.deleteFailed')
  }
}

// ─────────── computed ───────────
const typeItems = computed(() => LINKABLE_TYPES.map((v) => ({ title: t(`dl.traceType.${v}`), value: v })))
const targetItems = computed(() => add.items.map((x) => ({ title: x.label, value: x.id })))

// ─────────── watch ───────────
watch(() => [props.type, props.id], () => { void load() }, { immediate: true })
watch(() => add.toType, () => { if (add.open) void loadItems() })
</script>

<template>
  <div>
    <div class="d-flex align-center mb-2">
      <span class="text-subtitle-2">{{ t('dl.trace.links') }}</span>
      <VChip size="x-small" variant="tonal" class="ml-2">{{ links.length }}</VChip>
      <VSpacer />
      <VBtn v-if="!readonly" size="x-small" variant="tonal" prepend-icon="ri-link" data-test="trace-add" @click="openAdd">{{ t('dl.trace.add') }}</VBtn>
    </div>
    <VProgressLinear v-if="loading" indeterminate color="primary" />
    <VList density="compact" class="py-0">
      <VListItem v-for="l in links" :key="l.linkId" class="px-0">
        <template #prepend><VChip size="x-small" label variant="tonal" class="mr-2">{{ t(`dl.traceType.${other(l).type}`) }}</VChip></template>
        <VListItemTitle class="text-body-2">{{ other(l).label }}</VListItemTitle>
        <template v-if="!readonly" #append>
          <VBtn icon variant="text" size="x-small" :aria-label="t('dl.trace.remove')" @click="remove(l)"><VIcon icon="ri-link-unlink" /></VBtn>
        </template>
      </VListItem>
      <div v-if="!links.length && !loading" class="text-body-2 text-muted py-2">{{ t('dl.trace.none') }}</div>
    </VList>

    <VDialog v-model="add.open" max-width="480">
      <VCard :title="t('dl.trace.add')">
        <VCardText>
          <VSelect v-model="add.toType" :items="typeItems" :label="t('dl.trace.type')" class="mb-3" />
          <VAutocomplete v-model="add.toId" :items="targetItems" :loading="add.loading" :label="t('dl.trace.target')" :no-data-text="t('common.noData')" data-test="trace-target" />
          <VAlert v-if="add.error" type="error" variant="tonal" density="compact" class="mt-2">{{ add.error }}</VAlert>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="add.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :disabled="!add.toId" :loading="add.saving" data-test="trace-save" @click="save">{{ t('common.add') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>
  </div>
</template>
