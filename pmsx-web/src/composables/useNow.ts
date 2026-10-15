import { onBeforeUnmount, onMounted, ref } from 'vue'

/**
 * 일정 간격으로 갱신되는 현재 시각(ms). SLA 남은 시간 카운트다운 같은 화면 표시용.
 * 컴포넌트가 사라지면 타이머도 멈춘다.
 */
export const useNow = (intervalMs = 30_000) => {
  const now = ref(Date.now())
  let timer: ReturnType<typeof setInterval> | null = null
  onMounted(() => { timer = setInterval(() => { now.value = Date.now() }, intervalMs) })
  onBeforeUnmount(() => { if (timer) clearInterval(timer) })
  return now
}
