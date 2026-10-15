<route lang="yaml">
meta:
  roles: [SYS_ADMIN, PMO, PM]
</route>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { getSystemInfoApi, registerLicenseApi } from '@/api/systemApi'
import { useApi } from '@/composables/useApi'
import { useAuthStore } from '@/stores/auth'
import type { ApiError } from '@/types/api'
import type { SystemInfo } from '@/types/common'
import { formatDateTime } from '@/utils/format'
import { showSuccessToast } from '@/utils/toast'

const { t } = useI18n()
const auth = useAuthStore()
const { data: info, loading, error, execute: load } = useApi<SystemInfo>(() => getSystemInfoApi())

const keyText = ref('')
const registering = ref(false)
const registerError = ref<string | null>(null)

// ─────────── 헬퍼 ───────────
// 키 형식: base64url(payload).base64url(서명) — 형식만 먼저 확인하고 검증은 서버가 한다
const keyFormat = (v: string) => !v || /^[A-Za-z0-9_-]+={0,2}\.[A-Za-z0-9_-]+={0,2}$/.test(v.trim()) || t('validation.licenseKey')

const register = async () => {
  const key = keyText.value.trim()
  if (!key || keyFormat(key) !== true) return
  registering.value = true
  registerError.value = null
  try {
    const license = await registerLicenseApi(key)
    if (info.value) info.value.license = license
    keyText.value = ''
    showSuccessToast(license.valid ? 'admin.system.registered' : 'admin.system.registeredInvalid')
  } catch (e) {
    const err = e as ApiError
    registerError.value = err.code === 'LICENSE_INVALID' || err.code === 'VALIDATION'
      ? (err.message || t('admin.system.keyInvalid'))
      : (err.message ?? t('common.error.unknown'))
  } finally {
    registering.value = false
  }
}

// ─────────── computed ───────────
const isAdmin = computed(() => auth.hasRole('SYS_ADMIN'))
const license = computed(() => info.value?.license ?? null)
const daysLeft = computed(() => {
  const exp = license.value?.expiresAt
  if (!exp) return null
  return Math.ceil((new Date(exp).getTime() - Date.now()) / 86_400_000)
})
const userUsage = computed(() => {
  const max = license.value?.maxUsers
  if (!info.value || !max) return null
  return Math.min(100, Math.round((info.value.users.active / max) * 100))
})

// ─────────── 라이프사이클 ───────────
onMounted(load)
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <VRow>
      <VCol cols="12" md="12">
        <h1 class="pmsx-page-title">{{ t('nav.admin.system') }}</h1>
        <div class="text-body-2 text-muted">{{ t('admin.system.subtitle') }}</div>
      </VCol>

      <VCol v-if="error" cols="12" md="12">
        <VAlert type="error" variant="tonal">
          {{ error.message }}
          <VBtn variant="text" size="small" @click="load">{{ t('common.retry') }}</VBtn>
        </VAlert>
      </VCol>
      <VCol v-if="loading && !info" cols="12" md="12">
        <VProgressLinear indeterminate color="primary" />
      </VCol>

      <template v-if="info">
        <VCol cols="12" md="6">
          <VCard border flat :title="t('admin.system.infoTitle')" data-test="system-info">
            <VCardText>
              <dl class="kv">
                <dt>{{ t('admin.system.version') }}</dt><dd class="font-mono">{{ info.version }}</dd>
                <dt>{{ t('admin.system.buildTime') }}</dt><dd>{{ formatDateTime(info.buildTime) }}</dd>
                <dt>{{ t('admin.system.profile') }}</dt><dd><VChip size="x-small" variant="tonal">{{ info.profile }}</VChip></dd>
                <dt>{{ t('admin.system.db') }}</dt><dd>{{ info.db }}</dd>
                <dt>{{ t('admin.system.java') }}</dt><dd class="font-mono">{{ info.javaVersion }}</dd>
                <dt>{{ t('admin.system.users') }}</dt>
                <dd>{{ t('admin.system.usersValue', { active: info.users.active, total: info.users.total }) }}</dd>
              </dl>
            </VCardText>
          </VCard>
        </VCol>

        <VCol cols="12" md="6">
          <VCard border flat data-test="license-card">
            <VCardTitle class="d-flex align-center ga-2">
              {{ t('admin.system.licenseTitle') }}
              <VChip
                v-if="license"
                size="small"
                variant="tonal"
                :color="license.valid ? 'success' : 'error'"
                data-test="license-state"
              >
                {{ license.valid ? t('admin.system.valid') : t('admin.system.invalid') }}
              </VChip>
            </VCardTitle>
            <VCardText>
              <div v-if="!license" class="text-muted">{{ t('admin.system.noLicense') }}</div>
              <template v-else>
                <VAlert v-if="!license.valid" type="error" variant="tonal" density="compact" class="mb-3">
                  {{ license.reason ?? t('admin.system.invalid') }}
                </VAlert>
                <dl v-if="license.customer" class="kv">
                  <dt>{{ t('admin.system.customer') }}</dt><dd>{{ license.customer }}</dd>
                  <dt>{{ t('admin.system.issuedAt') }}</dt><dd>{{ formatDateTime(license.issuedAt) }}</dd>
                  <dt>{{ t('admin.system.expiresAt') }}</dt>
                  <dd>
                    {{ formatDateTime(license.expiresAt) }}
                    <VChip v-if="daysLeft !== null" size="x-small" variant="tonal" class="ml-1" :color="daysLeft < 0 ? 'error' : daysLeft <= 30 ? 'warning' : 'default'">
                      {{ daysLeft < 0 ? t('admin.system.expired') : t('admin.system.daysLeft', { n: daysLeft }) }}
                    </VChip>
                  </dd>
                  <dt>{{ t('admin.system.maxUsers') }}</dt>
                  <dd>
                    {{ license.maxUsers }}
                    <VProgressLinear v-if="userUsage !== null" :model-value="userUsage" :color="userUsage >= 90 ? 'warning' : 'primary'" height="6" rounded class="mt-1" />
                  </dd>
                  <dt>{{ t('admin.system.features') }}</dt>
                  <dd>
                    <VChip v-for="f in license.features ?? []" :key="f" size="x-small" variant="outlined" class="mr-1">{{ f }}</VChip>
                  </dd>
                </dl>
                <div v-else class="text-caption text-muted">{{ t('admin.system.detailAdminOnly') }}</div>
              </template>
            </VCardText>
          </VCard>
        </VCol>

        <VCol v-if="isAdmin" cols="12" md="12">
          <VCard border flat :title="t('admin.system.registerTitle')">
            <VCardText>
              <VRow>
                <VCol cols="12" md="12">
                  <VTextarea
                    v-model="keyText"
                    :label="t('admin.system.key')"
                    :hint="t('admin.system.keyHint')"
                    persistent-hint
                    :rules="[keyFormat]"
                    rows="3"
                    auto-grow
                    density="compact"
                    class="font-mono"
                    data-test="license-key"
                  />
                </VCol>
                <VCol v-if="registerError" cols="12" md="12">
                  <VAlert type="error" variant="tonal" density="compact" role="alert" data-test="license-error">{{ registerError }}</VAlert>
                </VCol>
                <VCol cols="12" md="12" class="d-flex">
                  <VSpacer />
                  <VBtn color="primary" prepend-icon="ri-key-2-line" :loading="registering" :disabled="!keyText.trim()" data-test="license-register" @click="register">
                    {{ t('admin.system.register') }}
                  </VBtn>
                </VCol>
              </VRow>
            </VCardText>
          </VCard>
        </VCol>
      </template>
    </VRow>
  </VContainer>
</template>

<style scoped>
.kv {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr);
  gap: 8px 12px;
  margin: 0;
}

.kv dt {
  color: rgba(var(--v-theme-on-surface), var(--v-medium-emphasis-opacity));
}

.kv dd {
  margin: 0;
  word-break: break-all;
}
</style>
