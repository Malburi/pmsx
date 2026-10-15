<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { getNotificationsApi, readAllNotificationsApi, readNotificationApi } from '@/api/dashboardApi'
import type { Notification, NotifyEvent } from '@/types/r2'
import { formatDateTime } from '@/utils/format'

// GNB 알림 벨 (FR-CM-007) — 미읽음 배지, 최근 목록, 모두 읽음. 1분마다 미읽음 수를 다시 읽는다.
const POLL_MS = 60_000
const ICONS: Record<NotifyEvent, string> = {
  ASSIGNED: 'ri-user-add-line', REVIEW_REQUESTED: 'ri-file-search-line', APPROVED: 'ri-checkbox-circle-line', REJECTED: 'ri-close-circle-line',
  DUE_SOON: 'ri-time-line', OVERDUE: 'ri-alarm-warning-line', DEFECT_ASSIGNED: 'ri-bug-line', ISSUE_ESCALATED: 'ri-arrow-up-circle-line',
  TIMESHEET_REJECTED: 'ri-calendar-close-line', MENTION: 'ri-at-line',
}

const { t } = useI18n()
const router = useRouter()
const items = ref<Notification[]>([])
const unread = ref(0)
const open = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

// ─────────── 헬퍼 ───────────
const refreshCount = async () => {
  try {
    unread.value = (await getNotificationsApi({ unread: true, size: 1 })).totalElements
  } catch { /* 알림 수 조회 실패는 화면 동작에 영향 없음 */ }
}

const loadList = async (isOpen: boolean) => {
  if (!isOpen) return
  try {
    items.value = (await getNotificationsApi({ size: 15 })).content
  } catch {
    items.value = []
  }
}

const go = async (n: Notification) => {
  open.value = false
  if (!n.read) {
    await readNotificationApi(n.notificationId).catch(() => {})
    n.read = true
    unread.value = Math.max(0, unread.value - 1)
  }
  if (n.link) await router.push(n.link)
}

const readAll = async () => {
  await readAllNotificationsApi().catch(() => {})
  items.value.forEach((n) => { n.read = true })
  unread.value = 0
}

// ─────────── 라이프사이클 ───────────
onMounted(() => {
  void refreshCount()
  timer = setInterval(refreshCount, POLL_MS)
})

onBeforeUnmount(() => { if (timer) clearInterval(timer) })
</script>

<template>
  <VMenu v-model="open" location="bottom end" :close-on-content-click="false" @update:model-value="loadList">
    <template #activator="{ props: mp }">
      <VBtn v-bind="mp" icon variant="text" size="small" :aria-label="t('notify.bell', { n: unread })" data-test="notify-bell">
        <VBadge :model-value="unread > 0" :content="unread > 99 ? '99+' : unread" color="error" floating>
          <VIcon icon="ri-notification-3-line" />
        </VBadge>
      </VBtn>
    </template>
    <VCard min-width="320" max-width="380">
      <div class="d-flex align-center px-4 py-2 border-b">
        <b class="text-body-2">{{ t('notify.title') }}</b>
        <VSpacer />
        <VBtn variant="text" size="x-small" :disabled="!unread" data-test="notify-read-all" @click="readAll">{{ t('notify.readAll') }}</VBtn>
        <VBtn variant="text" size="x-small" to="/admin/notifications" @click="open = false">{{ t('notify.settings') }}</VBtn>
      </div>
      <VList density="compact" max-height="420" class="py-0">
        <VListItem v-for="n in items" :key="n.notificationId" :class="{ unread: !n.read }" lines="two" @click="go(n)">
          <template #prepend><VIcon :icon="ICONS[n.event] ?? 'ri-notification-3-line'" size="18" :color="n.read ? undefined : 'primary'" /></template>
          <VListItemTitle class="text-body-2 font-weight-medium">{{ n.title }}</VListItemTitle>
          <VListItemSubtitle>{{ n.body }}</VListItemSubtitle>
          <div class="text-caption text-muted">{{ formatDateTime(n.createdAt) }}</div>
        </VListItem>
        <VListItem v-if="!items.length" :title="t('notify.empty')" disabled />
      </VList>
    </VCard>
  </VMenu>
</template>

<style scoped>
.unread {
  background: rgba(var(--v-theme-primary), 0.06);
}
</style>
