<script setup lang="ts">
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { getRunDiffApi } from '@/api/runApi'
import { useApi } from '@/composables/useApi'
import type { DiffRef, RunDiffDetail, RunSummary } from '@/types/api'

// 수집 회차 변경 상세 (FR-AS-024)
const props = defineProps<{ run: RunSummary | null }>()
const emit = defineEmits<{ close: []; openScreen: [screenId: string] }>()

const { t, te } = useI18n()
const { data: diff, loading, error, execute } = useApi<RunDiffDetail>((id: string) => getRunDiffApi(id))

// ─────────── 헬퍼 ───────────
// 수집기 통계 키는 늘어날 수 있다 — 모르는 키는 원문 그대로
const statLabel = (k: string) => (te(`asis.runs.statKeys.${k}`) ? t(`asis.runs.statKeys.${k}`) : k)

// 화면 참조 클릭 (id 가 있는 = 아직 존재하는 화면만 열 수 있다)
const openRef = (r: DiffRef) => { if (r.id) emit('openScreen', r.id) }

// ─────────── computed ───────────
const groups = computed(() => {
  const d = diff.value
  if (!d) return []
  return [
    { key: 'screens', added: d.screens.added, removed: d.screens.removed, link: true },
    { key: 'elements', added: d.elements.added, removed: d.elements.removed, link: false },
  ]
})
const sampleHeaders = computed(() => [
  { title: t('asis.diff.colChange'), key: 'change', width: 72, sortable: false, nowrap: true },
  { title: t('asis.diff.colKind'), key: 'kind', width: 110, sortable: false },
  { title: t('asis.diff.colFrom'), key: 'from', sortable: false },
  { title: t('asis.diff.colTo'), key: 'to', sortable: false },
])

// ─────────── watch ───────────
watch(() => props.run?.runId, (id) => { if (id) void execute(id) }, { immediate: true })
</script>

<template>
  <VDialog :model-value="!!run" max-width="900" scrollable @update:model-value="(v: boolean) => { if (!v) emit('close') }">
    <VCard v-if="run" data-test="run-diff">
      <VCardTitle class="d-flex align-center">
        {{ t('asis.diff.title', { seq: run.seq }) }}
        <VSpacer />
        <VBtn icon="ri-close-line" variant="text" size="small" :aria-label="t('common.close')" @click="emit('close')" />
      </VCardTitle>
      <VDivider />
      <VCardText>
        <VProgressLinear v-if="loading" indeterminate color="primary" />
        <VAlert v-else-if="error" type="error" variant="tonal" density="compact">{{ t('asis.diff.loadFailed') }}</VAlert>
        <template v-else-if="diff">
          <div class="text-caption text-muted mb-2">{{ diff.prevRunId ? t('asis.diff.prev') : t('asis.diff.first') }}</div>
          <div class="d-flex flex-wrap ga-1 mb-4" data-test="run-stats">
            <VChip
              v-for="(v, k) in run.stats"
              :key="k"
              size="small"
              :color="k === 'warnings' && v > 0 ? 'warning' : k === 'screensWireframe' && v > 0 ? 'info' : undefined"
              variant="tonal"
            >{{ statLabel(String(k)) }} {{ v }}</VChip>
          </div>
          <div v-for="g in groups" :key="g.key" class="mb-4">
            <div class="text-subtitle-2 mb-1">{{ t(`asis.diff.${g.key}`) }}</div>
            <div v-if="!g.added.length && !g.removed.length" class="text-body-2 text-muted">{{ t('asis.diff.none') }}</div>
            <div v-if="g.added.length" class="mb-1">
              <span class="text-caption text-success mr-2">{{ t('asis.diff.added', { n: g.added.length }) }}</span>
              <VChip
                v-for="r in g.added"
                :key="r.key"
                size="small"
                color="success"
                variant="tonal"
                class="mr-1 mb-1"
                :disabled="g.link && !r.id"
                @click="g.link && openRef(r)"
              >{{ r.label }}</VChip>
            </div>
            <div v-if="g.removed.length">
              <span class="text-caption text-error mr-2">{{ t('asis.diff.removed', { n: g.removed.length }) }}</span>
              <VChip v-for="r in g.removed" :key="r.key" size="small" color="error" variant="tonal" class="mr-1 mb-1">{{ r.label }}</VChip>
            </div>
          </div>
          <div class="text-subtitle-2 mb-1">{{ t('asis.diff.edges') }}</div>
          <div class="text-body-2 mb-2">
            <span class="text-success">{{ t('asis.diff.added', { n: diff.edges.added }) }}</span>
            <span class="text-error ml-3">{{ t('asis.diff.removed', { n: diff.edges.removed }) }}</span>
          </div>
          <template v-if="diff.edges.samples.length">
            <div class="text-caption text-muted mb-1">{{ t('asis.diff.samples') }}</div>
            <VDataTable :headers="sampleHeaders" :items="diff.edges.samples" density="compact" :items-per-page="10" class="border rounded">
              <template #item.change="{ item }">
                <span :class="item.change === 'added' ? 'text-success' : 'text-error'">{{ item.change === 'added' ? '+' : '−' }}</span>
              </template>
              <template #item.from="{ item }"><span class="font-mono text-caption">{{ item.from }}</span></template>
              <template #item.to="{ item }"><span class="font-mono text-caption">{{ item.to }}</span></template>
            </VDataTable>
          </template>
        </template>
      </VCardText>
    </VCard>
  </VDialog>
</template>
