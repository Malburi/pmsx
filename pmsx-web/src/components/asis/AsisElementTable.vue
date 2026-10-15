<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ElementSummary } from '@/types/api'
import { typeColor } from '@/utils/asis'

const props = defineProps<{ elements: ElementSummary[]; selectedElementId: string | null; mode: 'components' | 'events' }>()
const emit = defineEmits<{ select: [elementId: string] }>()

const { t } = useI18n()

// ─────────── 헬퍼 ───────────
const rowProps = ({ item }: { item: { elementId: string } }) => ({
  class: item.elementId === props.selectedElementId ? 'row-sel' : '',
  tabindex: 0,
})

const onRowClick = (_e: Event, row: { item: { elementId: string } }) => emit('select', row.item.elementId)

// ─────────── computed ───────────
const componentHeaders = computed(() => [
  { title: '#', key: 'seqNo', width: 56 },
  { title: t('asis.viewer.table.elementId'), key: 'elementCode' },
  { title: t('asis.viewer.table.label'), key: 'label' },
  { title: t('asis.viewer.table.type'), key: 'type', width: 100 },
  { title: t('asis.viewer.table.binding'), key: 'binding' },
  { title: t('asis.viewer.table.events'), key: 'eventCount', width: 80, align: 'end' as const },
])

const eventHeaders = computed(() => [
  { title: '#', key: 'seqNo', width: 56 },
  { title: t('asis.viewer.table.label'), key: 'label' },
  { title: t('asis.viewer.table.event'), key: 'event' },
  { title: t('asis.viewer.table.handler'), key: 'handler' },
])

const componentRows = computed(() => props.elements.map((e) => ({ ...e, eventCount: e.events.length })))

const eventRows = computed(() =>
  props.elements.flatMap((e) => e.events.map((ev, i) => ({
    key: `${e.elementId}-${i}`, elementId: e.elementId, seqNo: e.seqNo, label: e.label, event: ev.event, handler: ev.handler,
  }))),
)
</script>

<template>
  <div class="px-4 pb-4 pt-3">
    <VDataTable
      v-if="mode === 'components'"
      :headers="componentHeaders"
      :items="componentRows"
      item-value="elementId"
      density="compact"
      :items-per-page="-1"
      hide-default-footer
      :row-props="rowProps"
      class="border rounded-lg"
      hover
      @click:row="onRowClick"
    >
      <template #item.elementCode="{ item }"><span class="font-mono">{{ item.elementCode }}</span></template>
      <template #item.label="{ item }">
        {{ item.label }}
        <VChip v-if="item.matched === 'dynamic-only'" size="x-small" color="warning" variant="tonal" class="ml-1">
          {{ t('asis.viewer.matched.dynamic-only') }}
        </VChip>
        <VChip v-else-if="item.matched === 'static-only'" size="x-small" variant="tonal" class="ml-1">
          {{ t('asis.viewer.matched.static-only') }}
        </VChip>
      </template>
      <template #item.type="{ item }">
        <VChip size="x-small" :color="typeColor(item.type)" variant="flat" label>{{ t(`asis.type.${item.type}`) }}</VChip>
      </template>
      <template #item.binding="{ item }"><span class="text-caption">{{ item.binding ?? '-' }}</span></template>
    </VDataTable>

    <VDataTable
      v-else
      :headers="eventHeaders"
      :items="eventRows"
      item-value="key"
      density="compact"
      :items-per-page="-1"
      hide-default-footer
      :row-props="rowProps"
      class="border rounded-lg"
      hover
      @click:row="onRowClick"
    >
      <template #item.handler="{ item }"><span class="font-mono">{{ item.handler }}</span></template>
      <template #no-data><div class="py-6 text-muted">{{ t('asis.viewer.table.noEvents') }}</div></template>
    </VDataTable>
  </div>
</template>

<style scoped>
:deep(.row-sel td) {
  background: rgba(var(--v-theme-primary), 0.1);
}

:deep(tbody tr) {
  cursor: pointer;
}
</style>
