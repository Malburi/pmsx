<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import MarkdownView from '@/components/common/MarkdownView.vue'

const props = withDefaults(defineProps<{
  modelValue: string
  placeholder?: string
  maxLength?: number
  rows?: number
  dataTest?: string
}>(), { placeholder: undefined, maxLength: 20_000, rows: 5, dataTest: undefined })
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const { t } = useI18n()
const mode = ref<'write' | 'preview'>('write')

// ─────────── 헬퍼 ───────────
const lengthRule = (v: string) => (v ?? '').length <= props.maxLength || t('asis.notes.tooLong')
</script>

<template>
  <div class="md-editor">
    <VBtnToggle v-model="mode" density="compact" variant="text" color="primary" mandatory class="mb-1">
      <VBtn value="write" size="small" prepend-icon="ri-edit-line">{{ t('common.markdown.write') }}</VBtn>
      <VBtn value="preview" size="small" prepend-icon="ri-eye-line">{{ t('common.markdown.preview') }}</VBtn>
    </VBtnToggle>
    <VTextarea
      v-if="mode === 'write'"
      :model-value="modelValue"
      :placeholder="placeholder"
      :rows="rows"
      auto-grow
      :counter="maxLength"
      :rules="[lengthRule]"
      :hint="t('common.markdown.hint')"
      persistent-hint
      :data-test="dataTest"
      @update:model-value="(v: string) => emit('update:modelValue', v)"
    />
    <div v-else class="preview border rounded pa-3">
      <MarkdownView v-if="modelValue.trim()" :source="modelValue" />
      <span v-else class="text-caption text-muted">{{ t('common.markdown.empty') }}</span>
    </div>
  </div>
</template>

<style scoped>
.preview {
  min-height: 96px;
}
</style>
