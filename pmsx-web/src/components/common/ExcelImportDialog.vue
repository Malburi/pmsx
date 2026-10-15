<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { commitExcelImportApi, getExcelDatasetsApi, validateExcelImportApi, type ExcelParams } from '@/api/excelApi'
import type { ApiError } from '@/types/api'
import type { ExcelDataset, ImportPreview, ImportPreviewRow, ImportResult } from '@/types/common'

const props = withDefaults(defineProps<{
  modelValue: boolean
  datasetId: string
  params?: ExcelParams
  title?: string
}>(), { params: () => ({}), title: undefined })

const emit = defineEmits<{ 'update:modelValue': [value: boolean]; committed: [result: ImportResult] }>()

const { t } = useI18n()
const file = ref<File | null>(null)
const dataset = ref<ExcelDataset | null>(null)
const preview = ref<ImportPreview | null>(null)
const result = ref<ImportResult | null>(null)
const validating = ref(false)
const committing = ref(false)
const errorMessage = ref<string | null>(null)
const errorsOnly = ref(false)

// ─────────── 헬퍼 ───────────
const xlsxOnly = (v: File | File[] | null) => {
  const f = Array.isArray(v) ? v[0] : v
  return !f || /\.xlsx?$/i.test(f.name) || t('validation.xlsxOnly')
}

const reset = () => {
  file.value = null
  preview.value = null
  result.value = null
  errorMessage.value = null
  errorsOnly.value = false
}

const close = () => emit('update:modelValue', false)

const loadDataset = async () => {
  if (dataset.value?.datasetId === props.datasetId) return
  try {
    dataset.value = (await getExcelDatasetsApi()).find((d) => d.datasetId === props.datasetId) ?? null
  } catch {
    dataset.value = null // 컬럼 정의 없이도 미리보기 값 키로 표를 그린다
  }
}

const validate = async () => {
  const f = Array.isArray(file.value) ? file.value[0] : file.value
  if (!f || xlsxOnly(f) !== true) return
  validating.value = true
  errorMessage.value = null
  try {
    preview.value = await validateExcelImportApi(props.datasetId, f, props.params)
  } catch (e) {
    errorMessage.value = (e as ApiError)?.message ?? t('common.error.unknown')
  } finally {
    validating.value = false
  }
}

const commit = async () => {
  if (!preview.value) return
  committing.value = true
  errorMessage.value = null
  try {
    result.value = await commitExcelImportApi(props.datasetId, preview.value.token)
    emit('committed', result.value)
  } catch (e) {
    const err = e as ApiError
    errorMessage.value = err?.code === 'NOT_FOUND' || err?.code === 'VALIDATION'
      ? t('common.excel.tokenExpired')
      : (err?.message ?? t('common.error.unknown'))
  } finally {
    committing.value = false
  }
}

const cellError = (row: ImportPreviewRow, field: string) => row.errors[field] ?? null

// ─────────── computed ───────────
const fields = computed<{ field: string; header: string; required: boolean }[]>(() => {
  if (dataset.value) return dataset.value.columns.map((c) => ({ field: c.field, header: c.header, required: c.required }))
  const keys = new Set<string>()
  preview.value?.rows.forEach((r) => Object.keys(r.values).forEach((k) => keys.add(k)))
  return [...keys].map((k) => ({ field: k, header: k, required: false }))
})

const headers = computed(() => [
  { title: t('common.excel.rowNo'), key: 'rowNo', width: 72 },
  ...fields.value.map((f) => ({ title: f.required ? `${f.header} *` : f.header, key: f.field, sortable: false })),
])

const rows = computed(() => {
  const list = preview.value?.rows ?? []
  return errorsOnly.value ? list.filter((r) => Object.keys(r.errors).length > 0) : list
})

// ─────────── watch ───────────
watch(() => props.modelValue, (open) => {
  if (!open) return
  reset()
  void loadDataset()
})
</script>

<template>
  <VDialog :model-value="modelValue" max-width="1100" scrollable persistent @update:model-value="(v: boolean) => emit('update:modelValue', v)">
    <VCard>
      <VCardTitle class="d-flex align-center">
        {{ title ?? t('common.excel.importTitle') }}
        <VSpacer />
        <VBtn icon="ri-close-line" variant="text" size="small" :aria-label="t('common.close')" @click="close" />
      </VCardTitle>
      <VDivider />
      <VCardText>
        <VRow>
          <VCol v-if="!result" cols="12" md="8">
            <VFileInput
              v-model="file"
              accept=".xlsx,.xls"
              :label="t('common.excel.file')"
              :rules="[xlsxOnly]"
              prepend-icon=""
              prepend-inner-icon="ri-file-excel-2-line"
              show-size
              data-test="import-file"
              @update:model-value="preview = null"
            />
          </VCol>
          <VCol v-if="!result" cols="12" md="4" class="d-flex align-center">
            <VBtn color="primary" variant="tonal" block prepend-icon="ri-check-double-line" :loading="validating" :disabled="!file" data-test="import-validate" @click="validate">
              {{ t('common.excel.validate') }}
            </VBtn>
          </VCol>

          <VCol v-if="errorMessage" cols="12" md="12">
            <VAlert type="error" variant="tonal" density="compact" role="alert">{{ errorMessage }}</VAlert>
          </VCol>

          <VCol v-if="result" cols="12" md="12">
            <VAlert type="success" variant="tonal" data-test="import-result">
              {{ t('common.excel.committed', { inserted: result.inserted, updated: result.updated }) }}
            </VAlert>
          </VCol>

          <template v-else-if="preview">
            <VCol cols="12" md="12">
              <div class="d-flex flex-wrap align-center ga-2">
                <VChip color="success" variant="tonal" size="small" data-test="import-valid">{{ t('common.excel.validCount', { n: preview.validCount }) }}</VChip>
                <VChip :color="preview.errorCount ? 'error' : 'default'" variant="tonal" size="small" data-test="import-errors">
                  {{ t('common.excel.errorCount', { n: preview.errorCount }) }}
                </VChip>
                <VSpacer />
                <VSwitch v-model="errorsOnly" :label="t('common.excel.errorsOnly')" color="error" density="compact" hide-details inset :disabled="!preview.errorCount" />
              </div>
              <VAlert v-if="preview.errorCount && preview.validCount" type="warning" variant="tonal" density="compact" class="mt-2">
                {{ t('common.excel.partialWarning', { error: preview.errorCount, valid: preview.validCount }) }}
              </VAlert>
            </VCol>
            <VCol cols="12" md="12">
              <VDataTable
                :headers="headers"
                :items="rows"
                item-value="rowNo"
                density="compact"
                :items-per-page="50"
                class="border rounded-lg import-table"
                fixed-header
                height="420"
              >
                <template v-for="f in fields" :key="f.field" #[`item.${f.field}`]="{ item }">
                  <span v-if="cellError(item, f.field)" class="cell-error" :title="cellError(item, f.field) ?? ''" data-test="import-cell-error">
                    {{ item.values[f.field] || '∅' }}
                    <VIcon icon="ri-error-warning-line" size="14" />
                    <VTooltip activator="parent" location="top">{{ cellError(item, f.field) }}</VTooltip>
                  </span>
                  <span v-else>{{ item.values[f.field] }}</span>
                </template>
              </VDataTable>
            </VCol>
          </template>
        </VRow>
      </VCardText>
      <VCardActions>
        <VSpacer />
        <VBtn variant="text" @click="close">{{ result ? t('common.close') : t('common.cancel') }}</VBtn>
        <VBtn
          v-if="!result"
          color="primary"
          :loading="committing"
          :disabled="!preview || preview.validCount === 0"
          data-test="import-commit"
          @click="commit"
        >
          {{ t('common.excel.commit', { n: preview?.validCount ?? 0 }) }}
        </VBtn>
      </VCardActions>
    </VCard>
  </VDialog>
</template>

<style scoped>
.cell-error {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 0 4px;
  border-radius: 4px;
  color: rgb(var(--v-theme-error));
  background: rgba(var(--v-theme-error), 0.1);
  cursor: help;
}
</style>
