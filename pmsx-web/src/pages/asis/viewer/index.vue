<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useProjectStore } from '@/stores/project'

// 시스템을 고르지 않고 들어오면 현재 프로젝트의 첫 시스템 뷰어로 보낸다.
const { t } = useI18n()
const router = useRouter()
const projectStore = useProjectStore()
const { systems, loaded } = storeToRefs(projectStore)

// ─────────── 헬퍼 ───────────
const goFirst = () => {
  const first = systems.value.find((s) => s.lastRun) ?? systems.value[0]
  if (first) void router.replace(`/asis/viewer/${first.systemId}`)
}

watch(systems, goFirst)

// ─────────── 라이프사이클 ───────────
onMounted(goFirst)
</script>

<template>
  <VContainer fluid class="pa-6">
    <VRow>
      <VCol cols="12" md="12">
        <VProgressLinear v-if="!loaded" indeterminate color="primary" />
        <VAlert v-else-if="systems.length === 0" type="info" variant="tonal">
          {{ t('asis.viewer.noSystem') }}
          <template #append>
            <VBtn color="primary" variant="text" to="/asis/systems">{{ t('asis.systems.add') }}</VBtn>
          </template>
        </VAlert>
      </VCol>
    </VRow>
  </VContainer>
</template>
