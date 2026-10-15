<route lang="yaml">
meta:
  layout: portal
  roles: [CUSTOMER]
</route>

<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { useRouter } from 'vue-router'
import { createPortalSrApi, getPortalSrsApi } from '@/api/portalApi'
import { getSystemsApi } from '@/api/projectApi'
import AppStatusChip from '@/components/common/AppStatusChip.vue'
import ScreenPickerDialog from '@/components/sm/ScreenPickerDialog.vue'
import { usePortalStore } from '@/stores/portal'
import type { ApiError, SystemSummary } from '@/types/api'
import type { Page } from '@/types/common'
import { SR_TYPES, type PortalSrPayload, type ScreenPick, type Sr, type SrStatus } from '@/types/r3'
import { focusableRow } from '@/utils/a11y'
import { formatDateTime } from '@/utils/format'
import { showSuccessToast } from '@/utils/toast'

// SCR-SM-010 고객 SR 포털 — 내 요청 목록·새 요청 등록
const { t } = useI18n()
const auth = useAuthStore()
const router = useRouter()
const portal = usePortalStore()
const { currentProjectId, currentProject } = storeToRefs(portal)

const page = ref<Page<Sr> | null>(null)
const options = ref({ page: 1, itemsPerPage: 20 })
const filter = reactive({ status: null as SrStatus | null, q: '' })
const loading = ref(false)
const error = ref<string | null>(null)
const systems = ref<SystemSummary[]>([])
const form = reactive({ open: false, data: {} as PortalSrPayload, screenLabel: '', saving: false, errors: {} as Record<string, string>, error: null as string | null })
const pickerOpen = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null

// ─────────── 헬퍼 ───────────
const load = async () => {
  if (!currentProjectId.value) return
  loading.value = true
  error.value = null
  try {
    page.value = await getPortalSrsApi(currentProjectId.value, {
      status: filter.status ?? undefined, q: filter.q.trim() || undefined, page: options.value.page - 1, size: options.value.itemsPerPage,
    })
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}

const openForm = async () => {
  form.data = { type: 'INQUIRY', title: '', description: '', systemId: null, screenId: null, elementId: null }
  Object.assign(form, { open: true, screenLabel: '', saving: false, errors: {}, error: null })
  if (currentProjectId.value && !systems.value.length) systems.value = await getSystemsApi(currentProjectId.value).catch(() => [])
}

const onPick = (p: ScreenPick) => {
  Object.assign(form.data, { systemId: p.systemId, screenId: p.screenId, elementId: p.elementId })
  form.screenLabel = p.elementLabel ? `${p.screenName} › ${p.elementLabel}` : p.screenName
}

const submit = async () => {
  if (!currentProjectId.value) return
  const e: Record<string, string> = {}
  if (!form.data.title.trim()) e.title = t('validation.required')
  if (!form.data.description.trim()) e.description = t('validation.required')
  form.errors = e
  if (Object.keys(e).length) return
  form.saving = true
  try {
    const sr = await createPortalSrApi(currentProjectId.value, { ...form.data, title: form.data.title.trim(), description: form.data.description.trim() })
    form.open = false
    showSuccessToast('portal.created')
    await router.push(`/portal/srs/${sr.srId}`)
  } catch (err) {
    form.error = (err as ApiError).message ?? t('common.saveFailed')
  } finally {
    form.saving = false
  }
}

const onRowClick = (_e: Event, p: { item: Sr }) => { void router.push(`/portal/srs/${p.item.srId}`) }
const onOptions = (o: { page: number; itemsPerPage: number }) => { options.value = { page: o.page, itemsPerPage: o.itemsPerPage }; void load() }

// ─────────── computed ───────────
const statusItems = computed(() => (['RECEIVED', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'] as const).map((s) => ({ title: t(`sm.srStatus.${s}`), value: s })))
const typeItems = computed(() => SR_TYPES.map((v) => ({ title: t(`sm.srType.${v}`), value: v })))
const headers = computed(() => [
  { title: t('sm.col.no'), key: 'srNo', width: 110 },
  { title: t('sm.col.title'), key: 'title' },
  { title: t('sm.col.status'), key: 'status', width: 110 },
  { title: t('portal.requestedAt'), key: 'createdAt', width: 150 },
])
const waitingConfirm = computed(() => (page.value?.content ?? []).filter((s) => s.status === 'RESOLVED').length)

// ─────────── watch ───────────
watch(currentProjectId, () => { systems.value = []; void load() }, { immediate: true })
watch(() => filter.status, () => { options.value.page = 1; void load() })
watch(() => filter.q, () => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => { options.value.page = 1; void load() }, 300)
})
</script>

<template>
  <VContainer class="pa-4 pa-md-6 portal-wrap">
    <VCard class="portal-hero pa-6 pa-md-8 mb-6">
      <div class="d-flex flex-wrap align-center ga-4">
        <div class="flex-grow-1 head-text">
          <h1 class="portal-hello">{{ t('portal.hello', { name: auth.name ?? '' }) }}</h1>
          <div class="pmsx-page-subtitle">{{ t('portal.helloSub') }}</div>
          <VChip v-if="currentProject" size="small" variant="tonal" color="primary" class="mt-3" prepend-icon="ri-building-2-line">{{ currentProject.name }}</VChip>
        </div>
        <VBtn color="primary" size="large" prepend-icon="ri-add-line" :disabled="!currentProjectId" data-test="portal-new" @click="openForm">{{ t('portal.newSr') }}</VBtn>
      </div>
    </VCard>
    <h2 class="text-subtitle-1 font-weight-bold mb-3">{{ t('portal.mySrs') }}</h2>
    <VAlert v-if="waitingConfirm" type="info" variant="tonal" density="compact" class="mb-3" data-test="portal-waiting">{{ t('portal.waitingConfirm', { n: waitingConfirm }) }}</VAlert>
    <VAlert v-if="portal.loaded && !portal.projects.length" type="info" variant="tonal">{{ t('portal.noProject') }}</VAlert>
    <VCard v-else border flat>
      <VCardText class="pb-0">
        <VRow dense>
          <VCol cols="12" sm="7"><VTextField v-model="filter.q" :label="t('common.search')" prepend-inner-icon="ri-search-line" clearable hide-details /></VCol>
          <VCol cols="12" sm="5"><VSelect v-model="filter.status" :items="statusItems" :label="t('sm.col.status')" clearable hide-details /></VCol>
        </VRow>
      </VCardText>
      <VAlert v-if="error" type="error" variant="tonal" density="compact" class="ma-3">{{ error }}</VAlert>
      <VDataTableServer
        :headers="headers"
        :items="page?.content ?? []"
        :items-length="page?.totalElements ?? 0"
        :loading="loading"
        :page="options.page"
        :items-per-page="options.itemsPerPage"
        item-value="srId"
        hover
        data-test="portal-table"
        :row-props="focusableRow"
        @update:options="onOptions"
        @click:row="onRowClick"
      >
        <template #item.srNo="{ item }"><span class="font-mono">{{ item.srNo }}</span></template>
        <template #item.title="{ item }">
          <div>{{ item.title }}</div>
          <div class="text-caption text-muted">{{ t(`sm.srType.${item.type}`) }}<template v-if="item.screenName"> · {{ item.screenName }}</template></div>
        </template>
        <template #item.status="{ item }"><AppStatusChip :status="item.status" prefix="sm.srStatus" size="x-small" /></template>
        <template #item.createdAt="{ item }"><span class="font-mono text-caption">{{ formatDateTime(item.createdAt) }}</span></template>
        <template #no-data><div class="pa-6 text-muted">{{ t('portal.empty') }}</div></template>
      </VDataTableServer>
    </VCard>

    <VDialog v-model="form.open" max-width="600" scrollable>
      <VCard :title="t('portal.newSr')">
        <VCardText>
          <VAlert v-if="form.error" type="error" variant="tonal" density="compact" class="mb-3">{{ form.error }}</VAlert>
          <VSelect v-model="form.data.type" :items="typeItems" :label="t('portal.type')" class="mb-2" />
          <VTextField v-model="form.data.title" :label="t('portal.titleLabel')" :error-messages="form.errors.title" maxlength="200" counter="200" class="mb-2" data-test="portal-title" />
          <VTextarea v-model="form.data.description" :label="t('portal.description')" :placeholder="t('portal.descriptionHint')" :error-messages="form.errors.description" rows="5" auto-grow class="mb-2" data-test="portal-desc" />
          <div class="d-flex align-center ga-2">
            <VTextField :model-value="form.screenLabel" :label="t('portal.screen')" :placeholder="t('portal.screenOptional')" persistent-placeholder readonly hide-details prepend-inner-icon="ri-window-line" @click="pickerOpen = true" />
            <VBtn variant="tonal" :disabled="!systems.length" data-test="portal-pick" @click="pickerOpen = true">{{ t('sm.sr.pickScreen') }}</VBtn>
          </div>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="form.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :loading="form.saving" data-test="portal-submit" @click="submit">{{ t('portal.submit') }}</VBtn>
        </VCardActions>
      </VCard>
      <ScreenPickerDialog v-model="pickerOpen" :systems="systems" :initial="{ systemId: form.data.systemId, screenId: form.data.screenId, elementId: form.data.elementId }" @pick="onPick" />
    </VDialog>
  </VContainer>
</template>

<style scoped>
.portal-hero {
  background: linear-gradient(135deg, rgb(var(--v-theme-pastel-blue)) 0%, rgb(var(--v-theme-surface)) 55%, rgb(var(--v-theme-pastel-mint)) 130%) !important;
}

.portal-hello {
  font-size: clamp(1.5rem, 1.15rem + 0.9vw, 1.875rem);
  font-weight: 800;
  letter-spacing: -0.03em;
  line-height: 1.3;
}

.portal-wrap {
  max-width: 1100px;
}

.head-text {
  min-width: 0;
}
</style>
