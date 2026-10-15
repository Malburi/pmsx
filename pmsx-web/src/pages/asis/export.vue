<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { getAnalysisScreensApi } from '@/api/analysisApi'
import { downloadDeliverableApi, getDeliverableTypesApi } from '@/api/deliverableApi'
import { getExcelTemplatesApi } from '@/api/excelApi'
import AsisSystemSelect from '@/components/asis/AsisSystemSelect.vue'
import { useApi } from '@/composables/useApi'
import { useProjectStore } from '@/stores/project'
import type { DeliverableFormat, DeliverableQuery, DeliverableType } from '@/types/api'
import type { ExcelTemplate } from '@/types/common'
import { saveBlob } from '@/utils/download'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

// SCR-AS-010 산출물 출력 (FR-AS-025)
const MAX_SCREENS = 300
// 화면 범위가 의미 있는 산출물 (테이블 정의서·인터페이스 목록은 시스템 전체)
const SCREEN_SCOPED = ['screen-spec', 'program-spec', 'crud-matrix']

const { t, te } = useI18n()
const { systems } = storeToRefs(useProjectStore())

const systemId = ref<string | null>(null)
const type = ref<string | null>(null)
const format = ref<DeliverableFormat>('xlsx')
const target = ref<'all' | 'done' | 'selected'>('done')
const screenIds = ref<string[]>([])
const screenOptions = ref<{ title: string; value: string }[]>([])
const templateId = ref<string | null>(null)
const templates = ref<ExcelTemplate[]>([])
const downloading = ref(false)
const lastError = ref<string | null>(null)
const { data: types, loading: typesLoading, error: typesError, execute: loadTypes } = useApi<DeliverableType[]>(
  (id: string) => getDeliverableTypesApi(id),
)

// ─────────── 헬퍼 ───────────
const loadScreens = async () => {
  if (!systemId.value) return
  try {
    const page = await getAnalysisScreensApi(systemId.value, { size: 200 })
    screenOptions.value = page.content.map((s) => ({ title: `${s.name} (${s.screenCode})`, value: s.screenId }))
  } catch {
    screenOptions.value = []
  }
}

const loadTemplates = async () => {
  try {
    templates.value = await getExcelTemplatesApi()
  } catch {
    templates.value = []
  }
}

const descOf = (tp: string) => (te(`asis.export.desc.${tp}`) ? t(`asis.export.desc.${tp}`) : '')

const download = async () => {
  if (!systemId.value || !selectedType.value) return
  const query: DeliverableQuery = { format: format.value }
  if (scoped.value) {
    if (target.value === 'done') query.status = 'DONE'
    if (target.value === 'selected') query.screenIds = screenIds.value.join(',')
  }
  if (format.value === 'xlsx' && templateId.value) query.templateId = templateId.value
  downloading.value = true
  lastError.value = null
  try {
    const blob = await downloadDeliverableApi(systemId.value, selectedType.value.type, query)
    const now = new Date()
    const stamp = `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}`
    saveBlob(blob, `${selectedType.value.name}_${systemCode.value}_${stamp}.${format.value}`)
    showSuccessToast('asis.export.done')
  } catch (e) {
    lastError.value = (e as { message?: string }).message ?? t('asis.export.failed')
    showErrorToast('asis.export.failed')
  } finally {
    downloading.value = false
  }
}

// ─────────── computed ───────────
const systemCode = computed(() => systems.value.find((s) => s.systemId === systemId.value)?.code ?? '')
const selectedType = computed(() => types.value?.find((d) => d.type === type.value) ?? null)
const scoped = computed(() => !!selectedType.value && SCREEN_SCOPED.includes(selectedType.value.type))
// 엑셀 템플릿은 산출물 종류와 같은 데이터셋(datasetId = 산출물 type)으로 등록된 것만 고른다
const templateItems = computed(() => [
  { title: t('asis.export.templateNone'), value: null },
  ...templates.value.filter((tp) => tp.datasetId === type.value).map((tp) => ({ title: tp.name, value: tp.templateId })),
])
const tooMany = computed(() => target.value === 'selected' && screenIds.value.length > MAX_SCREENS)
const canDownload = computed(() =>
  !!selectedType.value && !downloading.value && !tooMany.value && !(scoped.value && target.value === 'selected' && !screenIds.value.length))

// ─────────── watch ───────────
watch(systemId, (id) => {
  if (!id) return
  type.value = null
  if (!templates.value.length) void loadTemplates()
  void loadTypes(id).then((list) => { if (list?.length) type.value = list[0].type })
  void loadScreens()
})

watch(selectedType, (d) => {
  if (!d) return
  if (!d.formats.includes(format.value)) format.value = d.formats[0] ?? 'xlsx'
  templateId.value = null
})

watch(format, (f) => { if (f !== 'xlsx') templateId.value = null })
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <div class="d-flex flex-wrap align-center ga-3 mb-4">
      <div class="flex-grow-1">
        <h1 class="pmsx-page-title">{{ t('nav.asis.export') }}</h1>
        <div class="text-body-2 text-muted">{{ t('asis.export.subtitle') }}</div>
      </div>
      <AsisSystemSelect v-model="systemId" />
    </div>

    <VAlert v-if="!systemId" type="info" variant="tonal">{{ t('asis.selectSystemFirst') }}</VAlert>
    <VRow v-else>
      <VCol cols="12" md="7">
        <VCard border flat :title="t('asis.export.type')">
          <VCardText>
            <VProgressLinear v-if="typesLoading" indeterminate color="primary" />
            <VAlert v-else-if="typesError" type="error" variant="tonal" density="compact">{{ typesError.message }}</VAlert>
            <div v-else-if="!types?.length" class="text-body-2 text-muted">{{ t('asis.export.noTypes') }}</div>
            <VRadioGroup v-else v-model="type" hide-details data-test="export-types">
              <VCard
                v-for="d in types"
                :key="d.type"
                border
                flat
                class="type-card mb-2"
                :class="{ active: d.type === type }"
                @click="type = d.type"
              >
                <div class="d-flex align-start pa-3 ga-2">
                  <VRadio :value="d.type" class="flex-grow-0" :aria-label="d.name" />
                  <div class="flex-grow-1">
                    <div class="font-weight-medium">{{ d.name }}</div>
                    <div class="text-caption text-muted">{{ descOf(d.type) }}</div>
                  </div>
                  <div class="d-flex ga-1">
                    <VChip v-for="f in d.formats" :key="f" size="x-small" variant="outlined" label class="text-uppercase">{{ f }}</VChip>
                  </div>
                </div>
              </VCard>
            </VRadioGroup>
          </VCardText>
        </VCard>
      </VCol>
      <VCol cols="12" md="5">
        <VCard border flat :title="t('asis.export.download')">
          <VCardText>
            <div class="text-caption text-muted mb-1">{{ t('asis.export.format') }}</div>
            <VBtnToggle v-model="format" mandatory density="compact" variant="outlined" divided color="primary" class="mb-4">
              <VBtn v-for="f in selectedType?.formats ?? ['xlsx']" :key="f" :value="f" size="small" class="text-uppercase">{{ f }}</VBtn>
            </VBtnToggle>

            <template v-if="scoped">
              <div class="text-caption text-muted mb-1">{{ t('asis.export.target') }}</div>
              <VRadioGroup v-model="target" hide-details class="mb-2" data-test="export-target">
                <VRadio value="all" :label="t('asis.export.targetAll')" />
                <VRadio value="done" :label="t('asis.export.targetDone')" />
                <VRadio value="selected" :label="t('asis.export.targetSelected')" />
              </VRadioGroup>
              <VAutocomplete
                v-if="target === 'selected'"
                v-model="screenIds"
                :items="screenOptions"
                :label="t('asis.export.pickScreens')"
                multiple
                chips
                closable-chips
                clearable
                :error-messages="tooMany ? t('asis.export.tooManyScreens') : undefined"
                class="mb-2"
              />
              <div class="text-caption text-muted mb-4">{{ t('asis.export.limit') }}</div>
            </template>

            <VSelect
              v-if="format === 'xlsx'"
              v-model="templateId"
              :items="templateItems"
              :label="t('asis.export.template')"
              class="mb-2"
            />

            <VAlert v-if="downloading" type="info" variant="tonal" density="compact" class="mb-3">{{ t('asis.export.generating') }}</VAlert>
            <VAlert v-else-if="lastError" type="error" variant="tonal" density="compact" class="mb-3" role="alert">{{ lastError }}</VAlert>
            <VBtn
              color="primary"
              block
              prepend-icon="ri-download-2-line"
              :loading="downloading"
              :disabled="!canDownload"
              data-test="export-download"
              @click="download"
            >{{ t('asis.export.download') }}</VBtn>
          </VCardText>
        </VCard>
      </VCol>
    </VRow>
  </VContainer>
</template>

<style scoped>
.type-card {
  cursor: pointer;
}

.type-card.active {
  border-color: rgb(var(--v-theme-primary)) !important;
  background: rgba(var(--v-theme-primary), 0.05);
}
</style>
