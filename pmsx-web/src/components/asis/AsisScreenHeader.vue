<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import AsisStatusChip from '@/components/asis/AsisStatusChip.vue'
import type { AnalysisStatus, ScreenDetail } from '@/types/api'
import { STATUS_ORDER, statusColor } from '@/utils/asis'
import { formatDateTime } from '@/utils/format'

withDefaults(defineProps<{
  screen: ScreenDetail
  saving: boolean
  /** 바꿀 수 있는 상태 — 확정(DONE)은 PM·PL 만 (docs/06 각주 3) */
  statuses?: readonly AnalysisStatus[]
}>(), { statuses: () => STATUS_ORDER })
const emit = defineEmits<{ changeStatus: [status: AnalysisStatus] }>()

const { t } = useI18n()
</script>

<template>
  <div class="screen-head px-4 py-3 border-b border-line">
    <div class="d-flex flex-wrap align-center ga-2">
      <h2 class="text-subtitle-1 font-weight-bold mr-1">{{ screen.name }}</h2>
      <VChip size="small" variant="outlined" class="font-mono" label>{{ screen.screenCode }}</VChip>
      <VMenu location="bottom start">
        <template #activator="{ props: menuProps }">
          <button v-bind="menuProps" type="button" class="status-btn" :aria-label="t('asis.viewer.header.changeStatus')" data-test="status-menu">
            <AsisStatusChip :status="screen.analysis.status" />
            <VIcon icon="ri-arrow-down-s-line" size="16" />
          </button>
        </template>
        <VList density="compact">
          <VListSubheader>{{ t('asis.viewer.header.changeStatus') }}</VListSubheader>
          <VListItem
            v-for="s in STATUS_ORDER"
            :key="s"
            :active="s === screen.analysis.status"
            :disabled="saving || !statuses.includes(s)"
            :data-test="`status-item-${s}`"
            @click="emit('changeStatus', s)"
          >
            <template #prepend>
              <span class="dot mr-3" :class="`bg-${statusColor(s)}`" />
            </template>
            <VListItemTitle>{{ t(`asis.status.${s}`) }}</VListItemTitle>
          </VListItem>
        </VList>
      </VMenu>
      <VChip v-if="screen.kind !== 'page'" size="small" variant="tonal" color="type-popup">{{ t(`asis.kind.${screen.kind}`) }}</VChip>
      <VSpacer />
      <slot name="actions" />
    </div>
    <div class="meta text-caption text-muted mt-1">
      <span>{{ screen.menuPath.join(' › ') }}</span>
      <span>{{ screen.uiTech }}</span>
      <span v-if="screen.sourceFile" class="font-mono">{{ screen.sourceFile }}</span>
      <span>{{ t('asis.viewer.header.owner') }} {{ screen.analysis.owner?.name ?? t('asis.viewer.header.unassigned') }}</span>
      <span>{{ t('asis.viewer.header.reviewer') }} {{ screen.analysis.reviewer?.name ?? t('asis.viewer.header.unassigned') }}</span>
      <span v-if="screen.capture">
        {{ t('asis.viewer.header.captured', { seq: screen.capture.runSeq, at: formatDateTime(screen.capture.collectedAt) }) }}
        <template v-if="screen.capture.state === 'wireframe'"> · {{ t('asis.viewer.capture.wireframe') }}</template>
        <template v-else-if="screen.capture.state === 'rendered'"> · {{ t('asis.viewer.capture.rendered') }}</template>
      </span>
    </div>
  </div>
</template>

<style scoped>
.status-btn {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  background: none;
  border: 0;
  cursor: pointer;
  color: inherit;
}

.dot {
  display: inline-block;
  width: 9px;
  height: 9px;
  border-radius: 50%;
}

.meta {
  word-break: break-all;
  display: flex;
  flex-wrap: wrap;
  column-gap: 14px;
  row-gap: 2px;
}
</style>
