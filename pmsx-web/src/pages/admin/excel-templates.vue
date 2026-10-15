<route lang="yaml">
meta:
  roles: [SYS_ADMIN, PM]
</route>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  createExcelTemplateApi, deleteExcelTemplateApi, getExcelDatasetsApi, getExcelTemplatesApi, updateExcelTemplateApi,
} from '@/api/excelApi'
import { downloadFileApi } from '@/api/fileApi'
import ConfirmDialog from '@/components/common/ConfirmDialog.vue'
import type { ApiError } from '@/types/api'
import { TEMPLATE_COMMON_CELLS, type ExcelDataset, type ExcelTemplate, type ExcelTemplateMeta } from '@/types/common'
import { saveBlob } from '@/utils/download'
import { showErrorToast, showSuccessToast } from '@/utils/toast'
import { isCellRef, isColumnRef } from '@/utils/validators'

const { t } = useI18n()
const templates = ref<ExcelTemplate[]>([])
const datasets = ref<ExcelDataset[]>([])
const loading = ref(false)
const loadError = ref<string | null>(null)

const dialog = ref(false)
const editing = ref<ExcelTemplate | null>(null)
const formRef = ref<{ validate: () => Promise<{ valid: boolean }> } | null>(null)
const form = reactive({
  name: '',
  datasetId: '',
  sheet: 'Sheet1',
  startRow: 2,
  columns: {} as Record<string, string>,
  cells: {} as Record<string, string>,
})
const file = ref<File | File[] | null>(null)
const saving = ref(false)
const formError = ref<string | null>(null)
const deleteTarget = ref<ExcelTemplate | null>(null)
const deleting = ref(false)

// ─────────── 헬퍼 ───────────
const required = (v: unknown) => !!String(v ?? '').trim() || t('validation.required')
const columnRule = (v: string) => isColumnRef(v) || t('validation.columnRef')
const cellRule = (v: string) => isCellRef(v) || t('validation.cellRef')
const startRowRule = (v: number) => (Number.isInteger(Number(v)) && Number(v) >= 1) || t('validation.positiveInt')
const xlsxRule = (v: File | File[] | null) => {
  const f = Array.isArray(v) ? v[0] : v
  if (!f) return editing.value ? true : t('validation.required')
  return /\.xlsx$/i.test(f.name) || t('validation.xlsxOnly')
}
// 최소 한 개 필드는 열에 매핑되어야 내보낼 수 있다
const anyColumnMapped = () => Object.values(form.columns).some((v) => !!v) || t('admin.excel.needOneColumn')

const load = async () => {
  loading.value = true
  loadError.value = null
  try {
    const [tp, ds] = await Promise.all([getExcelTemplatesApi(), getExcelDatasetsApi()])
    templates.value = tp
    datasets.value = ds
  } catch (e) {
    loadError.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}

const datasetName = (id: string) => datasets.value.find((d) => d.datasetId === id)?.name ?? id

// 데이터셋을 바꾸면 그 데이터셋의 필드로 매핑 표를 다시 만든다 (기존 값은 같은 필드만 유지)
const onDatasetChange = (datasetId: string) => {
  const ds = datasets.value.find((d) => d.datasetId === datasetId)
  const prev = form.columns
  form.columns = Object.fromEntries((ds?.columns ?? []).map((c) => [c.field, prev[c.field] ?? '']))
}

const columnLetter = (index: number): string => {
  let n = index + 1
  let s = ''
  while (n > 0) {
    const r = (n - 1) % 26
    s = String.fromCharCode(65 + r) + s
    n = Math.floor((n - 1) / 26)
  }
  return s
}

const autoFillColumns = () => {
  const ds = selectedDataset.value
  if (!ds) return
  form.columns = Object.fromEntries(ds.columns.map((c, i) => [c.field, columnLetter(i)]))
}

const openDialog = (tp: ExcelTemplate | null) => {
  editing.value = tp
  file.value = null
  formError.value = null
  Object.assign(form, {
    name: tp?.name ?? '',
    datasetId: tp?.datasetId ?? datasets.value[0]?.datasetId ?? '',
    sheet: tp?.mapping.sheet ?? 'Sheet1',
    startRow: tp?.mapping.startRow ?? 2,
    columns: { ...(tp?.mapping.columns ?? {}) },
    cells: Object.fromEntries(TEMPLATE_COMMON_CELLS.map((k) => [k, tp?.mapping.cells?.[k] ?? ''])),
  })
  onDatasetChange(form.datasetId)
  dialog.value = true
}

const save = async () => {
  const result = await formRef.value?.validate()
  if (!result?.valid) return
  const picked = Array.isArray(file.value) ? file.value[0] ?? null : file.value
  const clean = (rec: Record<string, string>) => Object.fromEntries(Object.entries(rec).filter(([, v]) => !!v?.trim()).map(([k, v]) => [k, v.trim().toUpperCase()]))
  const cells = clean(form.cells)
  const meta: ExcelTemplateMeta = {
    name: form.name.trim(),
    datasetId: form.datasetId,
    mapping: { sheet: form.sheet.trim(), startRow: Number(form.startRow), columns: clean(form.columns), ...(Object.keys(cells).length ? { cells } : {}) },
  }
  saving.value = true
  formError.value = null
  try {
    if (editing.value) await updateExcelTemplateApi(editing.value.templateId, meta, picked)
    else if (picked) await createExcelTemplateApi(meta, picked)
    dialog.value = false
    showSuccessToast('admin.excel.saved')
    await load()
  } catch (e) {
    formError.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    saving.value = false
  }
}

const downloadTemplate = async (tp: ExcelTemplate) => {
  try {
    saveBlob(await downloadFileApi(tp.fileId), `${tp.name}.xlsx`)
  } catch {
    showErrorToast('common.attach.downloadFailed')
  }
}

const remove = async () => {
  if (!deleteTarget.value) return
  deleting.value = true
  try {
    await deleteExcelTemplateApi(deleteTarget.value.templateId)
    deleteTarget.value = null
    await load()
  } catch {
    showErrorToast('common.error.unknown')
  } finally {
    deleting.value = false
  }
}

// ─────────── computed ───────────
const selectedDataset = computed(() => datasets.value.find((d) => d.datasetId === form.datasetId) ?? null)
const datasetItems = computed(() => datasets.value.map((d) => ({ title: `${d.name} (${d.datasetId})`, value: d.datasetId })))
const headers = computed(() => [
  { title: t('admin.excel.name'), key: 'name' },
  { title: t('admin.excel.dataset'), key: 'datasetId', width: 220 },
  { title: t('admin.excel.sheet'), key: 'sheet', sortable: false, width: 120 },
  { title: t('admin.excel.startRow'), key: 'startRow', sortable: false, width: 90 },
  { title: t('admin.excel.mappedColumns'), key: 'mapped', sortable: false, width: 120 },
  { title: '', key: 'actions', sortable: false, align: 'end' as const, width: 140 },
])

// ─────────── 라이프사이클 ───────────
onMounted(load)
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <VRow>
      <VCol cols="12" md="12">
        <div class="d-flex flex-wrap align-center ga-3">
          <div>
            <h1 class="pmsx-page-title">{{ t('nav.admin.excel') }}</h1>
            <div class="text-body-2 text-muted">{{ t('admin.excel.subtitle') }}</div>
          </div>
          <VSpacer />
          <VBtn color="primary" prepend-icon="ri-add-line" :disabled="!datasets.length" data-test="template-add" @click="openDialog(null)">
            {{ t('admin.excel.add') }}
          </VBtn>
        </div>
      </VCol>
      <VCol cols="12" md="12">
        <VCard border flat>
          <VAlert v-if="loadError" type="error" variant="tonal" class="ma-3">
            {{ loadError }}
            <VBtn variant="text" size="small" @click="load">{{ t('common.retry') }}</VBtn>
          </VAlert>
          <VDataTable :headers="headers" :items="templates" :loading="loading" item-value="templateId" density="comfortable">
            <template #item.datasetId="{ item }">{{ datasetName(item.datasetId) }}</template>
            <template #item.sheet="{ item }"><span class="font-mono">{{ item.mapping.sheet }}</span></template>
            <template #item.startRow="{ item }">{{ item.mapping.startRow }}</template>
            <template #item.mapped="{ item }">{{ t('admin.excel.mappedCount', { n: Object.keys(item.mapping.columns).length }) }}</template>
            <template #item.actions="{ item }">
              <div class="d-flex justify-end flex-nowrap">
                <VBtn icon="ri-download-2-line" variant="text" size="small" :aria-label="t('admin.excel.downloadFile')" :title="t('admin.excel.downloadFile')" @click="downloadTemplate(item)" />
                <VBtn icon="ri-pencil-line" variant="text" size="small" :aria-label="t('common.edit')" :title="t('common.edit')" @click="openDialog(item)" />
                <VBtn icon="ri-delete-bin-line" variant="text" size="small" color="error" :aria-label="t('common.delete')" :title="t('common.delete')" @click="deleteTarget = item" />
              </div>
            </template>
            <template #no-data>
              <div class="py-8 text-muted">{{ t('admin.excel.empty') }}</div>
            </template>
          </VDataTable>
        </VCard>
      </VCol>
    </VRow>

    <VDialog v-model="dialog" max-width="820" persistent scrollable>
      <VCard :title="editing ? t('admin.excel.editTitle') : t('admin.excel.add')">
        <VForm ref="formRef" @submit.prevent="save">
          <VCardText>
            <VRow>
              <VCol cols="12" md="6">
                <VTextField v-model="form.name" :label="t('admin.excel.name')" :rules="[required]" data-test="template-name" />
              </VCol>
              <VCol cols="12" md="6">
                <VSelect
                  v-model="form.datasetId"
                  :items="datasetItems"
                  :label="t('admin.excel.dataset')"
                  :rules="[required]"
                  :disabled="!!editing"
                  @update:model-value="onDatasetChange"
                />
              </VCol>
              <VCol cols="12" md="12">
                <VFileInput
                  v-model="file"
                  accept=".xlsx"
                  :label="editing ? t('admin.excel.replaceFile') : t('admin.excel.file')"
                  :rules="[xlsxRule]"
                  prepend-icon=""
                  prepend-inner-icon="ri-file-excel-2-line"
                  show-size
                  data-test="template-file"
                />
              </VCol>
              <VCol cols="12" sm="6">
                <VTextField v-model="form.sheet" :label="t('admin.excel.sheet')" :rules="[required]" />
              </VCol>
              <VCol cols="12" sm="6">
                <VTextField v-model.number="form.startRow" type="number" :label="t('admin.excel.startRow')" :rules="[startRowRule]" :hint="t('admin.excel.startRowHint')" persistent-hint />
              </VCol>

              <VCol cols="12" md="12">
                <div class="d-flex align-center">
                  <div class="text-subtitle-2">{{ t('admin.excel.columnMapping') }}</div>
                  <VSpacer />
                  <VBtn size="small" variant="text" prepend-icon="ri-magic-line" @click="autoFillColumns">{{ t('admin.excel.autoFill') }}</VBtn>
                </div>
                <div class="text-caption text-muted mb-2">{{ t('admin.excel.columnMappingHint') }}</div>
                <VTable density="compact" class="border rounded-lg mapping-table">
                  <thead>
                    <tr>
                      <th>{{ t('admin.excel.field') }}</th>
                      <th>{{ t('admin.excel.header') }}</th>
                      <th class="col-ref">{{ t('admin.excel.columnRef') }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="c in selectedDataset?.columns ?? []" :key="c.field">
                      <td class="font-mono">{{ c.field }}</td>
                      <td>{{ c.header }}<span v-if="c.required" class="text-error"> *</span></td>
                      <td>
                        <VTextField
                          v-model="form.columns[c.field]"
                          variant="plain"
                          placeholder="B"
                          class="font-mono"
                          :rules="[columnRule]"
                          :data-test="`map-col-${c.field}`"
                          @update:model-value="(v: string) => (form.columns[c.field] = v.toUpperCase())"
                        />
                      </td>
                    </tr>
                  </tbody>
                </VTable>
                <VInput :model-value="form.columns" :rules="[anyColumnMapped]" class="mt-1" />
              </VCol>

              <VCol cols="12" md="12">
                <div class="text-subtitle-2">{{ t('admin.excel.cellMapping') }}</div>
                <div class="text-caption text-muted mb-2">{{ t('admin.excel.cellMappingHint') }}</div>
                <VRow>
                  <VCol v-for="k in TEMPLATE_COMMON_CELLS" :key="k" cols="12" sm="6" md="3">
                    <VTextField
                      v-model="form.cells[k]"
                      :label="t(`admin.excel.cells.${k}`)"
                      placeholder="C3"
                      class="font-mono"
                      :rules="[cellRule]"
                      @update:model-value="(v: string) => (form.cells[k] = v.toUpperCase())"
                    />
                  </VCol>
                </VRow>
              </VCol>

              <VCol v-if="formError" cols="12" md="12">
                <VAlert type="error" variant="tonal" density="compact" role="alert">{{ formError }}</VAlert>
              </VCol>
            </VRow>
          </VCardText>
          <VCardActions>
            <VSpacer />
            <VBtn variant="text" @click="dialog = false">{{ t('common.cancel') }}</VBtn>
            <VBtn type="submit" color="primary" :loading="saving" data-test="template-save">{{ t('common.save') }}</VBtn>
          </VCardActions>
        </VForm>
      </VCard>
    </VDialog>

    <ConfirmDialog
      :model-value="!!deleteTarget"
      :title="t('admin.excel.deleteTitle')"
      :message="t('admin.excel.deleteConfirm', { name: deleteTarget?.name ?? '' })"
      :confirm-text="t('common.delete')"
      :loading="deleting"
      @update:model-value="(v: boolean) => { if (!v) deleteTarget = null }"
      @confirm="remove"
    />
  </VContainer>
</template>

<style scoped>
.mapping-table .col-ref {
  width: 140px;
}

.mapping-table :deep(.v-field__input) {
  padding-top: 2px;
  padding-bottom: 2px;
  min-height: 30px;
}
</style>
