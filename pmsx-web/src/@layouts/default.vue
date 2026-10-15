<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useDisplay } from 'vuetify'
import { getLicenseStatusApi } from '@/api/systemApi'
import AppLogo from '@/components/common/AppLogo.vue'
import AppNavList from '@/components/common/AppNavList.vue'
import ChangePasswordDialog from '@/components/common/ChangePasswordDialog.vue'
import GlobalSearch from '@/components/common/GlobalSearch.vue'
import NotificationBell from '@/components/common/NotificationBell.vue'
import { useAuthStore } from '@/stores/auth'
import { useProjectStore } from '@/stores/project'
import { useUiStore } from '@/stores/ui'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const { mdAndUp } = useDisplay()
const auth = useAuthStore()
const ui = useUiStore()
const projectStore = useProjectStore()
const { projects, currentProjectId } = storeToRefs(projectStore)

const drawerOpen = ref(false)
const passwordDialog = ref(false)
const licenseInvalid = ref(false)
// 화면 단위 접힘 상태. null 이면 기본값(라우트 meta.compactNav 또는 사용자 설정)을 따른다.
const railOverride = ref<boolean | null>(null)

// ─────────── 헬퍼 ───────────
const toggleNav = () => {
  if (!mdAndUp.value) { drawerOpen.value = !drawerOpen.value; return }
  const next = !isRail.value
  if (route.meta.compactNav) railOverride.value = next
  else ui.navRail = next
}

const onProjectChange = async (projectId: string) => {
  await projectStore.selectProject(projectId)
}

const logout = async () => {
  await auth.logout()
  await router.push('/login')
}

// ─────────── computed ───────────
const projectItems = computed(() =>
  projects.value.map((p) => ({ title: `[${p.type}] ${p.name}`, value: p.projectId })),
)
const drawerModel = computed({
  get: () => (mdAndUp.value ? true : drawerOpen.value),
  set: (v: boolean) => { drawerOpen.value = v },
})
const isRail = computed(() => mdAndUp.value && (railOverride.value ?? (route.meta.compactNav ? true : ui.navRail)))
const initials = computed(() => (auth.name ?? auth.loginId ?? '?').slice(0, 1))

// ─────────── watch ───────────
watch(() => route.path, () => { railOverride.value = null })

// ─────────── 라이프사이클 ───────────
onMounted(async () => {
  if (!projectStore.loaded) await projectStore.loadProjects().catch(() => {})
  // 라이선스가 무효면 관리자에게 상단 배너로 알린다 (로그인 자체는 관리자만 가능)
  licenseInvalid.value = await getLicenseStatusApi().then((st) => !st.valid).catch(() => false)
})
</script>

<template>
  <VAppBar flat height="60" color="surface" class="app-bar">
    <VAppBarNavIcon :aria-label="t('layout.toggleNav')" class="ml-1" @click="toggleNav" />
    <AppLogo class="mr-4 app-bar__logo" />
    <VSelect
      :model-value="currentProjectId"
      :items="projectItems"
      :aria-label="t('layout.project')"
      class="project-select"
      variant="solo-filled"
      flat
      rounded="pill"
      density="compact"
      hide-details
      prepend-inner-icon="ri-briefcase-4-line"
      single-line
      @update:model-value="onProjectChange"
    />
    <VSpacer />
    <GlobalSearch v-if="mdAndUp" class="mr-3" />
    <NotificationBell />
    <VBtn
      icon
      variant="text"
      size="small"
      :aria-label="t('common.theme.toggle')"
      @click="ui.toggleTheme()"
    >
      <VIcon :icon="ui.theme === 'dark' ? 'ri-sun-line' : 'ri-moon-line'" />
    </VBtn>
    <VMenu location="bottom end">
      <template #activator="{ props: menuProps }">
        <VBtn v-bind="menuProps" variant="text" rounded="pill" class="ml-1 mr-2 px-2" :aria-label="t('layout.userMenu')">
          <VAvatar size="30" class="mr-2 user-avatar">{{ initials }}</VAvatar>
          <span class="d-none d-sm-inline font-weight-medium">{{ auth.name }}</span>
          <VIcon icon="ri-arrow-down-s-line" size="18" class="ml-1 text-muted d-none d-sm-inline" />
        </VBtn>
      </template>
      <VList density="compact" min-width="220">
        <VListItem :title="auth.name ?? ''" :subtitle="auth.roles.join(', ')" />
        <VDivider class="my-1" />
        <VListItem prepend-icon="ri-lock-password-line" :title="t('auth.password.title')" data-test="menu-change-password" @click="passwordDialog = true" />
        <VListItem prepend-icon="ri-logout-box-r-line" :title="t('common.logout')" @click="logout" />
      </VList>
    </VMenu>
  </VAppBar>

  <VNavigationDrawer
    v-model="drawerModel"
    :rail="isRail"
    :expand-on-hover="isRail"
    :temporary="!mdAndUp"
    :permanent="mdAndUp"
    width="252"
    class="app-drawer"
  >
    <AppNavList :rail="isRail" />
  </VNavigationDrawer>

  <VMain>
    <VAlert v-if="licenseInvalid" type="warning" variant="tonal" density="compact" rounded="0" class="px-6" data-test="license-app-banner">
      {{ t('layout.licenseInvalid') }}
      <RouterLink v-if="auth.hasRole('SYS_ADMIN')" to="/admin/system" class="ml-2">{{ t('layout.licenseManage') }}</RouterLink>
    </VAlert>
    <RouterView />
  </VMain>

  <ChangePasswordDialog v-model="passwordDialog" />
</template>

<style scoped>
.app-bar {
  border-bottom: 1px solid rgba(var(--v-theme-line), 0.6) !important;
}

.project-select {
  max-width: 320px;
  min-width: 170px;
}

.project-select :deep(.v-field) {
  border-radius: 999px !important;
  background: rgb(var(--v-theme-surface-2));
  font-weight: 600;
  font-size: 0.875rem;
}

.user-avatar {
  background: linear-gradient(135deg, rgb(var(--v-theme-pastel-blue)), rgb(var(--v-theme-pastel-lavender)));
  color: rgb(var(--v-theme-primary));
  font-weight: 700;
  font-size: 0.8125rem;
}

.app-drawer :deep(.v-list) {
  padding: 12px 10px;
}

@media (max-width: 599px) {
  .project-select {
    max-width: 170px;
  }

  .app-bar__logo {
    display: none !important;
  }
}
</style>
