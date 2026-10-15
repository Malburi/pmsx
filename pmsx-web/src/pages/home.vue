<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { getPortfolioApi, getProjectDashboardApi } from '@/api/dashboardApi'
import ProgressSCurve from '@/components/schedule/ProgressSCurve.vue'
import { useCurrentProject } from '@/composables/useCurrentProject'
import { useAuthStore } from '@/stores/auth'
import { useProjectStore } from '@/stores/project'
import type { ApiError } from '@/types/api'
import type { PortfolioRow, ProjectDashboard } from '@/types/r2'
import { diffDays, today } from '@/utils/date'
import { PORTFOLIO_ROLES } from '@/utils/permission'
import { RISK_LEVEL_COLOR, riskLevel, statusColorOf } from '@/utils/quality'

// SCR-CM-001 홈 대시보드 (FR-CM-008) — 클린 벤토 레이아웃: 인사말 + 크기가 다른 타일(클릭하면 해당 화면으로)
const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()
const projectStore = useProjectStore()

const view = ref<'PM' | 'CUSTOMER' | 'PORTFOLIO'>(auth.hasAnyRole(['CUSTOMER']) && !auth.hasAnyRole(['PM', 'PL']) ? 'CUSTOMER' : 'PM')
const dash = ref<ProjectDashboard | null>(null)
const portfolio = ref<PortfolioRow[]>([])
const loading = ref(false)
const error = ref<string | null>(null)

// ─────────── 헬퍼 ───────────
const load = async (projectId: string) => {
  if (view.value === 'PORTFOLIO') return loadPortfolio()
  loading.value = true
  error.value = null
  try {
    dash.value = await getProjectDashboardApi(projectId, view.value)
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const loadPortfolio = async () => {
  loading.value = true
  error.value = null
  try {
    portfolio.value = await getPortfolioApi()
  } catch (e) {
    error.value = (e as ApiError).message ?? t('common.error.unknown')
  } finally {
    loading.value = false
  }
}
const { projectId, project } = useCurrentProject(load)

const openProject = async (row: PortfolioRow) => {
  if (projectStore.projects.some((p) => p.projectId === row.projectId)) {
    await projectStore.selectProject(row.projectId)
    view.value = 'PM'
  }
}

const dday = (d: string) => {
  const n = diffDays(today(), d)
  return n === 0 ? 'D-DAY' : n > 0 ? `D-${n}` : `D+${-n}`
}
const go = (path: string) => { void router.push(path) }

// ─────────── computed ───────────
const canPortfolio = computed(() => auth.hasAnyRole(PORTFOLIO_ROLES))
const todayLabel = computed(() => new Intl.DateTimeFormat('ko-KR', { month: 'long', day: 'numeric', weekday: 'short' }).format(new Date()))
const spi = computed(() => dash.value?.progress.spi ?? null)
const spiColor = computed(() => {
  const s = spi.value ?? 1
  return s < 0.9 ? 'error' : s < 0.95 ? 'warning' : 'success'
})
const spiMessage = computed(() => t(spiColor.value === 'success' ? 'home.spiGood' : spiColor.value === 'warning' ? 'home.spiWarn' : 'home.spiBad'))
const gap = computed(() => Math.round(((dash.value?.progress.actual ?? 0) - (dash.value?.progress.planned ?? 0)) * 10) / 10)
const gapMessage = computed(() => (Math.abs(gap.value) < 0.5 ? t('home.onTrack') : gap.value > 0 ? t('home.ahead', { n: gap.value }) : t('home.behind', { n: -gap.value })))
const upcoming = computed(() => (dash.value?.milestones ?? []).filter((m) => m.status !== 'DONE').sort((a, b) => a.planEnd.localeCompare(b.planEnd)).slice(0, 3))
const dlTotal = computed(() => Object.values(dash.value?.deliverables ?? {}).reduce((s, n) => s + n, 0) || 1)
const analysisPct = computed(() => {
  const a = dash.value?.analysis
  return a && a.total ? Math.round((a.done / a.total) * 100) : 0
})
// 진척 스파크라인 — S-curve 실적 값을 작은 선으로 (값이 2개 이상일 때)
const spark = computed(() => {
  const pts = (dash.value?.progress.sCurve ?? []).map((p) => p.actual).filter((v): v is number => v !== null)
  if (pts.length < 2) return null
  const w = 120
  const h = 36
  const max = Math.max(...pts, 1)
  const step = w / (pts.length - 1)
  const line = pts.map((v, i) => `${(i * step).toFixed(1)},${(h - (v / max) * (h - 4) - 2).toFixed(1)}`).join(' ')
  return { line, area: `0,${h} ${line} ${w},${h}`, w, h }
})
const viewItems = computed(() => [
  { title: t('home.viewPM'), value: 'PM' },
  { title: t('home.viewCustomer'), value: 'CUSTOMER' },
  ...(canPortfolio.value ? [{ title: t('home.viewPortfolio'), value: 'PORTFOLIO' }] : []),
])

// ─────────── watch ───────────
watch(view, () => { if (view.value === 'PORTFOLIO') void loadPortfolio(); else if (projectId.value) void load(projectId.value) })
</script>

<template>
  <VContainer fluid class="pa-4 pa-md-8 pmsx-page home">
    <!-- 인사말 -->
    <header class="d-flex flex-wrap align-end ga-3 mb-6">
      <div class="flex-grow-1 greet">
        <h1 class="greet-title">{{ t('home.greeting', { name: auth.name ?? auth.loginId ?? '' }) }}</h1>
        <div class="pmsx-page-subtitle">
          {{ view === 'PORTFOLIO' ? t('home.greetingPortfolio', { date: todayLabel }) : t('home.greetingSub', { date: todayLabel, project: project?.name ?? t('nav.home') }) }}
        </div>
      </div>
      <VBtnToggle v-model="view" density="compact" mandatory data-test="home-view">
        <VBtn v-for="v in viewItems" :key="v.value" :value="v.value" size="small">{{ v.title }}</VBtn>
      </VBtnToggle>
    </header>

    <VAlert v-if="error" type="error" variant="tonal" density="compact" class="mb-4">{{ error }}</VAlert>
    <VProgressLinear v-if="loading" indeterminate color="primary" rounded class="mb-4" />

    <!-- 포트폴리오 (PMO·임원) -->
    <VCard v-if="view === 'PORTFOLIO'" class="pa-2" data-test="portfolio">
      <VCardTitle class="pt-4 px-4">{{ t('home.portfolio') }}</VCardTitle>
      <VCardSubtitle class="px-4">{{ t('home.portfolioSub') }}</VCardSubtitle>
      <div class="table-scroll">
        <VTable density="comfortable" hover>
          <thead>
            <tr>
              <th>{{ t('home.colProject') }}</th><th>{{ t('home.colPm') }}</th><th class="prog-col">{{ t('home.colProgress') }}</th>
              <th class="text-end">SPI</th><th class="text-end">{{ t('home.colIssues') }}</th><th class="text-end">{{ t('home.colCritical') }}</th><th>{{ t('home.colHealth') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in portfolio" :key="r.projectId" class="clickable" tabindex="0" @click="openProject(r)">
              <td>
                <div class="font-weight-bold">{{ r.name }}</div>
                <div class="text-caption text-muted">[{{ r.type }}] {{ r.customer }}</div>
              </td>
              <td>{{ r.pm }}</td>
              <td>
                <div class="d-flex align-center ga-2">
                  <div class="prog">
                    <div class="prog-plan" :style="{ width: `${r.planned}%` }" />
                    <div class="prog-act" :style="{ width: `${r.actual}%` }" />
                  </div>
                  <span class="text-caption text-no-wrap tabular-nums">{{ r.actual }} / {{ r.planned }}%</span>
                </div>
              </td>
              <td class="text-end font-weight-bold tabular-nums" :class="(r.spi ?? 1) < 0.9 ? 'text-error' : (r.spi ?? 1) < 0.95 ? 'text-warning' : ''">{{ r.spi ?? '-' }}</td>
              <td class="text-end">{{ r.openIssues }}</td>
              <td class="text-end" :class="{ 'text-error font-weight-bold': r.criticalDefects > 0 }">{{ r.criticalDefects }}</td>
              <td><VChip :color="statusColorOf(r.health)" variant="tonal" size="small">{{ t(`home.health.${r.health}`) }}</VChip></td>
            </tr>
          </tbody>
        </VTable>
      </div>
      <div class="text-caption text-muted px-4 pb-3 pt-2">{{ t('home.healthRule') }}</div>
    </VCard>

    <VAlert v-else-if="!projectId" type="info" variant="tonal">{{ t('pj.selectProject') }}</VAlert>

    <!-- 벤토 그리드 -->
    <div v-else-if="dash" class="bento" data-test="home-kpis">
      <!-- 큰 타일: SM 프로젝트는 운영 현황, 그 외는 진척률 -->
      <VCard v-if="dash.sm" class="tile tile--hero hero-sm clickable" tabindex="0" role="link" @click="go('/sm/srs')" @keydown.enter="go('/sm/srs')">
        <div class="tile-head">
          <span class="tile-icon bg-pastel-blue text-primary"><VIcon icon="ri-customer-service-2-line" size="20" /></span>
          <span class="pmsx-label">{{ t('home.sm.title') }}</span>
          <VSpacer />
          <span class="tile-more">{{ t('home.more') }} <VIcon icon="ri-arrow-right-s-line" size="16" /></span>
        </div>
        <div class="d-flex align-end ga-2 mt-3">
          <span class="pmsx-kpi hero-num">{{ dash.sm.complianceRate }}</span><span class="kpi-unit">%</span>
        </div>
        <div class="pmsx-label mb-4">{{ t('home.sm.compliance') }}</div>
        <div class="sm-stats">
          <div><div class="sm-n">{{ dash.sm.openSrs }}</div><div class="pmsx-label">{{ t('home.sm.openSrs') }}</div></div>
          <div><div class="sm-n text-warning">{{ dash.sm.dueSoon }}</div><div class="pmsx-label">{{ t('home.sm.dueSoon') }}</div></div>
          <div><div class="sm-n text-error">{{ dash.sm.breached }}</div><div class="pmsx-label">{{ t('home.sm.breached') }}</div></div>
          <div><div class="sm-n">{{ dash.sm.openIncidents }}</div><div class="pmsx-label">{{ t('home.sm.incidents') }}</div></div>
        </div>
      </VCard>
      <VCard v-else class="tile tile--hero clickable" tabindex="0" role="link" @click="go('/schedule/wbs')" @keydown.enter="go('/schedule/wbs')">
        <div class="tile-head">
          <span class="tile-icon bg-pastel-blue text-primary"><VIcon icon="ri-line-chart-line" size="20" /></span>
          <span class="pmsx-label">{{ t('home.progress') }}</span>
          <VSpacer />
          <span class="tile-more">{{ t('home.more') }} <VIcon icon="ri-arrow-right-s-line" size="16" /></span>
        </div>
        <div class="d-flex align-end justify-space-between flex-wrap ga-2 mt-3">
          <div class="d-flex align-end ga-1">
            <span class="pmsx-kpi hero-num">{{ dash.progress.actual }}</span><span class="kpi-unit">%</span>
          </div>
          <svg v-if="spark" :viewBox="`0 0 ${spark.w} ${spark.h}`" :width="spark.w" :height="spark.h" class="spark" aria-hidden="true">
            <polygon :points="spark.area" class="spark-area" />
            <polyline :points="spark.line" class="spark-line" />
          </svg>
        </div>
        <div class="pmsx-label">{{ t('home.planVsActual', { planned: dash.progress.planned }) }}</div>
        <div class="hero-bar mt-4" role="img" :aria-label="`${dash.progress.actual}% / ${dash.progress.planned}%`">
          <div class="hero-bar__plan" :style="{ width: `${dash.progress.planned}%` }" />
          <div class="hero-bar__act" :style="{ width: `${dash.progress.actual}%` }" />
        </div>
        <div class="gap-msg mt-3" :class="gap < -0.5 ? 'text-warning' : 'text-success'">
          <VIcon :icon="gap < -0.5 ? 'ri-error-warning-line' : 'ri-checkbox-circle-line'" size="16" class="mr-1" />{{ gapMessage }}
        </div>
        <div class="hero-stats mt-auto">
          <div><div class="pmsx-label">{{ t('home.heroPlanned') }}</div><div class="hero-stat tabular-nums">{{ dash.progress.planned }}%</div></div>
          <div><div class="pmsx-label">{{ t('home.heroActual') }}</div><div class="hero-stat tabular-nums text-primary">{{ dash.progress.actual }}%</div></div>
          <div><div class="pmsx-label">{{ t('home.heroGap') }}</div><div class="hero-stat tabular-nums" :class="gap < -0.5 ? 'text-warning' : 'text-success'">{{ gap > 0 ? '+' : '' }}{{ gap }}%p</div></div>
          <div v-if="upcoming[0]"><div class="pmsx-label">{{ t('home.heroNext') }}</div><div class="hero-stat text-truncate">{{ dday(upcoming[0].planEnd) }}</div></div>
        </div>
      </VCard>

      <VCard class="tile tile--sm">
        <div class="tile-head">
          <span class="tile-icon bg-pastel-mint text-mint"><VIcon icon="ri-speed-up-line" size="20" /></span>
          <span class="pmsx-label">SPI</span>
        </div>
        <div class="pmsx-kpi mt-3" :class="`text-${spiColor}`">{{ spi ?? '-' }}</div>
        <div class="pmsx-label">{{ spi === null ? t('home.spiHint') : spiMessage }}</div>
      </VCard>

      <VCard class="tile tile--sm clickable" tabindex="0" role="link" @click="go('/quality/issues')" @keydown.enter="go('/quality/issues')">
        <div class="tile-head">
          <span class="tile-icon bg-pastel-yellow text-warning"><VIcon icon="ri-error-warning-line" size="20" /></span>
          <span class="pmsx-label">{{ t('home.openIssues') }}</span>
        </div>
        <div class="pmsx-kpi mt-3">{{ dash.openIssues }}</div>
        <div class="pmsx-label" :class="{ 'text-error': dash.overdueIssues }">{{ t('home.overdueN', { n: dash.overdueIssues }) }}</div>
      </VCard>

      <VCard class="tile tile--sm clickable" tabindex="0" role="link" @click="go('/quality/defects')" @keydown.enter="go('/quality/defects')">
        <div class="tile-head">
          <span class="tile-icon bg-pastel-peach text-peach"><VIcon icon="ri-bug-line" size="20" /></span>
          <span class="pmsx-label">{{ t('home.openDefects') }}</span>
        </div>
        <div class="pmsx-kpi mt-3">{{ dash.defects.open }}</div>
        <div class="pmsx-label" :class="{ 'text-error font-weight-bold': dash.defects.critical }">{{ t('home.criticalN', { n: dash.defects.critical }) }}</div>
      </VCard>

      <VCard v-if="view === 'PM'" class="tile tile--sm clickable" tabindex="0" role="link" @click="go('/resource/mm')" @keydown.enter="go('/resource/mm')">
        <div class="tile-head">
          <span class="tile-icon bg-pastel-lavender text-lavender"><VIcon icon="ri-team-line" size="20" /></span>
          <span class="pmsx-label">{{ t('home.mm') }}</span>
        </div>
        <div class="mt-3"><span class="pmsx-kpi">{{ dash.mm.actual }}</span><span class="kpi-unit"> / {{ dash.mm.planned }}</span></div>
        <div class="pmsx-label">{{ t('home.mmHint') }}</div>
      </VCard>
      <VCard v-else-if="dash.analysis" class="tile tile--sm clickable" tabindex="0" role="link" @click="go('/asis/progress')" @keydown.enter="go('/asis/progress')">
        <div class="tile-head">
          <span class="tile-icon bg-pastel-lavender text-lavender"><VIcon icon="ri-layout-masonry-line" size="20" /></span>
          <span class="pmsx-label">{{ t('home.analysis') }}</span>
        </div>
        <div class="mt-3"><span class="pmsx-kpi">{{ dash.analysis.done }}</span><span class="kpi-unit"> / {{ dash.analysis.total }}</span></div>
      </VCard>

      <!-- S-curve (와이드) -->
      <VCard class="tile tile--wide">
        <div class="tile-head mb-1">
          <span class="text-subtitle-1 font-weight-bold">{{ t('pj.progress.sCurve') }}</span>
        </div>
        <ProgressSCurve :progress="dash.progress" :height="250" />
      </VCard>

      <!-- 마일스톤 D-day -->
      <VCard class="tile tile--side">
        <div class="tile-head mb-3">
          <span class="text-subtitle-1 font-weight-bold">{{ t('home.milestones') }}</span>
        </div>
        <div v-if="upcoming.length" class="d-flex flex-column ga-2">
          <div v-for="m in upcoming" :key="m.name" class="ms-row">
            <div class="ms-dday" :class="diffDays(today(), m.planEnd) < 14 ? 'ms-dday--soon' : ''">{{ dday(m.planEnd) }}</div>
            <div class="min-w-0">
              <div class="font-weight-bold text-truncate">{{ m.name }}</div>
              <div class="pmsx-label">{{ m.planEnd }}</div>
            </div>
          </div>
        </div>
        <div v-else class="pmsx-empty py-6"><span class="pmsx-empty__icon"><VIcon icon="ri-flag-2-line" /></span>{{ t('home.noMilestone') }}</div>
      </VCard>

      <!-- 주요 리스크 -->
      <VCard class="tile tile--wide clickable-head">
        <div class="tile-head mb-2">
          <span class="text-subtitle-1 font-weight-bold">{{ t('home.topRisks') }}</span>
          <VSpacer />
          <VBtn variant="text" size="small" append-icon="ri-arrow-right-s-line" to="/quality/risks">{{ t('home.more') }}</VBtn>
        </div>
        <div v-if="dash.risksTop.length" class="d-flex flex-column ga-1">
          <div v-for="r in dash.risksTop" :key="r.riskId" class="risk-row">
            <VChip size="small" :color="RISK_LEVEL_COLOR[riskLevel(r.score)]" variant="tonal" class="risk-score tabular-nums">{{ r.score }}</VChip>
            <div class="min-w-0 flex-grow-1">
              <div class="font-weight-medium text-truncate">{{ r.title }}</div>
              <div class="pmsx-label text-truncate">{{ r.response }}</div>
            </div>
            <span class="pmsx-label text-no-wrap d-none d-sm-inline">{{ r.owner?.name ?? '-' }}</span>
          </div>
        </div>
        <div v-else class="pmsx-empty py-6">{{ t('home.noRisk') }}</div>
      </VCard>

      <!-- 산출물 현황 + 분석 도넛 -->
      <VCard class="tile tile--side">
        <div class="tile-head mb-3">
          <span class="text-subtitle-1 font-weight-bold">{{ t('home.deliverables') }}</span>
        </div>
        <div class="dl-bar mb-3" role="img" :aria-label="t('home.deliverables')">
          <div v-for="(n, s) in dash.deliverables" :key="s" :class="`bg-${statusColorOf(String(s))}`" :style="{ width: `${(n / dlTotal) * 100}%` }" />
        </div>
        <div class="d-flex flex-wrap ga-3 text-body-2">
          <span v-for="(n, s) in dash.deliverables" :key="`n-${s}`" class="d-inline-flex align-center ga-1">
            <i :class="['dot', `bg-${statusColorOf(String(s))}`]" />{{ t(`dl.status.${s}`) }} <b class="tabular-nums">{{ n }}</b>
          </span>
        </div>
        <template v-if="dash.analysis">
          <VDivider class="my-4" />
          <div class="d-flex align-center ga-4 clickable" tabindex="0" role="link" @click="go('/asis/progress')" @keydown.enter="go('/asis/progress')">
            <VProgressCircular :model-value="analysisPct" :size="64" :width="7" color="lavender" bg-color="pastel-lavender">
              <span class="font-weight-bold tabular-nums">{{ analysisPct }}%</span>
            </VProgressCircular>
            <div>
              <div class="pmsx-label">{{ t('home.analysis') }}</div>
              <div class="text-h6 font-weight-bold tabular-nums">{{ dash.analysis.done }} <span class="pmsx-label">/ {{ dash.analysis.total }}</span></div>
            </div>
          </div>
        </template>
      </VCard>
    </div>
  </VContainer>
</template>

<style scoped>
.greet-title {
  font-size: clamp(1.5rem, 1.15rem + 0.9vw, 1.875rem);
  font-weight: 800;
  letter-spacing: -0.03em;
  line-height: 1.25;
}

.bento {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 16px;
}

.tile {
  padding: 20px 22px;
  min-width: 0;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.tile--hero {
  grid-column: span 6;
  grid-row: span 2;
  display: flex;
  flex-direction: column;
  background: linear-gradient(160deg, rgb(var(--v-theme-surface)) 55%, rgb(var(--v-theme-pastel-blue)) 140%);
}

.tile--sm {
  grid-column: span 3;
}

.tile--wide {
  grid-column: span 8;
}

.tile--side {
  grid-column: span 4;
}

.clickable {
  cursor: pointer;
}

.tile.clickable:hover {
  transform: translateY(-2px);
  box-shadow: var(--pmsx-shadow-pop) !important;
}

.tile-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.tile-icon {
  width: 36px;
  height: 36px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  flex: none;
}

.tile-more {
  font-size: 0.8125rem;
  font-weight: 600;
  color: rgb(var(--v-theme-muted));
  display: inline-flex;
  align-items: center;
}

.hero-num {
  font-size: clamp(2.5rem, 2rem + 1.6vw, 3.5rem);
}

.kpi-unit {
  font-size: 1.125rem;
  font-weight: 700;
  color: rgb(var(--v-theme-muted));
  padding-bottom: 6px;
}

.spark-line {
  fill: none;
  stroke: rgb(var(--v-theme-primary));
  stroke-width: 2.5;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.spark-area {
  fill: rgba(var(--v-theme-primary), 0.12);
}

.hero-bar {
  position: relative;
  height: 14px;
  border-radius: 999px;
  background: rgb(var(--v-theme-surface-2));
  overflow: hidden;
}

.hero-bar__plan {
  position: absolute;
  inset: 0 auto 0 0;
  background: rgba(var(--v-theme-primary), 0.18);
  border-radius: 999px;
}

.hero-bar__act {
  position: absolute;
  inset: 0 auto 0 0;
  background: linear-gradient(90deg, rgb(var(--v-theme-primary)), #6b8cff);
  border-radius: 999px;
}

.hero-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  padding-top: 20px;
}

.hero-stat {
  font-size: 1.25rem;
  font-weight: 700;
}

.gap-msg {
  font-size: 0.875rem;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
}

.sm-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  background: rgb(var(--v-theme-surface-2));
  border-radius: 16px;
  padding: 14px;
}

.sm-n {
  font-size: 1.5rem;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.ms-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 14px;
  background: rgb(var(--v-theme-surface-2));
}

.ms-dday {
  min-width: 60px;
  text-align: center;
  font-weight: 800;
  font-size: 0.875rem;
  padding: 6px 8px;
  border-radius: 10px;
  background: rgb(var(--v-theme-pastel-blue));
  color: rgb(var(--v-theme-primary));
  font-variant-numeric: tabular-nums;
}

.ms-dday--soon {
  background: rgb(var(--v-theme-pastel-peach));
  color: rgb(var(--v-theme-error));
}

.min-w-0 {
  min-width: 0;
}

.risk-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 4px;
  border-radius: 12px;
}

.risk-score {
  min-width: 40px;
  justify-content: center;
}

.dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.dl-bar {
  display: flex;
  height: 12px;
  border-radius: 999px;
  overflow: hidden;
  gap: 2px;
}

.table-scroll {
  overflow-x: auto;
}

.prog-col {
  min-width: 200px;
}

.prog {
  position: relative;
  flex: 1;
  height: 10px;
  background: rgb(var(--v-theme-surface-2));
  border-radius: 999px;
  overflow: hidden;
}

.prog-plan {
  position: absolute;
  inset: 0 auto 0 0;
  background: rgba(var(--v-theme-primary), 0.2);
  border-radius: 999px;
}

.prog-act {
  position: absolute;
  inset: 0 auto 0 0;
  background: rgb(var(--v-theme-primary));
  border-radius: 999px;
}

tr.clickable {
  cursor: pointer;
}

@media (max-width: 1279px) {
  .tile--hero {
    grid-column: span 12;
    grid-row: auto;
  }

  .tile--sm {
    grid-column: span 6;
  }

  .tile--wide,
  .tile--side {
    grid-column: span 12;
  }
}

@media (max-width: 599px) {
  .tile--sm {
    grid-column: span 12;
  }

  .sm-stats,
  .hero-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
