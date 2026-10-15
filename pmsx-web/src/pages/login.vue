<route lang="yaml">
meta:
  layout: blank
</route>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { getLicenseStatusApi } from '@/api/systemApi'
import { homePathFor } from '@/router/guards'
import { useAuthStore } from '@/stores/auth'
import { useProjectStore } from '@/stores/project'
import AppLogo from '@/components/common/AppLogo.vue'
import { APP_NAME } from '@/themeConfig'
import type { ApiError } from '@/types/api'
import type { LicenseStatus } from '@/types/common'
import { formatDateTime } from '@/utils/format'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const projectStore = useProjectStore()

const formRef = ref<{ validate: () => Promise<{ valid: boolean }> } | null>(null)
const form = reactive({ loginId: '', password: '' })
const showPassword = ref(false)
const submitting = ref(false)
const errorMessage = ref<string | null>(null)
const license = ref<LicenseStatus | null>(null)

// ─────────── 헬퍼 ───────────
const required = (v: string) => !!v?.trim() || t('validation.required')

const submit = async () => {
  const result = await formRef.value?.validate()
  if (!result?.valid) return
  submitting.value = true
  errorMessage.value = null
  try {
    await auth.login({ loginId: form.loginId.trim(), password: form.password })
    projectStore.loaded = false
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : homePathFor(auth.roles)
    await router.replace(redirect)
  } catch (e) {
    errorMessage.value = loginErrorMessage(e as ApiError)
  } finally {
    submitting.value = false
  }
}

const loginErrorMessage = (err: ApiError) => {
  switch (err?.code) {
    case 'AUTH_INVALID': return t('auth.login.invalid')
    case 'AUTH_LOCKED': return t('auth.login.locked')
    case 'LICENSE_INVALID': return t('auth.login.licenseInvalid')
    case 'NETWORK': return t('common.error.network')
    default: return err?.message || t('common.error.unknown')
  }
}

const loadLicense = async () => {
  try {
    license.value = await getLicenseStatusApi()
  } catch {
    license.value = null // 상태를 모르면 배너를 띄우지 않는다
  }
}

// ─────────── 라이프사이클 ───────────
onMounted(loadLicense)
</script>

<template>
  <VContainer class="fill-height login-bg pa-4" fluid>
    <div class="blob blob--a" aria-hidden="true" />
    <div class="blob blob--b" aria-hidden="true" />
    <VRow justify="center" align="center" class="login-row">
      <VCol cols="12" sm="8" md="5" lg="4" xl="3">
        <div class="d-flex justify-center mb-6">
          <AppLogo to="/login" />
        </div>
        <VCard class="login-card pa-6 pa-sm-9">
          <h1 class="login-title">{{ t('auth.login.hello') }}</h1>
          <p class="pmsx-page-subtitle mb-7">{{ t('auth.login.helloSub') }}</p>
          <VAlert
            v-if="license && !license.valid"
            type="warning"
            variant="tonal"
            density="compact"
            class="mb-4"
            data-test="license-banner"
          >
            <div class="font-weight-medium">{{ t('auth.login.licenseBannerTitle') }}</div>
            <div class="text-caption">
              {{ license.reason ?? t('auth.login.licenseBannerReason') }}
              <template v-if="license.expiresAt"> · {{ t('auth.login.licenseExpiresAt', { at: formatDateTime(license.expiresAt) }) }}</template>
            </div>
            <div class="text-caption">{{ t('auth.login.licenseAdminOnly') }}</div>
          </VAlert>
          <VForm ref="formRef" @submit.prevent="submit">
            <VRow dense>
              <VCol cols="12" md="12">
                <VTextField
                  v-model="form.loginId"
                  :label="t('auth.login.loginId')"
                  :rules="[required]"
                  density="comfortable"
                  prepend-inner-icon="ri-user-3-line"
                  autocomplete="username"
                  autofocus
                  data-test="login-id"
                />
              </VCol>
              <VCol cols="12" md="12">
                <VTextField
                  v-model="form.password"
                  :label="t('auth.login.password')"
                  :rules="[required]"
                  density="comfortable"
                  :type="showPassword ? 'text' : 'password'"
                  :append-inner-icon="showPassword ? 'ri-eye-off-line' : 'ri-eye-line'"
                  prepend-inner-icon="ri-lock-2-line"
                  autocomplete="current-password"
                  data-test="login-password"
                  @click:append-inner="showPassword = !showPassword"
                />
              </VCol>
              <VCol v-if="errorMessage" cols="12" md="12">
                <VAlert type="error" variant="tonal" density="compact" role="alert">{{ errorMessage }}</VAlert>
              </VCol>
              <VCol cols="12" md="12" class="mt-2">
                <VBtn type="submit" color="primary" block size="x-large" :loading="submitting" class="login-btn" data-test="login-submit">
                  {{ t('auth.login.submit') }}
                </VBtn>
              </VCol>
            </VRow>
          </VForm>
        </VCard>
        <div class="text-center pmsx-label mt-6">{{ APP_NAME }} · {{ t('auth.login.subtitle') }}</div>
      </VCol>
    </VRow>
  </VContainer>
</template>

<style scoped>
.login-bg {
  position: relative;
  overflow: hidden;
  background: rgb(var(--v-theme-background));
}

/* 은은한 그라데이션 블롭 — 배경 장식(움직임 없음, 스크린리더 숨김) */
.blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(70px);
  opacity: 0.55;
  pointer-events: none;
}

.blob--a {
  width: 520px;
  height: 520px;
  top: -160px;
  left: -120px;
  background: radial-gradient(circle, rgba(49, 130, 246, 0.45), transparent 70%);
}

.blob--b {
  width: 480px;
  height: 480px;
  bottom: -180px;
  right: -100px;
  background: radial-gradient(circle, rgba(47, 212, 184, 0.4), transparent 70%);
}

.login-row {
  position: relative;
  z-index: 1;
}

.login-card {
  border-radius: 28px !important;
  box-shadow: var(--pmsx-shadow-pop) !important;
}

.login-title {
  font-size: clamp(1.5rem, 1.2rem + 0.8vw, 1.75rem);
  font-weight: 800;
  letter-spacing: -0.03em;
  line-height: 1.3;
}

.login-btn {
  font-size: 1rem;
  font-weight: 700;
}
</style>
