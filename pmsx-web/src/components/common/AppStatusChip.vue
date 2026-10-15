<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { statusColorOf } from '@/utils/quality'

// 상태 값 → 색·라벨 칩. 라벨은 i18n `<prefix>.<status>` 키를 쓴다 (R2 대장 공통)
const props = withDefaults(defineProps<{ status: string | null | undefined; prefix: string; size?: 'x-small' | 'small' }>(), { size: 'small' })

const { t, te } = useI18n()
const label = computed(() => {
  if (!props.status) return '-'
  const key = `${props.prefix}.${props.status}`
  return te(key) ? t(key) : props.status
})
</script>

<template>
  <VChip :color="statusColorOf(status)" :size="size" variant="tonal" label class="font-weight-medium">{{ label }}</VChip>
</template>
