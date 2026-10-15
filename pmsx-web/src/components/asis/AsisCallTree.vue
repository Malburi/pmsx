<script setup lang="ts">
import { ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { getCallTreeApi } from '@/api/asisApi'
import AsisCallTreeItem from '@/components/asis/AsisCallTreeItem.vue'
import { useApi } from '@/composables/useApi'
import type { CallTreeNode } from '@/types/api'

// 함수 호출 트리 (FR-AS-010) — 아래로(호출 대상) / 위로(호출하는 곳)
const props = defineProps<{ nodeId: string }>()
const emit = defineEmits<{ select: [nodeId: string] }>()

const { t } = useI18n()
const direction = ref<'down' | 'up'>('down')
const depth = ref(3)
const { data: tree, loading, error, execute } = useApi<CallTreeNode>(
  (id: string, dir: 'down' | 'up', d: number) => getCallTreeApi(id, dir, d),
)

// ─────────── 헬퍼 ───────────
const load = () => execute(props.nodeId, direction.value, depth.value)

// ─────────── watch ───────────
watch(() => [props.nodeId, direction.value, depth.value], () => { void load() }, { immediate: true })
</script>

<template>
  <div class="call-tree">
    <div class="d-flex flex-wrap align-center ga-2 mb-2">
      <VBtnToggle v-model="direction" mandatory density="compact" variant="outlined" divided color="primary">
        <VBtn value="down" size="small" prepend-icon="ri-arrow-down-line">{{ t('asis.callTree.down') }}</VBtn>
        <VBtn value="up" size="small" prepend-icon="ri-arrow-up-line">{{ t('asis.callTree.up') }}</VBtn>
      </VBtnToggle>
      <VSelect
        v-model="depth"
        :items="[2, 3, 4, 5, 6].map((n) => ({ title: t('asis.callTree.depth', { n }), value: n }))"
        density="compact"
        hide-details
        class="depth-select"
      />
    </div>
    <VProgressLinear v-if="loading" indeterminate color="primary" />
    <VAlert v-else-if="error" type="warning" variant="tonal" density="compact">
      {{ error.message }}
      <VBtn variant="text" size="small" @click="load">{{ t('common.retry') }}</VBtn>
    </VAlert>
    <div v-else-if="!tree || tree.children.length === 0" class="text-body-2 text-muted">{{ t('asis.callTree.empty') }}</div>
    <ul v-else class="ct-root border rounded-lg pa-2" role="tree" :aria-label="t('asis.callTree.title')" data-test="call-tree">
      <AsisCallTreeItem :node="tree" :level="0" @select="(id: string) => emit('select', id)" />
    </ul>
  </div>
</template>

<style scoped>
.ct-root {
  list-style: none;
  margin: 0;
  max-height: 320px;
  overflow: auto;
}

.depth-select {
  max-width: 120px;
}
</style>
