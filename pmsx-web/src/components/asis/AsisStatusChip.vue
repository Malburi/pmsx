<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { AnalysisStatus } from '@/types/api'
import { statusColor } from '@/utils/asis'

defineProps<{ status: AnalysisStatus; size?: string }>()

const { t } = useI18n()
</script>

<template>
  <VChip :color="statusColor(status)" :size="size ?? 'small'" variant="tonal" label>
    {{ t(`asis.status.${status}`) }}
  </VChip>
</template>
