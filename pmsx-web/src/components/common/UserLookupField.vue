<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { lookupUsersApi } from '@/api/userApi'
import type { UserRef } from '@/types/api'

// 담당자·검토자 지정용 사용자 검색 (docs/10 v0.2 `GET /users/lookup`)
const props = withDefaults(defineProps<{
  modelValue: string | null
  label: string
  /** 이미 지정된 사용자 — 처음 열 때 목록에 이름이 보이도록 */
  initial?: UserRef | null
  hint?: string
  clearable?: boolean
  dataTest?: string
}>(), { initial: null, hint: undefined, clearable: true, dataTest: undefined })
const emit = defineEmits<{ 'update:modelValue': [value: string | null] }>()

const { t } = useI18n()
const items = ref<{ title: string; value: string }[]>([])
const loading = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null
let seq = 0

// ─────────── 헬퍼 ───────────
const withInitial = (list: { title: string; value: string }[]) => {
  const init = props.initial
  if (init && !list.some((i) => i.value === init.userId)) return [{ title: init.name, value: init.userId }, ...list]
  return list
}

// 입력이 멈춘 뒤 300ms 후 조회 (forms-validation §1)
const search = (text: string | null) => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(async () => {
    const mine = ++seq
    loading.value = true
    try {
      const res = await lookupUsersApi(text ?? '')
      if (mine === seq) items.value = withInitial(res.map((u) => ({ title: `${u.name} (${u.loginId})`, value: u.userId })))
    } catch {
      if (mine === seq) items.value = withInitial([])
    } finally {
      if (mine === seq) loading.value = false
    }
  }, 300)
}

// ─────────── watch ───────────
watch(() => props.initial, () => { items.value = withInitial(items.value) }, { immediate: true })

// ─────────── 라이프사이클 ───────────
onBeforeUnmount(() => { if (timer) clearTimeout(timer) })
</script>

<template>
  <VAutocomplete
    :model-value="modelValue"
    :items="items"
    :label="label"
    :hint="hint"
    :persistent-hint="!!hint"
    :loading="loading"
    :clearable="clearable"
    :placeholder="t('common.userLookup.placeholder')"
    :no-data-text="t('common.userLookup.noResult')"
    no-filter
    prepend-inner-icon="ri-user-search-line"
    :data-test="dataTest"
    @update:search="search"
    @update:focused="(f: boolean) => f && items.length <= 1 && search('')"
    @update:model-value="(v: string | null) => emit('update:modelValue', v ?? null)"
  />
</template>
