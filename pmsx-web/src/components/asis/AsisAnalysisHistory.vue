<script setup lang="ts">
import { watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { getAnalysisHistoryApi } from '@/api/analysisApi'
import AsisStatusChip from '@/components/asis/AsisStatusChip.vue'
import { useApi } from '@/composables/useApi'
import type { AnalysisHistoryItem } from '@/types/api'
import { formatDateTime } from '@/utils/format'

// 화면 분석 상태 변경 이력 (FR-AS-022)
const props = defineProps<{ screenId: string; refreshKey?: number }>()

const { t } = useI18n()
const { data: items, loading, error, execute } = useApi<AnalysisHistoryItem[]>((id: string) => getAnalysisHistoryApi(id))

// ─────────── watch ───────────
watch(() => [props.screenId, props.refreshKey], () => { void execute(props.screenId) }, { immediate: true })
</script>

<template>
  <div>
    <div class="text-caption text-muted mb-2">{{ t('asis.history.title') }}</div>
    <VProgressLinear v-if="loading" indeterminate color="primary" />
    <VAlert v-else-if="error" type="warning" variant="tonal" density="compact">
      {{ error.message }}
      <VBtn variant="text" size="small" @click="execute(screenId)">{{ t('common.retry') }}</VBtn>
    </VAlert>
    <div v-else-if="!items?.length" class="text-body-2 text-muted">{{ t('asis.history.empty') }}</div>
    <VTimeline v-else density="compact" side="end" truncate-line="both" class="history" data-test="analysis-history">
      <VTimelineItem v-for="(h, i) in items" :key="i" size="x-small" dot-color="primary">
        <div class="d-flex flex-wrap align-center ga-1 text-body-2">
          <template v-if="h.fromStatus">
            <AsisStatusChip :status="h.fromStatus" />
            <VIcon icon="ri-arrow-right-line" size="14" />
          </template>
          <AsisStatusChip :status="h.toStatus" />
        </div>
        <div class="text-caption text-muted">{{ h.user.name }} · {{ formatDateTime(h.at) }}</div>
        <div v-if="h.comment" class="text-caption comment">{{ h.comment }}</div>
      </VTimelineItem>
    </VTimeline>
  </div>
</template>

<style scoped>
.history {
  margin-left: -8px;
}

.comment {
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
