import { ref, watch, type Ref } from 'vue'
import { getCodeMapApi } from '@/api/codeApi'
import type { Code } from '@/types/common'

// 화면 콤보용 공통코드 캐시. 같은 그룹을 여러 화면이 동시에 요청해도 한 번만 부른다.
const cache = new Map<string, Code[]>()
const pending = new Map<string, Promise<void>>()
// 캐시 무효화 시 사용 중인 화면이 다시 읽도록 버전을 올린다
const version = ref(0)

const fetchGroups = (groups: string[]): Promise<void> => {
  const missing = groups.filter((g) => !cache.has(g) && !pending.has(g))
  const waits = groups.map((g) => pending.get(g)).filter((p): p is Promise<void> => !!p)
  if (missing.length) {
    const p = getCodeMapApi(missing)
      .then((map) => { missing.forEach((g) => cache.set(g, map[g] ?? [])) })
      .finally(() => { missing.forEach((g) => pending.delete(g)) })
    missing.forEach((g) => pending.set(g, p))
    waits.push(p)
  }
  return Promise.all(waits).then(() => undefined)
}

/** 공통코드 저장 후 호출 — 다음 조회 때 서버에서 다시 읽는다 */
export const clearCodeCache = (groups?: string[]) => {
  if (groups) groups.forEach((g) => cache.delete(g))
  else cache.clear()
  version.value += 1
}

export interface UseCodesResult {
  codes: Ref<Record<string, Code[]>>
  loading: Ref<boolean>
  /** VSelect items 형식 */
  items: (group: string) => { title: string; value: string }[]
  /** 코드 → 이름 (없으면 코드 그대로) */
  label: (group: string, code: string | null | undefined) => string
  reload: () => Promise<void>
}

export const useCodes = (groups: string[]): UseCodesResult => {
  const codes = ref<Record<string, Code[]>>({})
  const loading = ref(false)

  const reload = async () => {
    loading.value = true
    try {
      await fetchGroups(groups)
    } catch {
      // 코드 조회 실패는 콤보가 비는 것으로 끝낸다 (화면 본 기능은 계속 동작)
    } finally {
      codes.value = Object.fromEntries(groups.map((g) => [g, cache.get(g) ?? []]))
      loading.value = false
    }
  }

  const items = (group: string) =>
    (codes.value[group] ?? []).map((c) => ({ title: c.name, value: c.code }))

  const label = (group: string, code: string | null | undefined) =>
    (code ? (codes.value[group] ?? []).find((c) => c.code === code)?.name ?? code : '')

  // 캐시 무효화(clearCodeCache) 시 다시 읽기
  watch(version, () => { void reload() })

  void reload()
  return { codes, loading, items, label, reload }
}
