<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { exportExcelApi, getExcelTemplatesApi, type ExcelParams } from '@/api/excelApi'
import type { ExcelTemplate } from '@/types/common'
import { excelFileName, saveBlob } from '@/utils/download'
import { showErrorToast } from '@/utils/toast'

const props = withDefaults(defineProps<{
  datasetId: string
  /** 저장 파일명 앞부분 (예: '감사로그') */
  fileName: string
  params?: ExcelParams
  label?: string
  disabled?: boolean
}>(), { params: () => ({}), label: undefined, disabled: false })

const { t } = useI18n()
const templates = ref<ExcelTemplate[] | null>(null)
const loadingTemplates = ref(false)
const exporting = ref(false)

// ─────────── 헬퍼 ───────────
// 메뉴를 처음 열 때만 템플릿 목록을 읽는다
const loadTemplates = async (open: boolean) => {
  if (!open || templates.value) return
  loadingTemplates.value = true
  try {
    templates.value = (await getExcelTemplatesApi()).filter((tp) => tp.datasetId === props.datasetId)
  } catch {
    templates.value = []
  } finally {
    loadingTemplates.value = false
  }
}

const exportWith = async (template: ExcelTemplate | null) => {
  exporting.value = true
  try {
    const blob = await exportExcelApi(props.datasetId, props.params, template?.templateId)
    saveBlob(blob, excelFileName(template ? `${props.fileName}_${template.name}` : props.fileName))
  } catch {
    showErrorToast('common.excel.exportFailed')
  } finally {
    exporting.value = false
  }
}
</script>

<template>
  <VMenu location="bottom end" @update:model-value="loadTemplates">
    <template #activator="{ props: menuProps }">
      <VBtn
        v-bind="menuProps"
        variant="tonal"
        size="small"
        prepend-icon="ri-file-excel-2-line"
        append-icon="ri-arrow-down-s-line"
        :loading="exporting"
        :disabled="disabled"
        :data-test="`excel-export-${datasetId}`"
      >
        {{ label ?? t('common.excel.export') }}
      </VBtn>
    </template>
    <VList density="compact" min-width="220">
      <VListItem prepend-icon="ri-file-list-2-line" data-test="excel-export-default" @click="exportWith(null)">
        <VListItemTitle>{{ t('common.excel.defaultForm') }}</VListItemTitle>
      </VListItem>
      <VDivider v-if="loadingTemplates || templates?.length" />
      <VListSubheader v-if="templates?.length">{{ t('common.excel.customerTemplates') }}</VListSubheader>
      <VListItem v-if="loadingTemplates" :title="t('common.loading')" disabled />
      <VListItem v-for="tp in templates ?? []" :key="tp.templateId" prepend-icon="ri-file-excel-line" @click="exportWith(tp)">
        <VListItemTitle>{{ tp.name }}</VListItemTitle>
      </VListItem>
    </VList>
  </VMenu>
</template>
