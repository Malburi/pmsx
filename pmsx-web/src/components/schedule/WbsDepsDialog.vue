<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DepType, WbsDep, WbsItem } from '@/types/r2'
import { hasDepCycle } from '@/utils/wbs'

// 선후행 편집 (FS/SS/FF/SF + 지연일). 순환이 생기면 저장 버튼을 막는다.
const props = defineProps<{ modelValue: boolean; item: WbsItem | null; items: WbsItem[] }>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean]; save: [deps: WbsDep[]] }>()

const { t } = useI18n()
const deps = ref<WbsDep[]>([])
const DEP_TYPES: DepType[] = ['FS', 'SS', 'FF', 'SF']

// ─────────── 헬퍼 ───────────
const add = () => deps.value.push({ predId: '', type: 'FS', lagDays: 0 })
const remove = (i: number) => deps.value.splice(i, 1)
const close = () => emit('update:modelValue', false)
const save = () => {
  emit('save', deps.value.filter((d) => d.predId).map((d) => ({ ...d, lagDays: Number(d.lagDays) || 0 })))
  close()
}

// ─────────── computed ───────────
const candidates = computed(() => props.items
  .filter((i) => i.wbsId !== props.item?.wbsId)
  .map((i) => ({ title: `${i.wbsNo} ${i.name}`, value: i.wbsId })))
const typeItems = computed(() => DEP_TYPES.map((d) => ({ title: t(`pj.dep.${d}`), value: d })))
const cycle = computed(() => {
  if (!props.item) return false
  const next = props.items.map((i) => (i.wbsId === props.item?.wbsId ? { ...i, deps: deps.value.filter((d) => d.predId) } : i))
  return hasDepCycle(next)
})
const duplicate = computed(() => new Set(deps.value.filter((d) => d.predId).map((d) => d.predId)).size !== deps.value.filter((d) => d.predId).length)

// ─────────── watch ───────────
watch(() => props.modelValue, (open) => { if (open) deps.value = structuredClone(props.item?.deps ?? []) })
</script>

<template>
  <VDialog :model-value="modelValue" max-width="640" @update:model-value="emit('update:modelValue', $event)">
    <VCard :title="t('pj.wbs.depsTitle', { name: item ? `${item.wbsNo} ${item.name}` : '' })">
      <VCardText>
        <div v-if="!deps.length" class="text-body-2 text-muted mb-2">{{ t('pj.wbs.depsEmpty') }}</div>
        <VRow v-for="(d, i) in deps" :key="i" dense align="center">
          <VCol cols="12" sm="6">
            <VAutocomplete v-model="d.predId" :items="candidates" :label="t('pj.wbs.pred')" hide-details density="compact" />
          </VCol>
          <VCol cols="6" sm="3">
            <VSelect v-model="d.type" :items="typeItems" :label="t('pj.wbs.depType')" hide-details density="compact" />
          </VCol>
          <VCol cols="4" sm="2">
            <VTextField v-model.number="d.lagDays" type="number" :label="t('pj.wbs.lag')" hide-details density="compact" />
          </VCol>
          <VCol cols="2" sm="1">
            <VBtn icon variant="text" size="small" :aria-label="t('common.delete')" @click="remove(i)"><VIcon icon="ri-delete-bin-line" /></VBtn>
          </VCol>
        </VRow>
        <VBtn variant="tonal" size="small" prepend-icon="ri-add-line" class="mt-2" @click="add">{{ t('pj.wbs.addDep') }}</VBtn>
        <VAlert v-if="cycle" type="error" variant="tonal" density="compact" class="mt-3" role="alert">{{ t('pj.wbs.cycle') }}</VAlert>
        <VAlert v-else-if="duplicate" type="warning" variant="tonal" density="compact" class="mt-3">{{ t('pj.wbs.depDuplicate') }}</VAlert>
      </VCardText>
      <VCardActions>
        <VSpacer />
        <VBtn variant="text" @click="close">{{ t('common.cancel') }}</VBtn>
        <VBtn color="primary" :disabled="cycle || duplicate" data-test="deps-apply" @click="save">{{ t('common.apply') }}</VBtn>
      </VCardActions>
    </VCard>
  </VDialog>
</template>
