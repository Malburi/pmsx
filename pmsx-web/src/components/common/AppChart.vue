<script setup lang="ts">
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, MarkLineComponent, TooltipComponent } from 'echarts/components'
import { init, use, type ECharts, type EChartsCoreOption } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useTheme } from 'vuetify'

// 필요한 차트·컴포넌트만 등록해 번들을 줄인다 (트리셰이킹, performance-optimization)
use([BarChart, LineChart, PieChart, GridComponent, LegendComponent, MarkLineComponent, TooltipComponent, CanvasRenderer])

const props = withDefaults(defineProps<{
  option: EChartsCoreOption
  height?: number
  /** 차트 내용 요약 (스크린리더용 aria-label) */
  label: string
}>(), { height: 240 })
const emit = defineEmits<{ select: [params: { name: string; seriesName?: string; dataIndex: number }] }>()

const theme = useTheme()
const el = ref<HTMLDivElement | null>(null)
let chart: ECharts | null = null
let observer: ResizeObserver | null = null

// ─────────── 헬퍼 ───────────
type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => !!v && typeof v === 'object' && !Array.isArray(v)
/** base 위에 over 를 깊게 덮는다 (화면이 지정한 값이 이긴다) */
const deepMerge = (base: Obj, over: Obj): Obj => {
  const out: Obj = { ...base }
  for (const [k, v] of Object.entries(over)) out[k] = isObj(v) && isObj(out[k]) ? deepMerge(out[k] as Obj, v) : v
  return out
}
/** 단일 객체 또는 배열인 옵션(xAxis 등)에 기본값을 깔아 준다 */
const applyDefaults = (v: unknown, defaults: Obj): unknown => {
  if (Array.isArray(v)) return v.map((x) => (isObj(x) ? deepMerge(defaults, x) : x))
  return isObj(v) ? deepMerge(defaults, v) : v
}

// 테마 색(문자·축·격자선·범례)을 옵션에 깔아 라이트/다크 모두에서 읽히게 한다.
// ECharts 기본 테마는 축 글자·범례 색을 회색/검정으로 고정하므로 전역 textStyle 만으로는 다크에서 안 보인다.
// 테마 색은 차트가 놓인 요소의 CSS 변수(--v-theme-*)에서 읽는다. theme.current.colors 에는 on-* 색이 없다.
const cssColor = (name: string, fallback: string): string => {
  const v = el.value ? getComputedStyle(el.value).getPropertyValue(`--v-theme-${name}`).trim() : ''
  return v ? `rgb(${v})` : fallback
}
// 클린 벤토 팔레트 — 파스텔 톤이지만 흰 바탕에서 구분되는 채도 (블루·민트·라벤더·피치·옐로·스카이·핑크·그레이)
const PALETTE_LIGHT = ['#3182f6', '#2bc4a8', '#8b7cf6', '#f59a6c', '#f5c14b', '#5bb8f0', '#f27ba6', '#a0a9b6']
const PALETTE_DARK = ['#5ea0ff', '#3fd9bd', '#a89bff', '#ffab85', '#ffd36b', '#7ccaf7', '#ff94bb', '#8d96a3']

const themed = (option: EChartsCoreOption): EChartsCoreOption => {
  const text = cssColor('on-surface', '#191f28')
  const muted = cssColor('muted', '#6b7684')
  const line = cssColor('line', '#e9ecf0')
  const surface = cssColor('surface', '#fff')
  const dark = theme.global.current.value.dark
  const axis: Obj = {
    axisLabel: { color: muted, fontSize: 11 },
    axisLine: { lineStyle: { color: line } },
    axisTick: { show: false },
    splitLine: { lineStyle: { color: line, type: 'dashed', opacity: 0.8 } },
    nameTextStyle: { color: muted },
  }
  const o = option as Obj
  // 막대는 둥글게 (화면이 itemStyle.borderRadius 를 지정했거나 누적 막대면 그대로 둔다)
  const series = Array.isArray(o.series)
    ? (o.series as Obj[]).map((sr) => (isObj(sr) && sr.type === 'bar' && !sr.stack
      ? deepMerge({ itemStyle: { borderRadius: 6 }, barMaxWidth: 28 }, sr)
      : sr))
    : o.series
  return {
    backgroundColor: 'transparent',
    color: dark ? PALETTE_DARK : PALETTE_LIGHT,
    textStyle: { color: text, fontFamily: 'inherit' },
    tooltip: {
      backgroundColor: surface,
      borderColor: line,
      borderWidth: 1,
      padding: [8, 12],
      extraCssText: 'border-radius:12px;box-shadow:0 8px 24px rgba(0,23,51,0.12);',
      textStyle: { color: text },
    },
    ...o,
    ...(series !== undefined ? { series } : {}),
    ...(o.legend !== undefined ? { legend: applyDefaults(o.legend, { textStyle: { color: text }, icon: 'circle', itemWidth: 8, itemHeight: 8, itemGap: 16 }) } : {}),
    ...(o.xAxis !== undefined ? { xAxis: applyDefaults(o.xAxis, axis) } : {}),
    ...(o.yAxis !== undefined ? { yAxis: applyDefaults(o.yAxis, axis) } : {}),
  }
}

const render = () => {
  if (!chart) return
  chart.setOption(themed(props.option), true)
}

// ─────────── computed ───────────
const style = computed(() => ({ height: `${props.height}px` }))

// ─────────── watch ───────────
watch(() => props.option, render, { deep: true })
// 테마가 바뀌면 CSS 변수가 DOM 에 반영된 다음 다시 그린다
watch(() => theme.global.name.value, () => { void nextTick(render) })

// ─────────── 라이프사이클 ───────────
onMounted(() => {
  if (!el.value) return
  chart = init(el.value, undefined, { renderer: 'canvas' })
  chart.on('click', (p) => emit('select', { name: String(p.name), seriesName: p.seriesName, dataIndex: p.dataIndex }))
  render()
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(el.value)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div ref="el" class="app-chart" :style="style" role="img" :aria-label="label" />
</template>

<style scoped>
.app-chart {
  width: 100%;
}
</style>
