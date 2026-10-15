<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { createSystemApi } from '@/api/projectApi'
import { useProjectStore } from '@/stores/project'
import type { ApiError, SystemCreate } from '@/types/api'
import { DB_TYPE_OPTIONS, SERVER_TECH_OPTIONS, UI_TECH_OPTIONS } from '@/utils/asis'
import { formatDateTime } from '@/utils/format'
import { showSuccessToast } from '@/utils/toast'

const { t } = useI18n()
const projectStore = useProjectStore()
const { systems, currentProjectId } = storeToRefs(projectStore)

const dialog = ref(false)
const formRef = ref<{ validate: () => Promise<{ valid: boolean }>; reset: () => void } | null>(null)
const saving = ref(false)
const formError = ref<string | null>(null)
const form = reactive<SystemCreate>({ code: '', name: '', uiTech: [], serverTech: [], dbType: '', testUrl: '' })

// ─────────── 헬퍼 ───────────
const required = (v: unknown) => (Array.isArray(v) ? v.length > 0 : !!String(v ?? '').trim()) || t('validation.required')
const codeFormat = (v: string) => /^[A-Z][A-Z0-9_]{1,19}$/.test(v) || t('validation.systemCode')
const dbTypeItems = computed(() => [{ title: t('asis.systems.autoDetect'), value: '' }, ...DB_TYPE_OPTIONS.map((v) => ({ title: v, value: v }))])
const urlFormat = (v: string) => !v || /^https?:\/\/\S+$/.test(v) || t('validation.url')

const openDialog = () => {
  Object.assign(form, { code: '', name: '', uiTech: [], serverTech: [], dbType: '', testUrl: '' })
  formError.value = null
  dialog.value = true
}

const save = async () => {
  const result = await formRef.value?.validate()
  if (!result?.valid || !currentProjectId.value) return
  saving.value = true
  formError.value = null
  try {
    const created = await createSystemApi(currentProjectId.value, { ...form, testUrl: form.testUrl || undefined })
    projectStore.upsertSystem(created)
    dialog.value = false
    showSuccessToast('asis.systems.created')
  } catch (e) {
    const err = e as ApiError
    formError.value = err.code === 'CONFLICT' ? t('asis.systems.conflict') : (err.message ?? t('common.error.unknown'))
  } finally {
    saving.value = false
  }
}

// ─────────── computed ───────────
const headers = computed(() => [
  { title: t('asis.systems.code'), key: 'code', width: 120 },
  { title: t('asis.systems.name'), key: 'name' },
  { title: t('asis.systems.tech'), key: 'tech', sortable: false },
  { title: t('asis.systems.dbType'), key: 'dbType', width: 110 },
  { title: t('asis.systems.progress'), key: 'progress', sortable: false, width: 180 },
  { title: t('asis.systems.lastRun'), key: 'lastRun', sortable: false, width: 190 },
  { title: '', key: 'actions', sortable: false, align: 'end' as const, width: 120 },
])
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <VRow>
      <VCol cols="12" md="12">
        <div class="d-flex flex-wrap align-center ga-3">
          <div>
            <h1 class="pmsx-page-title">{{ t('nav.asis.systems') }}</h1>
            <div class="text-body-2 text-muted">{{ t('asis.systems.subtitle') }}</div>
          </div>
          <VSpacer />
          <VBtn color="primary" prepend-icon="ri-add-line" :disabled="!currentProjectId" data-test="system-add" @click="openDialog">
            {{ t('asis.systems.add') }}
          </VBtn>
        </div>
      </VCol>
      <VCol cols="12" md="12">
        <VCard border flat>
          <VDataTable :headers="headers" :items="systems" item-value="systemId" density="comfortable" :items-per-page="20">
            <template #item.code="{ item }">
              <span class="font-mono font-weight-medium">{{ item.code }}</span>
            </template>
            <template #item.tech="{ item }">
              <div class="d-flex flex-wrap ga-1 py-1">
                <VChip v-for="ui in item.uiTech" :key="ui" size="x-small" variant="tonal" color="primary">{{ ui }}</VChip>
                <VChip v-for="sv in item.serverTech" :key="sv" size="x-small" variant="tonal">{{ sv }}</VChip>
                <VChip v-if="!item.uiTech.length && !item.serverTech.length" size="x-small" variant="tonal" color="info">
                  {{ t('asis.systems.autoDetectPending') }}
                </VChip>
              </div>
            </template>
            <template #item.progress="{ item }">
              <div class="text-caption">{{ t('asis.systems.doneOf', { done: item.doneCount, total: item.screenCount }) }}</div>
              <VProgressLinear
                :model-value="item.screenCount ? (item.doneCount / item.screenCount) * 100 : 0"
                color="status-done"
                height="6"
                rounded
              />
            </template>
            <template #item.lastRun="{ item }">
              <span v-if="item.lastRun" class="text-body-2">
                {{ t('asis.runs.seqLabel', { seq: item.lastRun.seq }) }} · {{ formatDateTime(item.lastRun.collectedAt) }}
              </span>
              <span v-else class="text-muted text-body-2">{{ t('asis.systems.noRun') }}</span>
            </template>
            <template #item.actions="{ item }">
              <VBtn
                icon="ri-eye-line"
                variant="text"
                size="small"
                :to="`/asis/viewer/${item.systemId}`"
                :aria-label="t('asis.systems.openViewer')"
                :title="t('asis.systems.openViewer')"
              />
              <VBtn
                icon="ri-upload-cloud-2-line"
                variant="text"
                size="small"
                :to="{ path: '/asis/runs', query: { systemId: item.systemId } }"
                :aria-label="t('asis.systems.openRuns')"
                :title="t('asis.systems.openRuns')"
              />
            </template>
            <template #no-data>
              <div class="py-8 text-muted">{{ t('asis.systems.empty') }}</div>
            </template>
          </VDataTable>
        </VCard>
      </VCol>
    </VRow>

    <VDialog v-model="dialog" max-width="560" persistent>
      <VCard :title="t('asis.systems.dialogTitle')">
        <VForm ref="formRef" @submit.prevent="save">
          <VCardText>
            <VRow>
              <VCol cols="12" sm="4">
                <VTextField
                  v-model="form.code"
                  :label="t('asis.systems.code')"
                  :rules="[required, codeFormat]"
                  :hint="t('asis.systems.codeHint')"
                  persistent-hint
                  data-test="system-code"
                  @update:model-value="(v: string) => (form.code = v.toUpperCase())"
                />
              </VCol>
              <VCol cols="12" sm="8">
                <VTextField v-model="form.name" :label="t('asis.systems.name')" :rules="[required]" data-test="system-name" />
              </VCol>
              <VCol cols="12" sm="6">
                <VSelect v-model="form.uiTech" :items="UI_TECH_OPTIONS" :label="t('asis.systems.uiTech')" :hint="t('asis.systems.autoDetectHint')" persistent-hint multiple chips closable-chips />
              </VCol>
              <VCol cols="12" sm="6">
                <VSelect v-model="form.serverTech" :items="SERVER_TECH_OPTIONS" :label="t('asis.systems.serverTech')" :hint="t('asis.systems.autoDetectHint')" persistent-hint multiple chips closable-chips />
              </VCol>
              <VCol cols="12" sm="6">
                <VSelect v-model="form.dbType" :items="dbTypeItems" :label="t('asis.systems.dbType')" :hint="t('asis.systems.autoDetectHint')" persistent-hint />
              </VCol>
              <VCol cols="12" sm="6">
                <VTextField v-model="form.testUrl" :label="t('asis.systems.testUrl')" :rules="[urlFormat]" placeholder="https://" />
              </VCol>
              <VCol v-if="formError" cols="12" md="12">
                <VAlert type="error" variant="tonal" density="compact" role="alert">{{ formError }}</VAlert>
              </VCol>
            </VRow>
          </VCardText>
          <VCardActions>
            <VSpacer />
            <VBtn variant="text" @click="dialog = false">{{ t('common.cancel') }}</VBtn>
            <VBtn type="submit" color="primary" :loading="saving">{{ t('common.save') }}</VBtn>
          </VCardActions>
        </VForm>
      </VCard>
    </VDialog>
  </VContainer>
</template>
