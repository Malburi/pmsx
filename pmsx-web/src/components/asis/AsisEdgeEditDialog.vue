<script setup lang="ts">
import { reactive, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { EdgePatch, WorkflowStep } from '@/types/api'
import { CRUD_LETTERS } from '@/utils/asis'

// 연결 조건·CRUD 편집 (FR-AS-020)
const props = defineProps<{ modelValue: boolean; step: WorkflowStep | null; saving: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; save: [patch: EdgePatch] }>()

const { t } = useI18n()
const form = reactive({ condition: '', crud: [] as string[] })

// ─────────── 헬퍼 ───────────
const submit = () => {
  const patch: EdgePatch = { condition: form.condition.trim() }
  if (props.step?.lane === 'DB') patch.crud = CRUD_LETTERS.filter((c) => form.crud.includes(c)).join('')
  emit('save', patch)
}

// ─────────── watch ───────────
watch(() => props.modelValue, (open) => {
  if (!open) return
  form.condition = props.step?.edge?.condition ?? ''
  form.crud = CRUD_LETTERS.filter((c) => (props.step?.crud ?? '').includes(c))
})
</script>

<template>
  <VDialog :model-value="modelValue" max-width="460" @update:model-value="(v: boolean) => emit('update:modelValue', v)">
    <VCard :title="t('asis.edge.editTitle')">
      <VCardText>
        <div class="font-mono text-body-2 mb-3">{{ step?.title }}</div>
        <VTextField v-model="form.condition" :label="t('asis.edge.condition')" :hint="t('asis.edge.conditionHint')" persistent-hint maxlength="300" data-test="edge-condition" />
        <template v-if="step?.lane === 'DB'">
          <div class="text-caption text-muted mt-3">{{ t('asis.edge.crud') }}</div>
          <VChipGroup v-model="form.crud" multiple selected-class="text-primary" column>
            <VChip v-for="c in CRUD_LETTERS" :key="c" :value="c" filter variant="outlined" size="small">{{ t(`asis.crud.${c}`) }}</VChip>
          </VChipGroup>
        </template>
      </VCardText>
      <VCardActions>
        <VSpacer />
        <VBtn variant="text" @click="emit('update:modelValue', false)">{{ t('common.cancel') }}</VBtn>
        <VBtn color="primary" :loading="saving" data-test="edge-edit-save" @click="submit">{{ t('common.save') }}</VBtn>
      </VCardActions>
    </VCard>
  </VDialog>
</template>
