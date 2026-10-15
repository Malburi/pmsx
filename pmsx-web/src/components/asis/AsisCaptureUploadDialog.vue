<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { uploadCaptureApi } from '@/api/asisApi'
import type { ApiError, CaptureInfo } from '@/types/api'

// 수동 캡처 업로드 (FR-AS-026) — 테스트계 접속이 안 되는 화면용
const props = defineProps<{ modelValue: boolean; screenId: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; uploaded: [capture: CaptureInfo] }>()

const { t } = useI18n()
const ACCEPT = ['image/png', 'image/jpeg']
const MAX_BYTES = 20 * 1024 * 1024
const file = ref<File | File[] | null>(null)
const uploading = ref(false)
const error = ref<string | null>(null)

// ─────────── 헬퍼 ───────────
const picked = (): File | null => (Array.isArray(file.value) ? file.value[0] ?? null : file.value)

const imageRule = (v: File | File[] | null) => {
  const f = Array.isArray(v) ? v[0] : v
  if (!f) return true
  if (!ACCEPT.includes(f.type)) return t('asis.edit.imageOnly')
  return f.size <= MAX_BYTES || t('common.attach.tooLarge', { max: '20MB' })
}

// 이미지 실제 크기를 읽어 서버에 함께 보낸다 (좌표 기준)
const readSize = (f: File) => new Promise<{ width: number; height: number } | undefined>((resolve) => {
  const url = URL.createObjectURL(f)
  const img = new Image()
  img.onload = () => { resolve({ width: img.naturalWidth, height: img.naturalHeight }); URL.revokeObjectURL(url) }
  img.onerror = () => { resolve(undefined); URL.revokeObjectURL(url) }
  img.src = url
})

const upload = async () => {
  const f = picked()
  if (!f || imageRule(f) !== true) return
  uploading.value = true
  error.value = null
  try {
    const capture = await uploadCaptureApi(props.screenId, f, await readSize(f))
    emit('uploaded', capture)
    emit('update:modelValue', false)
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.saveFailed')
  } finally {
    uploading.value = false
  }
}

// ─────────── watch ───────────
watch(() => props.modelValue, (open) => {
  if (open) { file.value = null; error.value = null }
})
</script>

<template>
  <VDialog :model-value="modelValue" max-width="480" @update:model-value="(v: boolean) => emit('update:modelValue', v)">
    <VCard :title="t('asis.edit.uploadCaptureTitle')">
      <VCardText>
        <p class="text-body-2 text-muted mb-3">{{ t('asis.edit.uploadCaptureHint') }}</p>
        <VFileInput
          v-model="file"
          accept="image/png,image/jpeg"
          :label="t('asis.edit.captureFile')"
          :rules="[imageRule]"
          prepend-icon=""
          prepend-inner-icon="ri-image-add-line"
          show-size
          data-test="capture-file"
        />
        <VAlert v-if="error" type="error" variant="tonal" density="compact" role="alert">{{ error }}</VAlert>
      </VCardText>
      <VCardActions>
        <VSpacer />
        <VBtn variant="text" @click="emit('update:modelValue', false)">{{ t('common.cancel') }}</VBtn>
        <VBtn color="primary" :loading="uploading" :disabled="!picked()" data-test="capture-upload" @click="upload">
          {{ t('asis.edit.uploadCapture') }}
        </VBtn>
      </VCardActions>
    </VCard>
  </VDialog>
</template>
