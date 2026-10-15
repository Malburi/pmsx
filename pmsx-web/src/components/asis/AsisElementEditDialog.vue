<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { Box, ElementPayload, ElementSummary, ElementType } from '@/types/api'
import { nextElementCode, roundBox } from '@/utils/hotspot'

// 핫스팟 요소 추가·편집 (FR-AS-019). element 가 없으면 새 요소.
const props = defineProps<{
  modelValue: boolean
  element: ElementSummary | null
  initialBox: Box | null
  existingCodes: string[]
  saving: boolean
  error: string | null
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; save: [payload: ElementPayload] }>()

const { t } = useI18n()
const TYPES: ElementType[] = ['button', 'input', 'combo', 'check', 'radio', 'date', 'grid', 'tab', 'tree', 'popup', 'link', 'file', 'other']
const form = reactive({ elementCode: '', label: '', type: 'button' as ElementType, binding: '', x: 0, y: 0, w: 0, h: 0 })
const formRef = ref<{ validate: () => Promise<{ valid: boolean }> } | null>(null)

// ─────────── 헬퍼 ───────────
const required = (v: string) => !!v?.trim() || t('validation.required')
const codeRule = (v: string) => /^[A-Za-z0-9_-]{1,100}$/.test(v) || t('asis.edit.elementCodeRule')
const uniqueRule = (v: string) => !!props.element || !props.existingCodes.includes(v) || t('asis.edit.conflict')
const sizeRule = (v: number) => (Number.isFinite(Number(v)) && Number(v) >= 0) || t('validation.required')

const submit = async () => {
  const res = await formRef.value?.validate()
  if (res && !res.valid) return
  emit('save', {
    elementCode: form.elementCode.trim(),
    label: form.label.trim(),
    type: form.type,
    binding: form.binding.trim() || null,
    box: roundBox({ x: Number(form.x), y: Number(form.y), w: Number(form.w), h: Number(form.h) }),
  })
}

// ─────────── computed ───────────
const typeItems = computed(() => TYPES.map((v) => ({ title: t(`asis.type.${v}`), value: v })))

// ─────────── watch ───────────
watch(() => props.modelValue, (open) => {
  if (!open) return
  const e = props.element
  const box = e?.box ?? props.initialBox ?? { x: 0, y: 0, w: 60, h: 24 }
  Object.assign(form, {
    elementCode: e?.elementCode ?? nextElementCode(props.existingCodes),
    label: e?.label ?? '',
    type: e?.type ?? 'button',
    binding: e?.binding ?? '',
    ...roundBox(box),
  })
})
</script>

<template>
  <VDialog :model-value="modelValue" max-width="520" @update:model-value="(v: boolean) => emit('update:modelValue', v)">
    <VCard :title="element ? t('asis.edit.editElement') : t('asis.edit.newElement')">
      <VForm ref="formRef" @submit.prevent="submit">
        <VCardText>
          <VRow dense>
            <VCol cols="12" sm="6">
              <VTextField
                v-model="form.elementCode"
                :label="t('asis.edit.elementCode')"
                :hint="t('asis.edit.elementCodeHint')"
                :disabled="!!element"
                :rules="[required, codeRule, uniqueRule]"
                class="font-mono"
                data-test="el-code"
              />
            </VCol>
            <VCol cols="12" sm="6">
              <VTextField v-model="form.label" :label="t('asis.edit.label')" :rules="[required]" maxlength="200" data-test="el-label" />
            </VCol>
            <VCol cols="12" sm="6">
              <VSelect v-model="form.type" :items="typeItems" :label="t('asis.edit.type')" data-test="el-type" />
            </VCol>
            <VCol cols="12" sm="6">
              <VTextField v-model="form.binding" :label="t('asis.edit.binding')" maxlength="500" />
            </VCol>
            <VCol cols="12">
              <div class="text-caption text-muted mb-1">{{ t('asis.edit.box') }}</div>
              <div class="box-grid">
                <VTextField v-model.number="form.x" type="number" label="x" :rules="[sizeRule]" density="compact" />
                <VTextField v-model.number="form.y" type="number" label="y" :rules="[sizeRule]" density="compact" />
                <VTextField v-model.number="form.w" type="number" label="w" :rules="[sizeRule]" density="compact" />
                <VTextField v-model.number="form.h" type="number" label="h" :rules="[sizeRule]" density="compact" />
              </div>
            </VCol>
          </VRow>
          <VAlert v-if="error" type="error" variant="tonal" density="compact" class="mt-2" role="alert">{{ error }}</VAlert>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="emit('update:modelValue', false)">{{ t('common.cancel') }}</VBtn>
          <VBtn type="submit" color="primary" :loading="saving" data-test="el-save">{{ t('common.save') }}</VBtn>
        </VCardActions>
      </VForm>
    </VCard>
  </VDialog>
</template>

<style scoped>
.box-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}
</style>
