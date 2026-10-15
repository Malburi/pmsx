<script setup lang="ts">
import { useI18n } from 'vue-i18n'

withDefaults(defineProps<{
  modelValue: boolean
  title: string
  message: string
  confirmText?: string
  color?: string
  loading?: boolean
}>(), { confirmText: undefined, color: 'error', loading: false })

const emit = defineEmits<{ 'update:modelValue': [value: boolean]; confirm: [] }>()

const { t } = useI18n()
</script>

<template>
  <VDialog :model-value="modelValue" max-width="420" @update:model-value="(v: boolean) => emit('update:modelValue', v)">
    <VCard :title="title">
      <VCardText class="text-body-2">{{ message }}</VCardText>
      <VCardActions>
        <VSpacer />
        <VBtn variant="text" data-test="confirm-cancel" @click="emit('update:modelValue', false)">{{ t('common.cancel') }}</VBtn>
        <VBtn :color="color" :loading="loading" data-test="confirm-ok" @click="emit('confirm')">
          {{ confirmText ?? t('common.confirm') }}
        </VBtn>
      </VCardActions>
    </VCard>
  </VDialog>
</template>
