<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { getNotificationSettingsApi, saveNotificationSettingsApi } from '@/api/dashboardApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import type { ApiError } from '@/types/api'
import type { NotificationSettings } from '@/types/r2'
import { NOTIFY_EVENTS } from '@/types/r2'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// SCR-CM-008 알림 설정 (FR-CM-007) — 인앱은 항상, 메일·웹훅은 사용자 선택
const { t } = useI18n()

const settings = ref<NotificationSettings | null>(null)
const savedJson = ref('')
const loading = ref(false)
const saving = ref(false)
const error = ref<string | null>(null)

const load = async () => {
  loading.value = true
  error.value = null
  try {
    settings.value = await getNotificationSettingsApi()
    savedJson.value = JSON.stringify(settings.value)
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}

const save = async () => {
  if (!settings.value) return
  saving.value = true
  try {
    settings.value = await saveNotificationSettingsApi(settings.value)
    savedJson.value = JSON.stringify(settings.value)
    showSuccessToast('common.saved')
  } catch {
    showErrorToast('common.saveFailed')
  } finally {
    saving.value = false
  }
}

const dirty = computed(() => !!settings.value && JSON.stringify(settings.value) !== savedJson.value)

onMounted(load)
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.admin.notify')" :subtitle="t('notify.subtitle')">
      <VBtn color="primary" size="small" :disabled="!dirty" :loading="saving" data-test="notify-save" @click="save">{{ t('common.save') }}</VBtn>
    </AppPageHeader>
    <VAlert v-if="error" type="error" variant="tonal" density="compact" class="mb-3">{{ error }}</VAlert>
    <VProgressLinear v-if="loading" indeterminate color="primary" class="mb-2" />
    <VRow v-if="settings">
      <VCol cols="12" md="5">
        <VCard border flat :title="t('notify.channels')">
          <VCardText>
            <VSwitch :model-value="true" :label="t('notify.inApp')" color="primary" disabled hide-details />
            <div class="text-caption text-muted mb-2">{{ t('notify.inAppHint') }}</div>
            <VSwitch v-model="settings.email" :label="t('notify.email')" color="primary" hide-details data-test="notify-email" />
            <div class="text-caption text-muted mb-2">{{ t('notify.emailHint') }}</div>
            <VSwitch v-model="settings.webhook" :label="t('notify.webhook')" color="primary" hide-details />
            <div class="text-caption text-muted">{{ t('notify.webhookHint') }}</div>
          </VCardText>
        </VCard>
      </VCol>
      <VCol cols="12" md="7">
        <VCard border flat :title="t('notify.events')">
          <VList density="compact">
            <VListItem v-for="ev in NOTIFY_EVENTS" :key="ev" :title="t(`notify.event.${ev}`)" :subtitle="t(`notify.eventDesc.${ev}`)">
              <template #append><VSwitch v-model="settings.events[ev]" color="primary" hide-details density="compact" :aria-label="t(`notify.event.${ev}`)" /></template>
            </VListItem>
          </VList>
        </VCard>
      </VCol>
    </VRow>
  </VContainer>
</template>
