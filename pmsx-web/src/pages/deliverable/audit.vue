<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { getAuditChecklistApi, getProjectDeliverablesApi, saveAuditChecklistApi } from '@/api/traceApi'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useAuthStore } from '@/stores/auth'
import type { ApiError } from '@/types/api'
import type { AuditChecklistItem, Deliverable } from '@/types/r2'
import { statusColorOf } from '@/utils/quality'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// SCR-DL-004 감리 체크리스트 (FR-DL-006) — 점검 항목 ↔ 산출물 매핑과 준비 상태
const { t } = useI18n()
const auth = useAuthStore()

const items = ref<AuditChecklistItem[]>([])
const savedJson = ref('[]')
const deliverables = ref<Deliverable[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref<string | null>(null)

const load = async (projectId: string) => {
  loading.value = true
  error.value = null
  try {
    const [list, dls] = await Promise.all([getAuditChecklistApi(projectId), getProjectDeliverablesApi(projectId)])
    items.value = list
    savedJson.value = JSON.stringify(list)
    deliverables.value = dls
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const { projectId } = useCurrentProject(load)

const save = async () => {
  if (!projectId.value) return
  saving.value = true
  try {
    items.value = await saveAuditChecklistApi(projectId.value, items.value)
    savedJson.value = JSON.stringify(items.value)
    showSuccessToast('common.saved')
  } catch {
    showErrorToast('common.saveFailed')
  } finally {
    saving.value = false
  }
}

const dl = (id: string) => deliverables.value.find((d) => d.deliverableId === id)

const canEdit = computed(() => auth.hasAnyRole(['PM', 'PL']))
const dirty = computed(() => JSON.stringify(items.value) !== savedJson.value)
const phases = computed(() => [...new Set(items.value.map((i) => i.phase))])
const statusItems = computed(() => (['READY', 'NOT_READY', 'NA'] as const).map((s) => ({ title: t(`dl.audit.${s}`), value: s })))
const dlItems = computed(() => deliverables.value.map((d) => ({ title: `${d.phase} · ${d.name}`, value: d.deliverableId })))
const readyRate = computed(() => {
  const scope = items.value.filter((i) => i.status !== 'NA')
  return scope.length ? Math.round((scope.filter((i) => i.status === 'READY').length / scope.length) * 100) : 0
})
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.deliverable.audit')" :subtitle="t('dl.audit.subtitle')">
      <template v-if="canEdit">
        <span v-if="dirty" class="text-caption text-warning">{{ t('pj.wbs.unsaved') }}</span>
        <VBtn size="small" color="primary" :disabled="!dirty" :loading="saving" data-test="audit-save" @click="save">{{ t('common.save') }}</VBtn>
      </template>
    </AppPageHeader>
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <template v-else>
      <VAlert v-if="error" type="error" variant="tonal" density="compact" class="mb-3">{{ error }}</VAlert>
      <VCard border flat class="mb-3 pa-4">
        <div class="d-flex align-center ga-3">
          <span class="text-body-2">{{ t('dl.audit.readyRate') }}</span>
          <VProgressLinear :model-value="readyRate" color="success" height="10" rounded class="flex-grow-1" />
          <b>{{ readyRate }}%</b>
        </div>
      </VCard>
      <VProgressLinear v-if="loading" indeterminate color="primary" class="mb-2" />
      <VCard v-for="ph in phases" :key="ph" border flat class="mb-3" :title="ph">
        <VTable density="compact">
          <thead>
            <tr><th>{{ t('dl.audit.item') }}</th><th class="dl-col">{{ t('dl.audit.deliverables') }}</th><th class="st-col">{{ t('dl.audit.status') }}</th><th>{{ t('dl.audit.note') }}</th></tr>
          </thead>
          <tbody>
            <tr v-for="it in items.filter((i) => i.phase === ph)" :key="it.itemId">
              <td class="text-body-2">{{ it.item }}</td>
              <td>
                <VAutocomplete v-if="canEdit" v-model="it.deliverableIds" :items="dlItems" multiple chips closable-chips density="compact" hide-details variant="plain" />
                <template v-else>
                  <VChip v-for="id in it.deliverableIds" :key="id" size="x-small" class="mr-1" :color="statusColorOf(dl(id)?.status)" variant="tonal">{{ dl(id)?.name ?? id }}</VChip>
                </template>
                <div class="text-caption text-muted">
                  <span v-for="id in it.deliverableIds" :key="`s-${id}`" class="mr-2">{{ dl(id)?.name }}: {{ dl(id) ? t(`dl.status.${dl(id)?.status}`) : '-' }}</span>
                </div>
              </td>
              <td>
                <VSelect v-if="canEdit" v-model="it.status" :items="statusItems" density="compact" hide-details variant="plain" />
                <VChip v-else size="small" :color="statusColorOf(it.status)" variant="tonal" label>{{ t(`dl.audit.${it.status}`) }}</VChip>
              </td>
              <td><input v-if="canEdit" v-model="it.note" class="note" :aria-label="t('dl.audit.note')"><span v-else class="text-caption">{{ it.note }}</span></td>
            </tr>
          </tbody>
        </VTable>
      </VCard>
    </template>
  </VContainer>
</template>

<style scoped>
.dl-col {
  min-width: 260px;
}

.st-col {
  width: 130px;
}

.note {
  width: 100%;
  min-width: 120px;
  border: 1px solid rgb(var(--v-theme-line));
  border-radius: 4px;
  padding: 3px 6px;
  font: inherit;
  color: inherit;
  background: transparent;
}

.note:focus {
  outline: none;
  border-color: rgb(var(--v-theme-primary));
}
</style>
