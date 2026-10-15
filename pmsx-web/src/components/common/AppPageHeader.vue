<script setup lang="ts">
// 화면 제목 줄 — 큰 제목·부제 + 오른쪽 동작 버튼 슬롯 (클린 벤토: 제목 크고 굵게, 여백 넉넉히)
defineProps<{ title: string; subtitle?: string }>()
</script>

<template>
  <header class="d-flex flex-wrap align-end ga-3 mb-6">
    <div class="flex-grow-1 header-text">
      <h1 class="pmsx-page-title">{{ title }}</h1>
      <div v-if="subtitle" class="pmsx-page-subtitle">{{ subtitle }}</div>
    </div>
    <div class="d-flex flex-wrap align-center ga-2">
      <slot />
    </div>
  </header>
</template>

<style scoped>
.header-text {
  min-width: 0;
}
</style>
