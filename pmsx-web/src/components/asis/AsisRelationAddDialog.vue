<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { createRelationApi } from '@/api/tableApi'
import type { ApiError, ErdTable, Relation } from '@/types/api'

// 관계 수동 추가 (FR-AS-018) — FK 도 추정도 없는 관계를 사람이 등록
const props = defineProps<{ modelValue: boolean; tables: ErdTable[] }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; added: [relation: Relation] }>()

const { t } = useI18n()
const form = reactive({ childId: null as string | null, parentId: null as string | null, pairs: [['', '']] as [string, string][] })
const saving = ref(false)
const error = ref<string | null>(null)

// ─────────── 헬퍼 ───────────
const columnsOf = (id: string | null) => (props.tables.find((tb) => tb.tableId === id)?.columns ?? []).map((c) => c.name)

const addPair = () => { form.pairs.push(['', '']) }

const removePair = (i: number) => { if (form.pairs.length > 1) form.pairs.splice(i, 1) }

const submit = async () => {
  const pairs = form.pairs.filter(([c, p]) => c && p)
  if (!form.childId || !form.parentId || !pairs.length) { error.value = t('validation.required'); return }
  saving.value = true
  error.value = null
  try {
    emit('added', await createRelationApi({ childTableId: form.childId, parentTableId: form.parentId, columns: pairs }))
    emit('update:modelValue', false)
  } catch (e) {
    const err = e as ApiError
    error.value = err.code === 'FORBIDDEN' ? t('common.forbiddenAction') : (err.message ?? t('common.saveFailed'))
  } finally {
    saving.value = false
  }
}

// ─────────── computed ───────────
const tableItems = computed(() => props.tables.map((tb) => ({ title: tb.name, value: tb.tableId })))
const childCols = computed(() => columnsOf(form.childId))
const parentCols = computed(() => columnsOf(form.parentId))

// ─────────── watch ───────────
watch(() => props.modelValue, (open) => {
  if (!open) return
  Object.assign(form, { childId: null, parentId: null, pairs: [['', '']] })
  error.value = null
})

// 부모 PK 를 자식의 같은 이름 컬럼에 자동 대응 (가장 흔한 경우)
watch(() => [form.childId, form.parentId], () => {
  const parent = props.tables.find((tb) => tb.tableId === form.parentId)
  const pks = parent?.columns.filter((c) => c.pk).map((c) => c.name) ?? []
  const match = pks.filter((pk) => childCols.value.includes(pk))
  if (match.length) form.pairs = match.map((m) => [m, m] as [string, string])
})
</script>

<template>
  <VDialog :model-value="modelValue" max-width="560" scrollable @update:model-value="(v: boolean) => emit('update:modelValue', v)">
    <VCard :title="t('asis.relation.addTitle')">
      <VCardText>
        <VRow dense>
          <VCol cols="12" sm="6">
            <VAutocomplete v-model="form.childId" :items="tableItems" :label="t('asis.relation.child')" data-test="rel-child" />
          </VCol>
          <VCol cols="12" sm="6">
            <VAutocomplete v-model="form.parentId" :items="tableItems" :label="t('asis.relation.parent')" data-test="rel-parent" />
          </VCol>
        </VRow>
        <div class="text-caption text-muted mb-1">{{ t('asis.relation.columnPairs') }}</div>
        <div v-for="(pair, i) in form.pairs" :key="i" class="pair">
          <VSelect v-model="pair[0]" :items="childCols" :label="t('asis.relation.childColumn')" density="compact" hide-details />
          <VIcon icon="ri-arrow-right-line" size="16" />
          <VSelect v-model="pair[1]" :items="parentCols" :label="t('asis.relation.parentColumn')" density="compact" hide-details />
          <VBtn icon="ri-close-line" size="x-small" variant="text" :disabled="form.pairs.length === 1" :aria-label="t('common.delete')" @click="removePair(i)" />
        </div>
        <VBtn size="small" variant="text" prepend-icon="ri-add-line" @click="addPair">{{ t('asis.relation.addPair') }}</VBtn>
        <VAlert v-if="error" type="error" variant="tonal" density="compact" class="mt-2" role="alert">{{ error }}</VAlert>
      </VCardText>
      <VCardActions>
        <VSpacer />
        <VBtn variant="text" @click="emit('update:modelValue', false)">{{ t('common.cancel') }}</VBtn>
        <VBtn color="primary" :loading="saving" data-test="rel-save" @click="submit">{{ t('common.add') }}</VBtn>
      </VCardActions>
    </VCard>
  </VDialog>
</template>

<style scoped>
.pair {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
</style>
