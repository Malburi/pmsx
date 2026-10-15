<route lang="yaml">
meta:
  layout: blank
</route>

<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { HOME_PATH } from '@/router/guards'

const { t } = useI18n()
const route = useRoute()

const message = computed(() =>
  route.query.code === 'FORBIDDEN' ? t('common.error.forbidden') : t('common.error.notFound'),
)
</script>

<template>
  <VContainer class="fill-height" fluid>
    <VRow justify="center">
      <VCol cols="12" md="6" class="text-center">
        <VIcon icon="ri-error-warning-line" size="56" color="warning" class="mb-4" />
        <div class="text-h6 mb-6">{{ message }}</div>
        <VBtn color="primary" :to="HOME_PATH">{{ t('error.page.goHome') }}</VBtn>
      </VCol>
    </VRow>
  </VContainer>
</template>
