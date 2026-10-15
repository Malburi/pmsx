<route lang="yaml">
meta:
  roles: [SYS_ADMIN, PM]
</route>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import CodeGroupEditor from '@/components/admin/CodeGroupEditor.vue'
import TermManager from '@/components/admin/TermManager.vue'
import { useAuthStore } from '@/stores/auth'

const { t } = useI18n()
const auth = useAuthStore()
const tab = ref<'codes' | 'terms'>('codes')

// ─────────── computed ───────────
// 변경은 SYS_ADMIN 만 (docs/06 공통코드·템플릿 행), PM 은 조회만
const readonly = computed(() => !auth.hasRole('SYS_ADMIN'))
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <VRow>
      <VCol cols="12" md="12">
        <h1 class="pmsx-page-title">{{ t('nav.admin.code') }}</h1>
        <div class="text-body-2 text-muted">{{ t('admin.codes.subtitle') }}</div>
        <VAlert v-if="readonly" type="info" variant="tonal" density="compact" class="mt-3">{{ t('admin.codes.readonlyHint') }}</VAlert>
      </VCol>
      <VCol cols="12" md="12">
        <VTabs v-model="tab" color="primary" density="compact" class="border-b border-line">
          <VTab value="codes" data-test="tab-codes">{{ t('admin.codes.tab') }}</VTab>
          <VTab value="terms" data-test="tab-terms">{{ t('admin.terms.tab') }}</VTab>
        </VTabs>
      </VCol>
      <VCol cols="12" md="12">
        <CodeGroupEditor v-if="tab === 'codes'" :readonly="readonly" />
        <TermManager v-else :readonly="readonly" />
      </VCol>
    </VRow>
  </VContainer>
</template>
