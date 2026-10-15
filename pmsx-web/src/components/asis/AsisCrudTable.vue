<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import AsisCrudBadges from '@/components/asis/AsisCrudBadges.vue'
import type { CrudMatrix } from '@/types/api'

defineProps<{ matrix: CrudMatrix | null; loading: boolean; selectedElementId: string | null }>()
const emit = defineEmits<{ select: [elementId: string] }>()

const { t } = useI18n()
</script>

<template>
  <div class="px-4 pb-4 pt-3">
    <VProgressLinear v-if="loading" indeterminate color="primary" />
    <div v-else-if="!matrix || matrix.rows.length === 0" class="py-6 text-body-2 text-muted">{{ t('asis.viewer.crud.empty') }}</div>
    <div v-else class="crud-scroll border rounded-lg">
      <VTable density="compact">
        <thead>
          <tr>
            <th>{{ t('asis.viewer.crud.corner') }}</th>
            <th v-for="tb in matrix.tables" :key="tb.nodeId" class="font-mono">{{ tb.name }}</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in matrix.rows"
            :key="row.elementId"
            :class="{ 'row-sel': row.elementId === selectedElementId }"
            tabindex="0"
            @click="emit('select', row.elementId)"
            @keydown.enter="emit('select', row.elementId)"
          >
            <td>{{ row.seqNo }}. {{ row.label }}</td>
            <td v-for="tb in matrix.tables" :key="tb.nodeId"><AsisCrudBadges :crud="row.cells[tb.nodeId]" /></td>
          </tr>
        </tbody>
      </VTable>
    </div>
  </div>
</template>

<style scoped>
.crud-scroll {
  overflow-x: auto;
}

tbody tr {
  cursor: pointer;
}

.row-sel td {
  background: rgba(var(--v-theme-primary), 0.1);
}
</style>
