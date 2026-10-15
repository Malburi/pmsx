<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { changePasswordApi } from '@/api/authApi'
import type { ApiError } from '@/types/api'
import { showSuccessToast } from '@/utils/toast'
import { isPasswordPolicyOk } from '@/utils/validators'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const { t } = useI18n()
const formRef = ref<{ validate: () => Promise<{ valid: boolean }>; resetValidation: () => void } | null>(null)
const form = reactive({ currentPassword: '', newPassword: '', confirm: '' })
const saving = ref(false)
const errorMessage = ref<string | null>(null)
const fieldErrors = reactive<{ currentPassword: string[]; newPassword: string[] }>({ currentPassword: [], newPassword: [] })

// ─────────── 헬퍼 ───────────
const required = (v: string) => !!v || t('validation.required')
const policy = (v: string) => isPasswordPolicyOk(v) || t('validation.passwordPolicy')
const differs = (v: string) => v !== form.currentPassword || t('validation.passwordSame')
const matches = (v: string) => v === form.newPassword || t('validation.passwordMismatch')

const close = () => emit('update:modelValue', false)

const submit = async () => {
  fieldErrors.currentPassword = []
  fieldErrors.newPassword = []
  const result = await formRef.value?.validate()
  if (!result?.valid) return
  saving.value = true
  errorMessage.value = null
  try {
    await changePasswordApi({ currentPassword: form.currentPassword, newPassword: form.newPassword })
    showSuccessToast('auth.password.changed')
    close()
  } catch (e) {
    const err = e as ApiError
    // 서버 검증 오류는 해당 필드에 붙인다 (forms-validation §1)
    if (err.code === 'AUTH_INVALID') fieldErrors.currentPassword = [t('auth.password.currentInvalid')]
    else if (err.code === 'PASSWORD_POLICY') fieldErrors.newPassword = [err.message || t('validation.passwordPolicy')]
    else if (err.fields?.newPassword) fieldErrors.newPassword = [err.fields.newPassword]
    else errorMessage.value = err.message ?? t('common.error.unknown')
  } finally {
    saving.value = false
  }
}

// ─────────── watch ───────────
watch(() => props.modelValue, (open) => {
  if (!open) return
  Object.assign(form, { currentPassword: '', newPassword: '', confirm: '' })
  errorMessage.value = null
  fieldErrors.currentPassword = []
  fieldErrors.newPassword = []
  formRef.value?.resetValidation()
})
</script>

<template>
  <VDialog :model-value="modelValue" max-width="460" persistent @update:model-value="(v: boolean) => emit('update:modelValue', v)">
    <VCard :title="t('auth.password.title')">
      <VForm ref="formRef" @submit.prevent="submit">
        <VCardText>
          <VRow>
            <VCol cols="12" md="12">
              <VTextField
                v-model="form.currentPassword"
                type="password"
                :label="t('auth.password.current')"
                :rules="[required]"
                :error-messages="fieldErrors.currentPassword"
                autocomplete="current-password"
                data-test="pw-current"
              />
            </VCol>
            <VCol cols="12" md="12">
              <VTextField
                v-model="form.newPassword"
                type="password"
                :label="t('auth.password.new')"
                :rules="[required, policy, differs]"
                :error-messages="fieldErrors.newPassword"
                :hint="t('validation.passwordPolicy')"
                persistent-hint
                autocomplete="new-password"
                data-test="pw-new"
              />
            </VCol>
            <VCol cols="12" md="12">
              <VTextField
                v-model="form.confirm"
                type="password"
                :label="t('auth.password.confirm')"
                :rules="[required, matches]"
                autocomplete="new-password"
                data-test="pw-confirm"
              />
            </VCol>
            <VCol v-if="errorMessage" cols="12" md="12">
              <VAlert type="error" variant="tonal" density="compact" role="alert">{{ errorMessage }}</VAlert>
            </VCol>
          </VRow>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="close">{{ t('common.cancel') }}</VBtn>
          <VBtn type="submit" color="primary" :loading="saving" data-test="pw-submit">{{ t('common.save') }}</VBtn>
        </VCardActions>
      </VForm>
    </VCard>
  </VDialog>
</template>
