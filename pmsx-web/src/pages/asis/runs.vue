<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { getRunReportApi, getRunsApi, uploadRunApi } from '@/api/runApi'
import AsisRunDiffDialog from '@/components/asis/AsisRunDiffDialog.vue'
import { useApi } from '@/composables/useApi'
import { useElementWidth } from '@/composables/useElementWidth'
import { useProjectStore } from '@/stores/project'
import type { ApiError, RunDiff, RunSummary } from '@/types/api'
import { fitColumns } from '@/utils/columns'
import { formatDateTime } from '@/utils/format'
import { showErrorToast, showSuccessToast } from '@/utils/toast'

const { t, te } = useI18n()
const route = useRoute()
const router = useRouter()
const projectStore = useProjectStore()
const { systems } = storeToRefs(projectStore)

const systemId = ref<string | null>(typeof route.query.systemId === 'string' ? route.query.systemId : null)
const file = ref<File | File[] | null>(null)
const uploading = ref(false)
const progress = ref(0)
const uploadError = ref<string | null>(null)
const report = ref<{ seq: number; html: string; loading: boolean } | null>(null)
const diffRun = ref<RunSummary | null>(null)
const { data: runs, loading, error, execute: loadRuns } = useApi((id: string) => getRunsApi(id))

// ─────────── 헬퍼 ───────────
const zipOnly = (v: File | File[] | null) => {
  const f = Array.isArray(v) ? v[0] : v
  return !f || f.name.toLowerCase().endsWith('.zip') || t('validation.zipOnly')
}

const pickedFile = (): File | null => (Array.isArray(file.value) ? file.value[0] ?? null : file.value)

const upload = async () => {
  const f = pickedFile()
  if (!systemId.value || !f || zipOnly(f) !== true) return
  uploading.value = true
  progress.value = 0
  uploadError.value = null
  try {
    await uploadRunApi(systemId.value, f, (r) => { progress.value = Math.round(r * 100) })
    file.value = null
    showSuccessToast('asis.runs.uploaded')
    await Promise.all([loadRuns(systemId.value), projectStore.loadSystems()])
  } catch (e) {
    const err = e as ApiError
    uploadError.value = err.code === 'SYSTEM_MISMATCH' ? t('asis.runs.mismatch') : (err.message ?? t('common.error.unknown'))
  } finally {
    uploading.value = false
  }
}

// 리포트 HTML 은 수집 결과물(외부 입력)이므로 같은 오리진에서 열지 않고,
// allow-same-origin 없는 sandbox iframe 에 넣어 쿠키·스토리지에 접근하지 못하게 한다.
const openReport = async (run: RunSummary) => {
  report.value = { seq: run.seq, html: '', loading: true }
  try {
    const blob = await getRunReportApi(run.runId)
    report.value = { seq: run.seq, html: await blob.text(), loading: false }
  } catch {
    report.value = null
    showErrorToast('asis.runs.reportFailed')
  }
}

// 수집기 통계 키는 확장될 수 있으므로 모르는 키는 원문 그대로 표시한다
const statLabel = (k: string) => (te(`asis.runs.statKeys.${k}`) ? t(`asis.runs.statKeys.${k}`) : k)

const diffItems = (d: RunDiff) => [
  { key: 'screens', add: d.screensAdded, del: d.screensRemoved },
  { key: 'elements', add: d.elementsAdded, del: d.elementsRemoved },
  { key: 'edges', add: d.edgesAdded, del: d.edgesRemoved },
]

// ─────────── computed ───────────
const systemItems = computed(() => systems.value.map((s) => ({ title: `${s.name} (${s.code})`, value: s.systemId })))
// 2단 화면에서 목록 자리가 좁으면 업로드 일시 열을 숨긴다
const listCard = ref<HTMLElement | { $el: HTMLElement } | null>(null)
const listWidth = useElementWidth(listCard, 900)
const allHeaders = computed(() => [
  { title: t('asis.runs.seq'), key: 'seq', width: 72 },
  { title: t('asis.runs.collectedAt'), key: 'collectedAt', width: 150 },
  { title: t('asis.runs.uploadedAt'), key: 'uploadedAt', width: 150, hideBelow: 900 },
  { title: t('asis.runs.stats'), key: 'stats', sortable: false, minWidth: 220 },
  { title: t('asis.runs.diff'), key: 'diff', sortable: false, minWidth: 150, hideBelow: 700 },
  { title: '', key: 'actions', sortable: false, align: 'end' as const, width: 184 },
])
const headers = computed(() => fitColumns(allHeaders.value, listWidth.value))

// ─────────── watch ───────────
watch(systems, (list) => {
  if (!systemId.value && list.length) systemId.value = list[0].systemId
}, { immediate: true })

watch(systemId, (id) => {
  if (!id) return
  void router.replace({ query: { ...route.query, systemId: id } })
  void loadRuns(id)
}, { immediate: true })
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <VRow>
      <VCol cols="12" md="12">
        <h1 class="pmsx-page-title">{{ t('nav.asis.runs') }}</h1>
        <div class="text-body-2 text-muted">{{ t('asis.runs.subtitle') }}</div>
      </VCol>

      <VCol cols="12" lg="4">
        <VCard border flat :title="t('asis.runs.uploadTitle')">
          <VCardText>
            <VRow>
              <VCol cols="12" md="12">
                <VSelect v-model="systemId" :items="systemItems" :label="t('asis.runs.system')" data-test="runs-system" />
              </VCol>
              <VCol cols="12" md="12">
                <VFileInput
                  v-model="file"
                  accept=".zip,application/zip"
                  :label="t('asis.runs.file')"
                  :rules="[zipOnly]"
                  prepend-icon=""
                  prepend-inner-icon="ri-file-zip-line"
                  show-size
                  data-test="runs-file"
                />
              </VCol>
              <VCol cols="12" md="12">
                <div class="text-caption text-muted">{{ t('asis.runs.packageHint') }}</div>
              </VCol>
              <VCol v-if="uploading" cols="12" md="12">
                <VProgressLinear :model-value="progress" color="primary" height="8" rounded />
              </VCol>
              <VCol v-if="uploadError" cols="12" md="12">
                <VAlert type="error" variant="tonal" density="compact" role="alert">{{ uploadError }}</VAlert>
              </VCol>
              <VCol cols="12" md="12">
                <VBtn
                  color="primary"
                  block
                  prepend-icon="ri-upload-cloud-2-line"
                  :loading="uploading"
                  :disabled="!systemId || !pickedFile()"
                  data-test="runs-upload"
                  @click="upload"
                >
                  {{ t('asis.runs.uploadBtn') }}
                </VBtn>
              </VCol>
            </VRow>
          </VCardText>
        </VCard>
      </VCol>

      <VCol cols="12" lg="8">
        <VCard ref="listCard" border flat :title="t('asis.runs.listTitle')">
          <VAlert v-if="error" type="error" variant="tonal" class="ma-4">
            {{ error.message }}
            <VBtn variant="text" size="small" @click="systemId && loadRuns(systemId)">{{ t('common.retry') }}</VBtn>
          </VAlert>
          <VDataTable :headers="headers" :items="runs ?? []" :loading="loading" item-value="runId" density="comfortable">
            <template #item.seq="{ item }">
              <VChip size="small" variant="tonal" color="primary">{{ t('asis.runs.seqLabel', { seq: item.seq }) }}</VChip>
            </template>
            <template #item.collectedAt="{ item }">{{ formatDateTime(item.collectedAt) }}</template>
            <template #item.uploadedAt="{ item }">{{ formatDateTime(item.uploadedAt) }}</template>
            <template #item.stats="{ item }">
              <div class="d-flex flex-wrap ga-1 py-1">
                <VChip v-for="(v, k) in item.stats" :key="k" size="x-small" variant="outlined">{{ statLabel(String(k)) }} {{ v }}</VChip>
              </div>
            </template>
            <template #item.diff="{ item }">
              <div class="d-flex flex-column py-1 text-caption">
                <span v-for="d in diffItems(item.diff)" :key="d.key" class="text-no-wrap">
                  {{ t(`asis.runs.diffKind.${d.key}`) }}
                  <span class="text-success">+{{ d.add }}</span>
                  <span class="text-error ml-1">−{{ d.del }}</span>
                </span>
              </div>
            </template>
            <template #item.actions="{ item }">
              <VBtn variant="text" size="small" prepend-icon="ri-git-commit-line" :data-test="`run-diff-${item.seq}`" @click="diffRun = item">
                {{ t('asis.diff.detail') }}
              </VBtn>
              <VBtn variant="text" size="small" prepend-icon="ri-file-chart-line" @click="openReport(item)">
                {{ t('asis.runs.report') }}
              </VBtn>
            </template>
            <template #no-data>
              <div class="py-8 text-muted">{{ t('asis.runs.empty') }}</div>
            </template>
          </VDataTable>
        </VCard>
      </VCol>
    </VRow>

    <AsisRunDiffDialog
      :run="diffRun"
      @close="diffRun = null"
      @open-screen="(id: string) => router.push(`/asis/viewer/${systemId}/${id}`)"
    />

    <VDialog :model-value="!!report" max-width="1100" scrollable @update:model-value="(v: boolean) => { if (!v) report = null }">
      <VCard v-if="report">
        <VCardTitle class="d-flex align-center">
          {{ t('asis.runs.reportTitle', { seq: report.seq }) }}
          <VSpacer />
          <VBtn icon="ri-close-line" variant="text" size="small" :aria-label="t('common.close')" @click="report = null" />
        </VCardTitle>
        <VDivider />
        <VProgressLinear v-if="report.loading" indeterminate color="primary" />
        <iframe
          v-else
          class="report-frame"
          sandbox="allow-scripts"
          referrerpolicy="no-referrer"
          :srcdoc="report.html"
          :title="t('asis.runs.reportTitle', { seq: report.seq })"
        />
      </VCard>
    </VDialog>
  </VContainer>
</template>

<style scoped>
.report-frame {
  width: 100%;
  height: 72vh;
  border: 0;
  background: #fff;
}
</style>
