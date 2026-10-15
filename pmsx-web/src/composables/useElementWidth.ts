import { onBeforeUnmount, onMounted, ref, type Ref } from 'vue'

/**
 * 요소의 현재 너비(px). 목록+상세 2단 화면에서 목록 표가 들어갈 자리에 맞춰 열을 줄이는 데 쓴다.
 * target 은 HTML 요소 또는 Vuetify 컴포넌트 ref($el 사용).
 */
export const useElementWidth = (target: Ref<HTMLElement | { $el: HTMLElement } | null | undefined>, initial = 1200) => {
  const width = ref(initial)
  let observer: ResizeObserver | null = null

  const resolve = (): HTMLElement | null => {
    const t = target.value
    if (!t) return null
    return t instanceof HTMLElement ? t : (t.$el ?? null)
  }

  onMounted(() => {
    const el = resolve()
    if (!el || typeof ResizeObserver === 'undefined') return
    width.value = el.clientWidth || initial
    observer = new ResizeObserver((entries) => {
      const w = entries[0]?.contentRect.width
      if (w) width.value = Math.round(w)
    })
    observer.observe(el)
  })
  onBeforeUnmount(() => observer?.disconnect())

  return width
}
