<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { lookupUsersApi } from '@/api/userApi'
import type { UserRef } from '@/types/api'

// 여러 담당자 지정 (WBS 담당·회의 참석 등). 선택값은 이름까지 함께 가진 UserRef[] 로 주고받는다.
const props = withDefaults(defineProps<{ modelValue: UserRef[]; label: string; dataTest?: string }>(), { dataTest: undefined })
const emit = defineEmits<{ 'update:modelValue': [value: UserRef[]] }>()

const { t } = useI18n()
const found = ref<UserRef[]>([])
const loading = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null
let seq = 0

// ─────────── 헬퍼 ───────────
const search = (text: string | null) => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(async () => {
    const mine = ++seq
    loading.value = true
    try {
      const res = await lookupUsersApi(text ?? '')
      if (mine === seq) found.value = res.map((u) => ({ userId: u.userId, name: u.name }))
    } catch {
      if (mine === seq) found.value = []
    } finally {
      if (mine === seq) loading.value = false
    }
  }, 300)
}

const onUpdate = (ids: string[]) => {
  const pool = new Map([...props.modelValue, ...found.value].map((x) => [x.userId, x]))
  emit('update:modelValue', ids.map((id) => pool.get(id) ?? { userId: id, name: id }))
}

// ─────────── computed ───────────
const items = computed(() => {
  const map = new Map([...props.modelValue, ...found.value].map((x) => [x.userId, x]))
  return [...map.values()].map((x) => ({ title: x.name, value: x.userId }))
})
const ids = computed(() => props.modelValue.map((x) => x.userId))

// ─────────── 라이프사이클 ───────────
onBeforeUnmount(() => { if (timer) clearTimeout(timer) })
</script>

<template>
  <VAutocomplete
    :model-value="ids"
    :items="items"
    :label="label"
    :loading="loading"
    :placeholder="t('common.userLookup.placeholder')"
    :no-data-text="t('common.userLookup.noResult')"
    multiple
    chips
    closable-chips
    no-filter
    prepend-inner-icon="ri-user-search-line"
    :data-test="dataTest"
    @update:search="search"
    @update:focused="(f: boolean) => f && search('')"
    @update:model-value="onUpdate"
  />
</template>
