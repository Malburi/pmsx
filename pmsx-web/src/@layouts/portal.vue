<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import AppLogo from '@/components/common/AppLogo.vue'
import ChangePasswordDialog from '@/components/common/ChangePasswordDialog.vue'
import { useAuthStore } from '@/stores/auth'
import { usePortalStore } from '@/stores/portal'
import { useUiStore } from '@/stores/ui'

// 고객 SR 포털 전용 레이아웃 (SCR-SM-010) — 메뉴 없이 간결한 상단바만 둔다
const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()
const ui = useUiStore()
const portal = usePortalStore()
const { projects, currentProjectId } = storeToRefs(portal)
const passwordDialog = ref(false)

const logout = async () => {
  await auth.logout()
  await router.push('/login')
}

const projectItems = computed(() => projects.value.map((p) => ({ title: p.name, value: p.projectId })))

onMounted(() => { if (!portal.loaded) void portal.load().catch(() => {}) })
</script>

<template>
  <VAppBar flat height="60" color="surface" class="portal-bar">
    <AppLogo to="/portal" class="ml-4 mr-3" />
    <VChip size="small" color="primary" variant="tonal" class="mr-4 d-none d-sm-inline-flex">{{ t('portal.title') }}</VChip>
    <VSelect
      v-if="projects.length > 1"
      v-model="currentProjectId"
      :items="projectItems"
      :label="t('layout.project')"
      class="portal-project"
      variant="solo-filled"
      flat
      rounded="pill"
      single-line
      hide-details
    />
    <VSpacer />
    <VBtn icon variant="text" size="small" :aria-label="t('common.theme.toggle')" @click="ui.toggleTheme()">
      <VIcon :icon="ui.theme === 'dark' ? 'ri-sun-line' : 'ri-moon-line'" />
    </VBtn>
    <VMenu location="bottom end">
      <template #activator="{ props: mp }">
        <VBtn v-bind="mp" variant="text" rounded="pill" class="mr-2 px-2" :aria-label="t('layout.userMenu')">
          <VAvatar size="30" class="mr-2 portal-avatar">{{ (auth.name ?? '?').slice(0, 1) }}</VAvatar>
          <span class="d-none d-sm-inline">{{ auth.name }}</span>
        </VBtn>
      </template>
      <VList density="compact" min-width="200">
        <VListItem :title="auth.name ?? ''" :subtitle="t('portal.customer')" />
        <VDivider />
        <VListItem prepend-icon="ri-lock-password-line" :title="t('auth.password.title')" @click="passwordDialog = true" />
        <VListItem prepend-icon="ri-logout-box-r-line" :title="t('common.logout')" data-test="portal-logout" @click="logout" />
      </VList>
    </VMenu>
  </VAppBar>
  <VMain>
    <RouterView />
  </VMain>
  <ChangePasswordDialog v-model="passwordDialog" />
</template>

<style scoped>
.portal-bar {
  border-bottom: 1px solid rgba(var(--v-theme-line), 0.6) !important;
}

.portal-avatar {
  background: linear-gradient(135deg, rgb(var(--v-theme-pastel-mint)), rgb(var(--v-theme-pastel-blue)));
  color: rgb(var(--v-theme-primary));
  font-weight: 700;
}

.portal-project {
  max-width: 280px;
  min-width: 160px;
}
</style>
