<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { createOpsAssetApi, deleteOpsAssetApi, getOpsAssetsApi, updateOpsAssetApi } from '@/api/smApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useAuthStore } from '@/stores/auth'
import type { ApiError } from '@/types/api'
import { OPS_ASSET_TYPES, type OpsAsset, type OpsAssetPayload, type OpsAssetType } from '@/types/r3'
import { SM_EDIT_ROLES } from '@/utils/permission'
import { expiryLevel } from '@/utils/sm'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// SCR-SM-006 운영 자산 (FR-SM-010) — 서버·계정·인증서·라이선스·도메인 대장, 만료 D-day 강조
const { t } = useI18n()
const auth = useAuthStore()

const list = ref<OpsAsset[]>([])
const loading = ref(false)
const error = ref<string | null>(null)
const filter = reactive({ type: null as OpsAssetType | null, q: '', expiringOnly: false })
const form = reactive({ open: false, editing: null as OpsAsset | null, data: {} as OpsAssetPayload, saving: false, error: null as string | null })
const removeTarget = ref<OpsAsset | null>(null)

// ─────────── 헬퍼 ───────────
const load = async () => {
  if (!projectId.value) return
  loading.value = true
  error.value = null
  try {
    list.value = await getOpsAssetsApi(projectId.value)
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const { projectId } = useCurrentProject(() => load())

const openForm = (a: OpsAsset | null) => {
  form.editing = a
  form.data = a
    ? { type: a.type, name: a.name, host: a.host, owner: a.owner, expiresAt: a.expiresAt, note: a.note }
    : { type: 'SERVER', name: '', host: null, owner: null, expiresAt: null, note: null }
  Object.assign(form, { open: true, saving: false, error: null })
}

const save = async () => {
  if (!projectId.value) return
  if (!form.data.name.trim()) { form.error = t('validation.required'); return }
  form.saving = true
  try {
    if (form.editing) await updateOpsAssetApi(form.editing.assetId, form.data)
    else await createOpsAssetApi(projectId.value, form.data)
    form.open = false
    showSuccessToast('common.saved')
    void load()
  } catch (e) {
    form.error = (e as ApiError).message ?? t('common.saveFailed')
  } finally {
    form.saving = false
  }
}

const remove = async () => {
  if (!removeTarget.value) return
  try {
    await deleteOpsAssetApi(removeTarget.value.assetId)
    removeTarget.value = null
    void load()
  } catch {
    showErrorToast('common.deleteFailed')
  }
}

const ddayLabel = (a: OpsAsset) => {
  if (a.daysLeft === null) return '-'
  if (a.daysLeft < 0) return t('sm.asset.expiredAgo', { n: -a.daysLeft })
  return a.daysLeft === 0 ? 'D-DAY' : `D-${a.daysLeft}`
}
const levelColor: Record<string, string> = { expired: 'error', urgent: 'error', soon: 'warning', ok: 'success', none: 'secondary' }
const typeIcon: Record<OpsAssetType, string> = {
  SERVER: 'ri-server-line', ACCOUNT: 'ri-user-settings-line', CERT: 'ri-shield-check-line', LICENSE: 'ri-key-2-line', DOMAIN: 'ri-global-line',
}

// ─────────── computed ───────────
const canEdit = computed(() => auth.hasAnyRole(SM_EDIT_ROLES))
const rows = computed(() => list.value
  .filter((a) => !filter.type || a.type === filter.type)
  .filter((a) => !filter.q.trim() || `${a.name} ${a.host ?? ''} ${a.owner ?? ''}`.toLowerCase().includes(filter.q.trim().toLowerCase()))
  .filter((a) => !filter.expiringOnly || ['expired', 'urgent', 'soon'].includes(expiryLevel(a)))
  .sort((a, b) => (a.daysLeft ?? 99999) - (b.daysLeft ?? 99999)))
const counts = computed(() => ({
  expired: list.value.filter((a) => expiryLevel(a) === 'expired').length,
  urgent: list.value.filter((a) => expiryLevel(a) === 'urgent').length,
  soon: list.value.filter((a) => expiryLevel(a) === 'soon').length,
  total: list.value.length,
}))
const typeItems = computed(() => OPS_ASSET_TYPES.map((v) => ({ title: t(`sm.assetType.${v}`), value: v })))
const headers = computed(() => [
  { title: t('sm.asset.type'), key: 'type', width: 110 },
  { title: t('sm.asset.name'), key: 'name' },
  { title: t('sm.asset.host'), key: 'host', width: 160 },
  { title: t('sm.asset.owner'), key: 'owner', width: 100 },
  { title: t('sm.asset.expiresAt'), key: 'expiresAt', width: 110 },
  { title: 'D-day', key: 'daysLeft', width: 110 },
  { title: '', key: 'actions', width: 90, sortable: false, align: 'end' as const },
])
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.sm.asset')" :subtitle="t('sm.asset.subtitle')">
      <VBtn v-if="canEdit && projectId" color="primary" size="small" prepend-icon="ri-add-line" data-test="asset-add" @click="openForm(null)">{{ t('sm.asset.add') }}</VBtn>
    </AppPageHeader>
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <template v-else>
      <VRow class="mb-1">
        <VCol cols="6" sm="3"><VCard border flat class="pa-3"><div class="text-caption text-muted">{{ t('sm.asset.kpiExpired') }}</div><div class="text-h5 text-error">{{ counts.expired }}</div></VCard></VCol>
        <VCol cols="6" sm="3"><VCard border flat class="pa-3"><div class="text-caption text-muted">{{ t('sm.asset.kpiUrgent') }}</div><div class="text-h5 text-error">{{ counts.urgent }}</div></VCard></VCol>
        <VCol cols="6" sm="3"><VCard border flat class="pa-3"><div class="text-caption text-muted">{{ t('sm.asset.kpiSoon') }}</div><div class="text-h5 text-warning">{{ counts.soon }}</div></VCard></VCol>
        <VCol cols="6" sm="3"><VCard border flat class="pa-3"><div class="text-caption text-muted">{{ t('sm.asset.kpiTotal') }}</div><div class="text-h5">{{ counts.total }}</div></VCard></VCol>
      </VRow>
      <VCard border flat>
        <VCardText class="pb-0">
          <VRow dense align="center">
            <VCol cols="12" sm="5"><VTextField v-model="filter.q" :label="t('common.search')" prepend-inner-icon="ri-search-line" clearable hide-details /></VCol>
            <VCol cols="6" sm="3"><VSelect v-model="filter.type" :items="typeItems" :label="t('sm.asset.type')" clearable hide-details /></VCol>
            <VCol cols="6" sm="4"><VCheckbox v-model="filter.expiringOnly" :label="t('sm.asset.expiringOnly')" density="compact" hide-details /></VCol>
          </VRow>
        </VCardText>
        <VAlert v-if="error" type="error" variant="tonal" density="compact" class="ma-3">{{ error }}</VAlert>
        <VDataTable :headers="headers" :items="rows" :loading="loading" item-value="assetId" density="compact" :items-per-page="50" data-test="asset-table">
          <template #item.type="{ item }"><VIcon :icon="typeIcon[item.type]" size="16" class="mr-1" />{{ t(`sm.assetType.${item.type}`) }}</template>
          <template #item.host="{ item }"><span class="font-mono text-caption">{{ item.host ?? '-' }}</span></template>
          <template #item.owner="{ item }">{{ item.owner ?? '-' }}</template>
          <template #item.expiresAt="{ item }"><span class="font-mono text-caption">{{ item.expiresAt ?? '-' }}</span></template>
          <template #item.daysLeft="{ item }">
            <VChip v-if="item.daysLeft !== null" :color="levelColor[expiryLevel(item)]" size="small" :variant="['expired', 'urgent'].includes(expiryLevel(item)) ? 'flat' : 'tonal'" label class="font-mono" data-test="asset-dday">{{ ddayLabel(item) }}</VChip>
            <span v-else class="text-muted">-</span>
          </template>
          <template #item.actions="{ item }">
            <template v-if="canEdit">
              <VBtn icon="ri-edit-line" variant="text" size="x-small" :aria-label="t('common.edit')" @click="openForm(item)" />
              <VBtn icon="ri-delete-bin-line" variant="text" size="x-small" :aria-label="t('common.delete')" @click="removeTarget = item" />
            </template>
          </template>
        </VDataTable>
      </VCard>
    </template>

    <VDialog v-model="form.open" max-width="520">
      <VCard :title="form.editing ? t('sm.asset.edit') : t('sm.asset.add')">
        <VCardText>
          <VAlert v-if="form.error" type="error" variant="tonal" density="compact" class="mb-3">{{ form.error }}</VAlert>
          <VRow dense>
            <VCol cols="5"><VSelect v-model="form.data.type" :items="typeItems" :label="t('sm.asset.type')" /></VCol>
            <VCol cols="7"><VTextField v-model="form.data.name" :label="t('sm.asset.name')" data-test="asset-name" /></VCol>
            <VCol cols="12" sm="7"><VTextField :model-value="form.data.host ?? ''" :label="t('sm.asset.host')" @update:model-value="form.data.host = $event || null" /></VCol>
            <VCol cols="12" sm="5"><VTextField :model-value="form.data.owner ?? ''" :label="t('sm.asset.owner')" @update:model-value="form.data.owner = $event || null" /></VCol>
            <VCol cols="12" sm="6"><VTextField :model-value="form.data.expiresAt ?? ''" type="date" :label="t('sm.asset.expiresAt')" :hint="t('sm.asset.expiresHint')" persistent-hint @update:model-value="form.data.expiresAt = $event || null" /></VCol>
            <VCol cols="12"><VTextarea :model-value="form.data.note ?? ''" :label="t('sm.asset.note')" rows="2" auto-grow @update:model-value="form.data.note = $event || null" /></VCol>
          </VRow>
        </VCardText>
        <VCardActions>
          <VSpacer />
          <VBtn variant="text" @click="form.open = false">{{ t('common.cancel') }}</VBtn>
          <VBtn color="primary" :loading="form.saving" @click="save">{{ t('common.save') }}</VBtn>
        </VCardActions>
      </VCard>
    </VDialog>

    <ConfirmDialog
      :model-value="!!removeTarget"
      :title="t('common.delete')"
      :message="t('sm.asset.removeMessage', { name: removeTarget?.name ?? '' })"
      @update:model-value="(v: boolean) => { if (!v) removeTarget = null }"
      @confirm="remove"
    />
  </VContainer>
</template>
