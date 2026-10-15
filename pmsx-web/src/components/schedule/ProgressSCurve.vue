<script setup lang="ts">
import type { EChartsCoreOption } from 'echarts/core'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useTheme } from 'vuetify'
import AppChart from '@/components/common/AppChart.vue'
import type { ProjectProgress } from '@/types/r2'

// 계획 대비 실적 S-curve (FR-PJ-006)
const props = withDefaults(defineProps<{ progress: ProjectProgress; height?: number }>(), { height: 240 })

const { t } = useI18n()
const theme = useTheme()

const color = (name: string) => theme.current.value.colors[name] as string

const option = computed<EChartsCoreOption>(() => {
  const pts = props.progress.sCurve
  return {
    tooltip: { trigger: 'axis', valueFormatter: (v: number | null) => (v === null || v === undefined ? '-' : `${v}%`) },
    legend: { bottom: 0, icon: 'circle' },
    grid: { left: 8, right: 16, top: 28, bottom: 36, containLabel: true },
    xAxis: { type: 'category', data: pts.map((p) => p.date.slice(2)), boundaryGap: false, axisLine: { lineStyle: { color: color('line') } } },
    yAxis: { type: 'value', min: 0, max: 100, axisLabel: { formatter: '{value}%' }, splitLine: { lineStyle: { color: color('line') } } },
    series: [
      {
        name: t('pj.progress.planned'), type: 'line', smooth: true, showSymbol: false, data: pts.map((p) => p.planned),
        lineStyle: { color: color('secondary'), type: 'dashed' }, itemStyle: { color: color('secondary') },
        markLine: { symbol: 'none', silent: true, label: { formatter: t('pj.progress.today'), position: 'end', color: color('error'), fontSize: 11, textBorderWidth: 0 }, lineStyle: { color: color('error') }, data: [{ xAxis: props.progress.asOf.slice(2) }] },
      },
      {
        name: t('pj.progress.actual'), type: 'line', smooth: true, showSymbol: false, data: pts.map((p) => p.actual),
        lineStyle: { color: color('primary'), width: 3 }, itemStyle: { color: color('primary') },
        areaStyle: { color: color('primary'), opacity: 0.08 },
      },
    ],
  }
})
</script>

<template>
  <AppChart :option="option" :height="height" :label="t('pj.progress.sCurve')" />
</template>
