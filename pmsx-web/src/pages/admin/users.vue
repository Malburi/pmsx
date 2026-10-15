<route lang="yaml">
meta:
  roles: [SYS_ADMIN]
</route>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  createAdminUserApi, getAdminUsersApi, getRolesApi, lockUserApi, resetPasswordApi, unlockUserApi, updateAdminUserApi,
} from '@/api/adminApi'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import ExcelExportButton from '@/components/common/ExcelExportButton.vue'
import { useCodes } from '@/composables/useCodes'
import { useElementWidth } from '@/composables/useElementWidth'
import type { ApiError } from '@/types/api'
import type { AdminUser, AuthType, Role } from '@/types/common'
import { fitColumns } from '@/utils/columns'
import { formatDateTime } from '@/utils/format'
import { showErrorToast, showSuccessToast } from '@/utils/toast'
import { isEmail, isPasswordPolicyOk } from '@/utils/validators'

const { t } = useI18n()
const { items: codeItems, label: codeLabel } = useCodes(['ORG_TYPE'])

const q = ref('')
const page = ref(1)
const itemsPerPage = ref(20)
const users = ref<AdminUser[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref<string | null>(null)
const roles = ref<Role[]>([])

const dialog = ref(false)
const editing = ref<AdminUser | null>(null)
const formRef = ref<{ validate: () => Promise<{ valid: boolean }> } | null>(null)
const form = reactive({ loginId: '', name: '', email: '', orgType: '', authType: 'LOCAL' as AuthType, roles: [] as string[], password: '' })
const saving = ref(false)
const formError = ref<string | null>(null)
const fieldErrors = reactive<Record<string, string[]>>({})

const resetTarget = ref<AdminUser | null>(null)
const resetForm = reactive({ password: '' })
const resetFormRef = ref<{ validate: () => Promise<{ valid: boolean }> } | null>(null)
const resetError = ref<string | null>(null)
const resetting = ref(false)
const lockTarget = ref<AdminUser | null>(null)
const locking = ref(false)

// ─────────── 헬퍼 ───────────
const required = (v: unknown) => (Array.isArray(v) ? v.length > 0 : !!String(v ?? '').trim()) || t('validation.required')
const email = (v: string) => isEmail(v) || t('validation.email')
const loginIdFormat = (v: string) => /^[a-z][a-z0-9._-]{2,29}$/.test(v) || t('validation.loginId')
const policy = (v: string) => isPasswordPolicyOk(v) || t('validation.passwordPolicy')

const load = async () => {
  loading.value = true
  loadError.value = null
  try {
    const res = await getAdminUsersApi({ q: q.value.trim() || undefined, page: page.value - 1, size: itemsPerPage.value })
    users.value = res.content
    total.value = res.totalElements
  } catch (e) {
    loadError.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}

const onOptions = (opts: { page: number; itemsPerPage: number }) => {
  page.value = opts.page
  itemsPerPage.value = opts.itemsPerPage
  void load()
}

const search = () => {
  page.value = 1
  void load()
}

const loadRoles = async () => {
  try {
    roles.value = await getRolesApi()
  } catch {
    roles.value = []
  }
}

const clearFieldErrors = () => Object.keys(fieldErrors).forEach((k) => delete fieldErrors[k])

const openCreate = () => {
  editing.value = null
  Object.assign(form, { loginId: '', name: '', email: '', orgType: codeItems('ORG_TYPE')[0]?.value ?? '', authType: 'LOCAL', roles: [], password: '' })
  formError.value = null
  clearFieldErrors()
  dialog.value = true
}

const openEdit = (u: AdminUser) => {
  editing.value = u
  Object.assign(form, { loginId: u.loginId, name: u.name, email: u.email ?? '', orgType: u.orgType, authType: u.authType, roles: [...u.roles], password: '' })
  formError.value = null
  clearFieldErrors()
  dialog.value = true
}

const save = async () => {
  clearFieldErrors()
  const result = await formRef.value?.validate()
  if (!result?.valid) return
  saving.value = true
  formError.value = null
  const base = { name: form.name.trim(), email: form.email.trim() || undefined, orgType: form.orgType, authType: form.authType, roles: form.roles }
  try {
    if (editing.value) await updateAdminUserApi(editing.value.userId, base)
    else await createAdminUserApi({ ...base, loginId: form.loginId.trim(), password: form.authType === 'LOCAL' ? form.password : undefined })
    dialog.value = false
    showSuccessToast(editing.value ? 'admin.users.updated' : 'admin.users.created')
    await load()
  } catch (e) {
    const err = e as ApiError
    if (err.fields) Object.entries(err.fields).forEach(([k, v]) => { fieldErrors[k] = [v] })
    if (err.code === 'CONFLICT') fieldErrors.loginId = [t('admin.users.conflict')]
    else if (err.code === 'PASSWORD_POLICY') fieldErrors.password = [err.message || t('validation.passwordPolicy')]
    else if (!err.fields) formError.value = err.message ?? t('common.error.unknown')
  } finally {
    saving.value = false
  }
}

const openReset = (u: AdminUser) => {
  resetTarget.value = u
  resetForm.password = ''
  resetError.value = null
}

const doReset = async () => {
  const result = await resetFormRef.value?.validate()
  if (!result?.valid || !resetTarget.value) return
  resetting.value = true
  resetError.value = null
  try {
    await resetPasswordApi(resetTarget.value.userId, resetForm.password)
    resetTarget.value = null
    showSuccessToast('admin.users.passwordReset')
  } catch (e) {
    const err = e as ApiError
    resetError.value = err.code === 'PASSWORD_POLICY' ? (err.message || t('validation.passwordPolicy')) : (err.message ?? t('common.error.unknown'))
  } finally {
    resetting.value = false
  }
}

const toggleLock = async () => {
  const u = lockTarget.value
  if (!u) return
  locking.value = true
  try {
    if (u.locked) await unlockUserApi(u.userId)
    else await lockUserApi(u.userId)
    lockTarget.value = null
    showSuccessToast(u.locked ? 'admin.users.unlocked' : 'admin.users.locked')
    await load()
  } catch {
    showErrorToast('common.error.unknown')
  } finally {
    locking.value = false
  }
}

// ─────────── computed ───────────
// 화면이 좁으면 덜 중요한 열을 숨긴다
const listCard = ref<HTMLElement | { $el: HTMLElement } | null>(null)
const listWidth = useElementWidth(listCard, 1200)
const allHeaders = computed(() => [
  { title: t('admin.users.loginId'), key: 'loginId', width: 140 },
  { title: t('admin.users.name'), key: 'name', width: 120 },
  { title: t('admin.users.email'), key: 'email', hideBelow: 1100 },
  { title: t('admin.users.orgType'), key: 'orgType', width: 100 },
  { title: t('admin.users.authType'), key: 'authType', width: 90, hideBelow: 1000 },
  { title: t('admin.users.roles'), key: 'roles', sortable: false },
  { title: t('admin.users.status'), key: 'locked', width: 90 },
  { title: t('admin.users.lastLoginAt'), key: 'lastLoginAt', width: 150, hideBelow: 900 },
  { title: '', key: 'actions', sortable: false, align: 'end' as const, width: 150 },
])
const headers = computed(() => fitColumns(allHeaders.value, listWidth.value))

const systemRoleItems = computed(() =>
  roles.value.filter((r) => r.scope === 'SYSTEM').map((r) => ({ title: `${r.name} (${r.roleId})`, value: r.roleId })))

const authTypeItems = computed(() => [
  { title: t('admin.users.authLocal'), value: 'LOCAL' },
  { title: t('admin.users.authLdap'), value: 'LDAP' },
])

// ─────────── 라이프사이클 ───────────
onMounted(loadRoles)
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <VRow>
      <VCol cols="12" md="12">
        <div class="d-flex flex-wrap align-center ga-3">
          <div>
            <h1 class="pmsx-page-title">{{ t('nav.admin.user') }}</h1>
            <div class="text-body-2 text-muted">{{ t('admin.users.subtitle') }}</div>
          </div>
          <VSpacer />
          <ExcelExportButton dataset-id="users" :file-name="t('admin.users.exportName')" />
          <VBtn color="primary" prepend-icon="ri-user-add-line" data-test="user-add" @click="openCreate">{{ t('admin.users.add') }}</VBtn>
        </div>
      </VCol>

      <VCol cols="12" md="12">
        <VCard ref="listCard" border flat>
          <div class="pa-3 d-flex ga-2 align-center">
            <VTextField
              v-model="q"
              :label="t('admin.users.search')"
              prepend-inner-icon="ri-search-line"
              clearable
              class="search-box"
              data-test="user-search"
              @keyup.enter="search"
              @click:clear="() => { q = ''; search() }"
            />
            <VBtn variant="tonal" @click="search">{{ t('common.search') }}</VBtn>
          </div>
          <VAlert v-if="loadError" type="error" variant="tonal" class="mx-3 mb-3">
            {{ loadError }}
            <VBtn variant="text" size="small" @click="load">{{ t('common.retry') }}</VBtn>
          </VAlert>
          <VDataTableServer
            :headers="headers"
            :items="users"
            :items-length="total"
            :loading="loading"
            :items-per-page="itemsPerPage"
            :page="page"
            item-value="userId"
            density="comfortable"
            @update:options="onOptions"
          >
            <template #item.loginId="{ item }"><span class="font-mono">{{ item.loginId }}</span></template>
            <template #item.email="{ item }">{{ item.email ?? '-' }}</template>
            <template #item.orgType="{ item }">{{ codeLabel('ORG_TYPE', item.orgType) }}</template>
            <template #item.authType="{ item }">
              <VChip size="x-small" variant="tonal" :color="item.authType === 'LDAP' ? 'info' : 'default'">{{ item.authType }}</VChip>
            </template>
            <template #item.roles="{ item }">
              <div class="d-flex flex-wrap ga-1 py-1">
                <VChip v-for="r in item.roles" :key="r" size="x-small" variant="outlined">{{ r }}</VChip>
              </div>
            </template>
            <template #item.locked="{ item }">
              <VChip size="x-small" variant="tonal" :color="item.locked ? 'error' : 'success'">
                {{ item.locked ? t('admin.users.lockedLabel') : t('admin.users.activeLabel') }}
              </VChip>
            </template>
            <template #item.lastLoginAt="{ item }">{{ formatDateTime(item.lastLoginAt) }}</template>
            <template #item.actions="{ item }">
              <div class="d-flex justify-end flex-nowrap">
              <VBtn icon="ri-pencil-line" variant="text" size="small" :aria-label="t('common.edit')" :title="t('common.edit')" @click="openEdit(item)" />
              <VBtn
                v-if="item.authType === 'LOCAL'"
                icon="ri-key-2-line" variant="text" size="small"
                :aria-label="t('admin.users.resetPassword')" :title="t('admin.users.resetPassword')"
                @click="openReset(item)"
              />
              <VBtn
                :icon="item.locked ? 'ri-lock-unlock-line' : 'ri-lock-line'" variant="text" size="small"
                :color="item.locked ? 'success' : 'error'"
                :aria-label="item.locked ? t('admin.users.unlock') : t('admin.users.lock')"
                :title="item.locked ? t('admin.users.unlock') : t('admin.users.lock')"
                @click="lockTarget = item"
              />
              </div>
            </template>
            <template #no-data>
              <div class="py-8 text-muted">{{ t('common.noData') }}</div>
            </template>
          </VDataTableServer>
        </VCard>
      </VCol>
    </VRow>

    <!-- 등록/수정 -->
    <VDialog v-model="dialog" max-width="600" persistent>
      <VCard :title="editing ? t('admin.users.editTitle') : t('admin.users.createTitle')">
        <VForm ref="formRef" @submit.prevent="save">
          <VCardText>
            <VRow>
              <VCol cols="12" sm="6">
                <VTextField
                  v-model="form.loginId"
                  :label="t('admin.users.loginId')"
                  :rules="editing ? [] : [required, loginIdFormat]"
                  :disabled="!!editing"
                  :error-messages="fieldErrors.loginId"
                  data-test="user-loginId"
                />
              </VCol>
              <VCol cols="12" sm="6">
                <VTextField v-model="form.name" :label="t('admin.users.name')" :rules="[required]" :error-messages="fieldErrors.name" data-test="user-name" />
              </VCol>
              <VCol cols="12" sm="6">
                <VTextField v-model="form.email" :label="t('admin.users.email')" :rules="[email]" :error-messages="fieldErrors.email" />
              </VCol>
              <VCol cols="12" sm="6">
                <VSelect v-model="form.orgType" :items="codeItems('ORG_TYPE')" :label="t('admin.users.orgType')" :rules="[required]" />
              </VCol>
              <VCol cols="12" sm="6">
                <VSelect v-model="form.authType" :items="authTypeItems" :label="t('admin.users.authType')" :disabled="!!editing" />
              </VCol>
              <VCol cols="12" sm="6">
                <VSelect v-model="form.roles" :items="systemRoleItems" :label="t('admin.users.roles')" multiple chips closable-chips />
              </VCol>
              <VCol v-if="!editing && form.authType === 'LOCAL'" cols="12" md="12">
                <VTextField
                  v-model="form.password"
                  type="password"
                  :label="t('admin.users.initialPassword')"
                  :rules="[required, policy]"
                  :error-messages="fieldErrors.password"
                  :hint="t('validation.passwordPolicy')"
                  persistent-hint
                  autocomplete="new-password"
                  data-test="user-password"
                />
              </VCol>
              <VCol v-if="form.authType === 'LDAP'" cols="12" md="12">
                <VAlert type="info" variant="tonal" density="compact">{{ t('admin.users.ldapHint') }}</VAlert>
              </VCol>
              <VCol v-if="formError" cols="12" md="12">
                <VAlert type="error" variant="tonal" density="compact" role="alert">{{ formError }}</VAlert>
              </VCol>
            </VRow>
          </VCardText>
          <VCardActions>
            <VSpacer />
            <VBtn variant="text" @click="dialog = false">{{ t('common.cancel') }}</VBtn>
            <VBtn type="submit" color="primary" :loading="saving" data-test="user-save">{{ t('common.save') }}</VBtn>
          </VCardActions>
        </VForm>
      </VCard>
    </VDialog>

    <!-- 비밀번호 초기화 -->
    <VDialog :model-value="!!resetTarget" max-width="440" persistent>
      <VCard v-if="resetTarget" :title="t('admin.users.resetTitle', { name: resetTarget.name })">
        <VForm ref="resetFormRef" @submit.prevent="doReset">
          <VCardText>
            <VRow>
              <VCol cols="12" md="12">
                <VTextField
                  v-model="resetForm.password"
                  type="password"
                  :label="t('admin.users.newPassword')"
                  :rules="[required, policy]"
                  :hint="t('validation.passwordPolicy')"
                  persistent-hint
                  autocomplete="new-password"
                />
              </VCol>
              <VCol v-if="resetError" cols="12" md="12">
                <VAlert type="error" variant="tonal" density="compact" role="alert">{{ resetError }}</VAlert>
              </VCol>
            </VRow>
          </VCardText>
          <VCardActions>
            <VSpacer />
            <VBtn variant="text" @click="resetTarget = null">{{ t('common.cancel') }}</VBtn>
            <VBtn type="submit" color="primary" :loading="resetting">{{ t('admin.users.resetPassword') }}</VBtn>
          </VCardActions>
        </VForm>
      </VCard>
    </VDialog>

    <ConfirmDialog
      :model-value="!!lockTarget"
      :title="lockTarget?.locked ? t('admin.users.unlock') : t('admin.users.lock')"
      :message="t(lockTarget?.locked ? 'admin.users.unlockConfirm' : 'admin.users.lockConfirm', { name: lockTarget?.name ?? '' })"
      :color="lockTarget?.locked ? 'success' : 'error'"
      :loading="locking"
      @update:model-value="(v: boolean) => { if (!v) lockTarget = null }"
      @confirm="toggleLock"
    />
  </VContainer>
</template>

<style scoped>
.search-box {
  max-width: 360px;
}
</style>
