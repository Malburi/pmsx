<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { downloadDeliverableApi } from '@/api/deliverableApi'
import { getSubjectAreasApi, getTableApi, getTablesApi } from '@/api/tableApi'
import AsisSystemSelect from '@/components/asis/AsisSystemSelect.vue'
import AsisTableDetail from '@/components/asis/AsisTableDetail.vue'
import { useApi } from '@/composables/useApi'
import { useProjectStore } from '@/stores/project'
import type { ApiError, Relation, TableDetail, TableSummary } from '@/types/api'
import type { Page } from '@/types/common'
import { excelFileName, saveBlob } from '@/utils/download'
import { showErrorToast } from '@/utils/toast'

// SCR-AS-005 테이블 정의서 (FR-AS-017)
const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const { systems } = storeToRefs(useProjectStore())

const systemId = ref<string | null>(null)
const q = ref('')
const subjectArea = ref<string | null>(null)
const options = ref({ page: 1, itemsPerPage: 50 })
const list = ref<Page<TableSummary> | null>(null)
const listLoading = ref(false)
const listError = ref<string | null>(null)
const selectedId = ref<string | null>(typeof route.query.tableId === 'string' ? route.query.tableId : null)
const exporting = ref(false)
const { data: areas, execute: loadAreas } = useApi<{ name: string; tableCount: number }[]>((id: string) => getSubjectAreasApi(id))
const { data: detail, loading: detailLoading, error: detailError, execute: loadDetail } = useApi<TableDetail>((id: string) => getTableApi(id))
let qTimer: ReturnType<typeof setTimeout> | null = null

// ─────────── 헬퍼 ───────────
const loadList = async () => {
  if (!systemId.value) return
  listLoading.value = true
  listError.value = null
  try {
    list.value = await getTablesApi(systemId.value, {
      q: q.value.trim() || undefined,
      subjectArea: subjectArea.value ?? undefined,
      page: options.value.page - 1,
      size: options.value.itemsPerPage,
    })
  } catch (e) {
    listError.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    listLoading.value = false
  }
}

const onOptions = (o: { page: number; itemsPerPage: number }) => {
  options.value = { page: o.page, itemsPerPage: o.itemsPerPage }
  void loadList()
}

const selectTable = (id: string) => {
  selectedId.value = id
  void router.replace({ query: { ...route.query, tableId: id } })
}

const onRowClick = (_e: Event, payload: { item: TableSummary }) => selectTable(payload.item.tableId)

const rowProps = (payload: { item: TableSummary }) => ({ class: payload.item.tableId === selectedId.value ? 'row-sel' : '', tabindex: 0 })

const onUpdated = (summary: TableSummary) => {
  if (detail.value) Object.assign(detail.value, { subjectArea: summary.subjectArea, comment: summary.comment })
  const row = list.value?.content.find((r) => r.tableId === summary.tableId)
  if (row) Object.assign(row, summary)
  if (systemId.value) void loadAreas(systemId.value)
}

const onRelationChanged = (rel: Relation) => {
  if (detail.value) detail.value.relations = detail.value.relations.map((r) => (r.relationId === rel.relationId ? rel : r))
}

const openElement = (screenId: string, elementId: string | null) => {
  void router.push({ path: `/asis/viewer/${systemId.value}/${screenId}`, query: elementId ? { el: elementId } : {} })
}

const openErd = (tableId: string) => {
  void router.push({ path: '/asis/erd', query: { systemId: systemId.value ?? undefined, tableIds: tableId } })
}

// 테이블 정의서는 산출물(table-spec) 엑셀로 내려받는다 (docs/11 §5)
const exportSpec = async () => {
  if (!systemId.value) return
  exporting.value = true
  try {
    const blob = await downloadDeliverableApi(systemId.value, 'table-spec', { format: 'xlsx' })
    saveBlob(blob, excelFileName(t('asis.tables.exportName', { system: systemCode.value })))
  } catch {
    showErrorToast('asis.export.failed')
  } finally {
    exporting.value = false
  }
}

// ─────────── computed ───────────
const systemCode = computed(() => systems.value.find((s) => s.systemId === systemId.value)?.code ?? '')
const areaItems = computed(() => (areas.value ?? []).map((a) => ({ title: `${a.name} (${a.tableCount})`, value: a.name })))
const areaNames = computed(() => (areas.value ?? []).map((a) => a.name))
const headers = computed(() => [
  { title: t('asis.tables.colName'), key: 'name', sortable: false },
  { title: t('asis.tables.subjectArea'), key: 'subjectArea', sortable: false, width: 110 },
  { title: t('asis.tables.colColumns'), key: 'columnCount', sortable: false, align: 'end' as const, width: 70 },
  { title: t('asis.tables.colUsedBy'), key: 'usedByScreens', sortable: false, align: 'end' as const, width: 90 },
])

// ─────────── watch ───────────
watch(systemId, (id) => {
  if (!id) return
  options.value.page = 1
  void loadAreas(id)
  void loadList()
})

watch(subjectArea, () => { options.value.page = 1; void loadList() })

watch(q, () => {
  if (qTimer) clearTimeout(qTimer)
  qTimer = setTimeout(() => { options.value.page = 1; void loadList() }, 300)
})

watch(selectedId, (id) => { if (id) void loadDetail(id) }, { immediate: true })
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <div class="d-flex flex-wrap align-center ga-3 mb-4">
      <div class="flex-grow-1">
        <h1 class="pmsx-page-title">{{ t('nav.asis.tables') }}</h1>
        <div class="text-body-2 text-muted">{{ t('asis.tables.subtitle') }}</div>
      </div>
      <AsisSystemSelect v-model="systemId" />
      <VBtn
        variant="tonal"
        size="small"
        prepend-icon="ri-file-excel-2-line"
        :loading="exporting"
        :disabled="!systemId"
        data-test="tables-export"
        @click="exportSpec"
      >{{ t('asis.tables.export') }}</VBtn>
    </div>

    <VAlert v-if="!systemId" type="info" variant="tonal">{{ t('asis.selectSystemFirst') }}</VAlert>
    <VRow v-else>
      <VCol cols="12" lg="5">
        <VCard border flat>
          <VCardText class="pb-0">
            <VRow dense>
              <VCol cols="12" sm="7">
                <VTextField v-model="q" :label="t('asis.tables.q')" prepend-inner-icon="ri-search-line" clearable hide-details data-test="tables-q" />
              </VCol>
              <VCol cols="12" sm="5">
                <VSelect v-model="subjectArea" :items="areaItems" :label="t('asis.tables.subjectArea')" clearable hide-details />
              </VCol>
            </VRow>
          </VCardText>
          <VAlert v-if="listError" type="error" variant="tonal" density="compact" class="ma-3">{{ listError }}</VAlert>
          <VDataTableServer
            :headers="headers"
            :items="list?.content ?? []"
            :items-length="list?.totalElements ?? 0"
            :loading="listLoading"
            :page="options.page"
            :items-per-page="options.itemsPerPage"
            :items-per-page-options="[50, 100, 200]"
            item-value="tableId"
            density="compact"
            hover
            :row-props="rowProps"
            data-test="tables-grid"
            @update:options="onOptions"
            @click:row="onRowClick"
          >
            <template #item.name="{ item }">
              <div class="font-mono font-weight-medium">{{ item.name }}</div>
              <div class="text-caption text-muted text-truncate comment">{{ item.comment ?? '' }}</div>
            </template>
            <template #item.subjectArea="{ item }">
              <span class="text-caption">{{ item.subjectArea ?? t('asis.tables.noSubjectArea') }}</span>
            </template>
          </VDataTableServer>
        </VCard>
      </VCol>
      <VCol cols="12" lg="7">
        <VCard border flat class="pa-4 detail-card">
          <VProgressLinear v-if="detailLoading" indeterminate color="primary" />
          <VAlert v-else-if="detailError" type="error" variant="tonal" density="compact">{{ detailError.message }}</VAlert>
          <div v-else-if="!detail || !selectedId" class="text-body-2 text-muted py-8 text-center">{{ t('asis.tables.selectHint') }}</div>
          <AsisTableDetail
            v-else
            :table="detail"
            :system-id="systemId"
            :subject-areas="areaNames"
            @updated="onUpdated"
            @relation-changed="onRelationChanged"
            @open-table="selectTable"
            @open-element="openElement"
            @open-erd="openErd"
          />
        </VCard>
      </VCol>
    </VRow>
  </VContainer>
</template>

<style scoped>
.comment {
  max-width: 320px;
}

.detail-card {
  min-height: 320px;
}

:deep(.row-sel) td {
  background: rgba(var(--v-theme-primary), 0.1);
}
</style>
