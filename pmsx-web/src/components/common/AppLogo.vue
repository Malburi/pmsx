<script setup lang="ts">
import { APP_NAME } from '@/themeConfig'

// 브랜드 마크 — 작은 그라데이션 타일 + 워드마크
withDefaults(defineProps<{ to?: string; compact?: boolean }>(), { to: '/', compact: false })
</script>

<template>
  <RouterLink :to="to" class="app-logo text-decoration-none" :aria-label="APP_NAME">
    <span class="app-logo__mark" aria-hidden="true">
      <svg viewBox="0 0 24 24" width="16" height="16">
        <rect x="4" y="4" width="7" height="7" rx="2" fill="#fff" />
        <rect x="13" y="4" width="7" height="7" rx="2" fill="#fff" opacity="0.65" />
        <rect x="4" y="13" width="7" height="7" rx="2" fill="#fff" opacity="0.65" />
        <rect x="13" y="13" width="7" height="7" rx="3.5" fill="#fff" />
      </svg>
    </span>
    <span v-if="!compact" class="app-logo__word">{{ APP_NAME }}</span>
  </RouterLink>
</template>

<style scoped>
.app-logo {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: rgb(var(--v-theme-on-surface));
}

.app-logo__mark {
  width: 28px;
  height: 28px;
  border-radius: 9px;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, #3182f6 0%, #6b8cff 45%, #2fd4b8 100%);
  box-shadow: 0 4px 12px rgba(49, 130, 246, 0.28);
  flex: none;
}

.app-logo__word {
  font-size: 1.0625rem;
  font-weight: 800;
  letter-spacing: -0.02em;
}
</style>
