<script setup lang="ts">
import type { EChartsCoreOption } from 'echarts/core'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useTheme } from 'vuetify'
import { getSlaStatusApi } from '@/api/smApi'
import AppChart from '@/components/common/AppChart.vue'
import AppPageHeader from '@/components/common/AppPageHeader.vue'
import AppStatusChip from '@/components/common/AppStatusChip.vue'
import SlaBadge from '@/components/sm/SlaBadge.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useNow } from '@/composables/useNow'
import type { ApiError } from '@/types/api'
import { SR_TYPES, type SlaStatus, type Sr } from '@/types/r3'
import { today } from '@/utils/date'
import { formatPercent } from '@/utils/format'
import { shiftMonth } from '@/utils/sm'

// SCR-SM-008 SLA 현황 (FR-SM-005) — 월 준수율·응답률, 임박/위반 목록
const { t } = useI18n()
const theme = useTheme()
const now = useNow(30_000)

const month = ref(today().slice(0, 7))
const status = ref<SlaStatus | null>(null)
const fetchedAt = ref(Date.now())
const loading = ref(false)
const error = ref<string | null>(null)

// ─────────── 헬퍼 ───────────
const load = async () => {
  if (!projectId.value) return
  loading.value = true
  error.value = null
  try {
    status.value = await getSlaStatusApi(projectId.value, month.value)
    fetchedAt.value = Date.now()
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const { projectId } = useCurrentProject(() => load())

const color = (name: string) => theme.current.value.colors[name] as string
const srLink = (s: Sr) => ({ path: '/sm/srs', query: { id: s.srId } })

// ─────────── computed ───────────
const chartOption = computed<EChartsCoreOption>(() => {
  const by = status.value?.byType
  const types = SR_TYPES.filter((ty) => (by?.[ty]?.total ?? 0) > 0)
  return {
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0, icon: 'circle' },
    grid: { left: 8, right: 16, top: 12, bottom: 36, containLabel: true },
    xAxis: { type: 'category', data: types.map((ty) => t(`sm.srType.${ty}`)), axisLine: { lineStyle: { color: color('line') } } },
    yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: color('line') } } },
    series: [
      { name: t('sm.slaPage.met'), type: 'bar', stack: 'sla', data: types.map((ty) => by?.[ty].met ?? 0), itemStyle: { color: color('success') }, barMaxWidth: 36 },
      { name: t('sm.slaPage.notMet'), type: 'bar', stack: 'sla', data: types.map((ty) => (by?.[ty].total ?? 0) - (by?.[ty].met ?? 0)), itemStyle: { color: color('error') }, barMaxWidth: 36 },
    ],
  }
})
const rateColor = (v: number) => (v >= 0.95 ? 'text-success' : v >= 0.9 ? 'text-warning' : 'text-error')

watch(month, () => { void load() })
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page">
    <AppPageHeader :title="t('nav.sm.sla')" :subtitle="t('sm.slaPage.subtitle')">
      <div class="d-flex align-center">
        <VBtn icon="ri-arrow-left-s-line" variant="text" size="small" :aria-label="t('sm.prevMonth')" @click="month = shiftMonth(month, -1)" />
        <span class="font-mono">{{ month }}</span>
        <VBtn icon="ri-arrow-right-s-line" variant="text" size="small" :aria-label="t('sm.nextMonth')" @click="month = shiftMonth(month, 1)" />
      </div>
      <VBtn v-if="projectId" size="small" variant="tonal" prepend-icon="ri-settings-3-line" to="/admin/sla-policies">{{ t('sm.slaPage.policies') }}</VBtn>
    </AppPageHeader>
    <VAlert v-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>
    <template v-else>
      <VProgressLinear v-if="loading" indeterminate color="primary" />
      <VAlert v-if="error" type="error" variant="tonal" density="compact" class="mb-3">{{ error }}</VAlert>
      <template v-if="status">
        <VRow class="mb-1" data-test="sla-kpi">
          <VCol cols="6" md="3">
            <VCard border flat class="pa-4">
              <div class="text-caption text-muted">{{ t('sm.slaPage.compliance') }}</div>
              <div :class="['text-h4 font-weight-bold', rateColor(status.complianceRate)]">{{ formatPercent(status.complianceRate) }}</div>
            </VCard>
          </VCol>
          <VCol cols="6" md="3">
            <VCard border flat class="pa-4">
              <div class="text-caption text-muted">{{ t('sm.slaPage.responseRate') }}</div>
              <div :class="['text-h4 font-weight-bold', rateColor(status.responseRate)]">{{ formatPercent(status.responseRate) }}</div>
            </VCard>
          </VCol>
          <VCol cols="6" md="3">
            <VCard border flat class="pa-4">
              <div class="text-caption text-muted">{{ t('sm.slaPage.total') }}</div>
              <div class="text-h4">{{ status.total }}</div>
              <div class="text-caption text-muted">{{ t('sm.slaPage.metBreached', { met: status.met, breached: status.breached }) }}</div>
            </VCard>
          </VCol>
          <VCol cols="6" md="3">
            <VCard border flat class="pa-4" :to="{ path: '/sm/srs', query: { sla: 'DUE_SOON' } }">
              <div class="text-caption text-muted">{{ t('sm.slaPage.dueSoonNow') }}</div>
              <div class="text-h4 text-warning">{{ status.dueSoon.length }}</div>
            </VCard>
          </VCol>
        </VRow>
        <VRow>
          <VCol cols="12" lg="5">
            <VCard border flat :title="t('sm.slaPage.byType')">
              <VCardText><AppChart :option="chartOption" :height="260" :label="t('sm.slaPage.byType')" /></VCardText>
            </VCard>
          </VCol>
          <VCol cols="12" lg="7">
            <VCard border flat :title="t('sm.slaPage.dueSoonList')" class="mb-4">
              <VList density="compact" data-test="sla-due-soon">
                <VListItem v-for="s in status.dueSoon" :key="s.srId" :to="srLink(s)">
                  <VListItemTitle><span class="font-mono text-caption mr-1">{{ s.srNo }}</span>{{ s.title }}</VListItemTitle>
                  <VListItemSubtitle>{{ s.assignee?.name ?? t('sm.sr.unassigned') }}</VListItemSubtitle>
                  <template #append><SlaBadge :sla="s.sla" :fetched-at="fetchedAt" :now="now" size="x-small" /></template>
                </VListItem>
                <VListItem v-if="!status.dueSoon.length" :title="t('sm.slaPage.noDueSoon')" disabled />
              </VList>
            </VCard>
            <VCard border flat :title="t('sm.slaPage.breachedList')">
              <VList density="compact" data-test="sla-breached">
                <VListItem v-for="s in status.breachedList" :key="s.srId" :to="srLink(s)">
                  <VListItemTitle><span class="font-mono text-caption mr-1">{{ s.srNo }}</span>{{ s.title }}</VListItemTitle>
                  <VListItemSubtitle>{{ t(`sm.srType.${s.type}`) }} · {{ s.assignee?.name ?? t('sm.sr.unassigned') }}</VListItemSubtitle>
                  <template #append><AppStatusChip :status="s.status" prefix="sm.srStatus" size="x-small" /></template>
                </VListItem>
                <VListItem v-if="!status.breachedList.length" :title="t('sm.slaPage.noBreached')" disabled />
              </VList>
            </VCard>
          </VCol>
        </VRow>
      </template>
    </template>
  </VContainer>
</template>
