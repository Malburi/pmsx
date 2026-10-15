<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import UserLookupField from '@/components/common/UserLookupField.vue'
import type { AnalysisPatch, AnalysisStatus, ScreenAnalysis } from '@/types/api'

// 분석 상태 변경 시 코멘트·검토자 지정 (FR-AS-022)
const props = defineProps<{
  modelValue: boolean
  status: AnalysisStatus | null
  analysis: ScreenAnalysis | null
  saving: boolean
  error: string | null
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; save: [patch: AnalysisPatch] }>()

const { t } = useI18n()
const reviewerId = ref<string | null>(null)
const comment = ref('')

// ─────────── 헬퍼 ───────────
const submit = () => {
  if (!props.status) return
  const patch: AnalysisPatch = { status: props.status, comment: comment.value.trim() || undefined }
  if (props.status === 'REVIEW') patch.reviewerId = reviewerId.value
  emit('save', patch)
}

// ─────────── watch ───────────
watch(() => props.modelValue, (open) => {
  if (!open) return
  reviewerId.value = props.analysis?.reviewer?.userId ?? null
  comment.value = ''
})
</script>

<template>
  <VDialog :model-value="modelValue" max-width="480" @update:model-value="(v: boolean) => emit('update:modelValue', v)">
    <VCard v-if="status" :title="t('asis.statusDialog.title', { status: t(`asis.status.${status}`) })">
      <VCardText>
        <UserLookupField
          v-if="status === 'REVIEW'"
          v-model="reviewerId"
          :label="t('asis.statusDialog.reviewer')"
          :hint="t('asis.statusDialog.reviewerHint')"
          :initial="analysis?.reviewer ?? null"
          class="mb-3"
          data-test="status-reviewer"
        />
        <VTextarea
          v-model="comment"
          :label="t('asis.statusDialog.comment')"
          :placeholder="t('asis.statusDialog.commentPlaceholder')"
          rows="3"
          auto-grow
          counter="1000"
          maxlength="1000"
          data-test="status-comment"
        />
        <VAlert v-if="error" type="error" variant="tonal" density="compact" class="mt-2" role="alert">{{ error }}</VAlert>
      </VCardText>
      <VCardActions>
        <VSpacer />
        <VBtn variant="text" @click="emit('update:modelValue', false)">{{ t('common.cancel') }}</VBtn>
        <VBtn color="primary" :loading="saving" data-test="status-save" @click="submit">{{ t('common.save') }}</VBtn>
      </VCardActions>
    </VCard>
  </VDialog>
</template>
