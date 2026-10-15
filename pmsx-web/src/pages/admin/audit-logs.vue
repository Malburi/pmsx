<route lang="yaml">
meta:
  roles: [SYS_ADMIN, PMO, PM]
</route>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { getAuditLogsApi } from '@/api/adminApi'
import ExcelExportButton from '@/components/common/ExcelExportButton.vue'
import type { ApiError } from '@/types/api'
import { AUDIT_ACTIONS, type AuditAction, type AuditLog, type AuditLogQuery } from '@/types/common'
import { formatDateTime, toDateInput } from '@/utils/format'

const { t } = useI18n()

const today = () => toDateInput(new Date())
const daysAgo = (n: number) => toDateInput(new Date(Date.now() - n * 86_400_000))

// 입력 중인 필터와 실제 조회에 적용된 필터를 나눠, [조회] 를 눌러야 반영되게 한다
const filter = reactive({ from: daysAgo(7), to: today(), action: null as AuditAction | null, q: '' })
const applied = ref({ ...filter })
const page = ref(1)
const itemsPerPage = ref(50)
const logs = ref<AuditLog[]>([])
const total = ref(0)
const loading = ref(false)
const loadError = ref<string | null>(null)

// ─────────── 헬퍼 ───────────
const dateOrder = () => !filter.from || !filter.to || filter.from <= filter.to || t('validation.dateOrder')

const query = (): AuditLogQuery => ({
  // 기간 필터는 YYYY-MM-DD 날짜로 보낸다 (종료일 포함 여부는 서버가 그날 끝까지로 해석)
  from: applied.value.from || undefined,
  to: applied.value.to || undefined,
  action: applied.value.action ?? undefined,
  q: applied.value.q.trim() || undefined,
})

const load = async () => {
  loading.value = true
  loadError.value = null
  try {
    const res = await getAuditLogsApi({ ...query(), page: page.value - 1, size: itemsPerPage.value })
    logs.value = res.content
    total.value = res.totalElements
  } catch (e) {
    loadError.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}

const onOptions = (opts: { page: number; itemsPerPage: number }) => {
  page.value = opts.page
  itemsPerPage.value = opts.itemsPerPage
  void load()
}

const search = () => {
  if (dateOrder() !== true) return
  applied.value = { ...filter }
  page.value = 1
  void load()
}

const actionColor = (a: AuditAction) => {
  if (a === 'LOGIN_FAIL' || a === 'DELETE') return 'error'
  if (a === 'VIEW_SOURCE' || a === 'DOWNLOAD' || a === 'LICENSE') return 'warning'
  if (a === 'LOGIN' || a === 'LOGOUT') return 'info'
  return 'default'
}

// ─────────── computed ───────────
const actionItems = computed(() => AUDIT_ACTIONS.map((a) => ({ title: t(`admin.audit.actions.${a}`), value: a })))
const exportParams = computed(() => {
  const q = query()
  return { from: q.from, to: q.to, action: q.action, q: q.q }
})
const headers = computed(() => [
  { title: t('admin.audit.at'), key: 'at', width: 160 },
  { title: t('admin.audit.user'), key: 'loginId', width: 130 },
  { title: t('admin.audit.action'), key: 'action', width: 130 },
  { title: t('admin.audit.target'), key: 'target' },
  { title: t('admin.audit.detail'), key: 'detail' },
  { title: t('admin.audit.ip'), key: 'ip', width: 130 },
])
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <VRow>
      <VCol cols="12" md="12">
        <div class="d-flex flex-wrap align-center ga-3">
          <div>
            <h1 class="pmsx-page-title">{{ t('nav.admin.audit') }}</h1>
            <div class="text-body-2 text-muted">{{ t('admin.audit.subtitle') }}</div>
          </div>
          <VSpacer />
          <ExcelExportButton dataset-id="audit-logs" :file-name="t('admin.audit.exportName')" :params="exportParams" />
        </div>
      </VCol>

      <VCol cols="12" md="12">
        <VCard border flat>
          <VRow class="pa-3" dense>
            <VCol cols="12" sm="6" md="2">
              <VTextField v-model="filter.from" type="date" :label="t('admin.audit.from')" data-test="audit-from" />
            </VCol>
            <VCol cols="12" sm="6" md="2">
              <VTextField v-model="filter.to" type="date" :label="t('admin.audit.to')" :rules="[dateOrder]" data-test="audit-to" />
            </VCol>
            <VCol cols="12" sm="6" md="3">
              <VSelect v-model="filter.action" :items="actionItems" :label="t('admin.audit.action')" clearable data-test="audit-action" />
            </VCol>
            <VCol cols="12" sm="6" md="3">
              <VTextField v-model="filter.q" :label="t('admin.audit.q')" prepend-inner-icon="ri-search-line" clearable @keyup.enter="search" />
            </VCol>
            <VCol cols="12" md="2" class="d-flex align-center">
              <VBtn color="primary" variant="tonal" block prepend-icon="ri-search-line" data-test="audit-search" @click="search">{{ t('common.search') }}</VBtn>
            </VCol>
          </VRow>
          <VAlert v-if="loadError" type="error" variant="tonal" class="mx-3 mb-3">
            {{ loadError }}
            <VBtn variant="text" size="small" @click="load">{{ t('common.retry') }}</VBtn>
          </VAlert>
          <VDataTableServer
            :headers="headers"
            :items="logs"
            :items-length="total"
            :loading="loading"
            :items-per-page="itemsPerPage"
            :items-per-page-options="[20, 50, 100, 200]"
            :page="page"
            item-value="logId"
            density="compact"
            @update:options="onOptions"
          >
            <template #item.at="{ item }"><span class="text-no-wrap">{{ formatDateTime(item.at) }}</span></template>
            <template #item.loginId="{ item }"><span class="font-mono">{{ item.loginId ?? '-' }}</span></template>
            <template #item.action="{ item }">
              <VChip size="x-small" variant="tonal" :color="actionColor(item.action)">{{ t(`admin.audit.actions.${item.action}`) }}</VChip>
            </template>
            <template #item.target="{ item }"><span class="font-mono text-caption cell-wrap">{{ item.target ?? '-' }}</span></template>
            <template #item.detail="{ item }"><span class="text-caption cell-wrap">{{ item.detail ?? '' }}</span></template>
            <template #item.ip="{ item }"><span class="font-mono text-caption">{{ item.ip ?? '-' }}</span></template>
            <template #no-data>
              <div class="py-8 text-muted">{{ t('common.noData') }}</div>
            </template>
          </VDataTableServer>
        </VCard>
      </VCol>
    </VRow>
  </VContainer>
</template>

<style scoped>
.cell-wrap {
  word-break: break-all;
}
</style>
