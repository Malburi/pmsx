<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useProjectStore } from '@/stores/project'

// As-Is 시스템 단위 화면(진척·테이블·ERD·CRUD·이동 맵·산출물) 공통 시스템 선택 — URL ?systemId= 와 동기화
const props = defineProps<{ modelValue: string | null }>()
const emit = defineEmits<{ 'update:modelValue': [systemId: string | null] }>()

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const { systems } = storeToRefs(useProjectStore())

// ─────────── 헬퍼 ───────────
const select = (id: string | null) => {
  emit('update:modelValue', id)
  void router.replace({ query: { ...route.query, systemId: id ?? undefined } })
}

// ─────────── computed ───────────
const items = computed(() => systems.value.map((s) => ({ title: `${s.name} (${s.code})`, value: s.systemId })))

// ─────────── watch ───────────
// 쿼리 → 목록의 첫 시스템 순으로 기본값을 정한다
watch(systems, (list) => {
  if (props.modelValue && list.some((s) => s.systemId === props.modelValue)) return
  const fromQuery = typeof route.query.systemId === 'string' ? route.query.systemId : null
  const next = list.find((s) => s.systemId === fromQuery)?.systemId ?? list[0]?.systemId ?? null
  if (next !== props.modelValue) select(next)
}, { immediate: true })
</script>

<template>
  <VSelect
    :model-value="modelValue"
    :items="items"
    :label="t('asis.systemSelect')"
    prepend-inner-icon="ri-server-line"
    hide-details
    class="system-select"
    data-test="asis-system-select"
    @update:model-value="select"
  />
</template>

<style scoped>
.system-select {
  min-width: 200px;
  max-width: 320px;
}
</style>
