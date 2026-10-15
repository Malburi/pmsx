<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useTheme } from 'vuetify'
import { useUiStore } from '@/stores/ui'

const { t } = useI18n()
const theme = useTheme()
const ui = useUiStore()
const { snackbar } = storeToRefs(ui)
const TOAST_ICON = { error: 'ri-error-warning-fill', success: 'ri-checkbox-circle-fill', info: 'ri-information-fill' } as const

// ─────────── 헬퍼 ───────────
const applyTheme = (name: string) => {
  theme.change(name)
  // 커스텀 CSS 가 [data-theme] 로 분기할 수 있게 루트에도 표시 (design-system §2-4)
  document.documentElement.dataset.theme = name
}

watch(() => ui.theme, applyTheme, { immediate: true })
</script>

<template>
  <VApp>
    <RouterView />
    <!-- 토스트: 하단 중앙 다크 pill + 상태 아이콘 (색만으로 구분하지 않도록 아이콘 병기, a11y) -->
    <VSnackbar
      v-model="snackbar.show"
      :timeout="4000"
      location="bottom"
      class="pmsx-toast"
      role="status"
      aria-live="polite"
    >
      <span class="d-inline-flex align-center ga-2">
        <VIcon :icon="TOAST_ICON[snackbar.color]" :color="snackbar.color" size="20" />
        {{ t(snackbar.messageKey) }}
      </span>
    </VSnackbar>
  </VApp>
</template>
