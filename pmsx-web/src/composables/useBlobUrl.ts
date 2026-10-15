import { onBeforeUnmount, ref, watch, type Ref } from 'vue'

// 인증이 필요한 이미지/문서를 blob 으로 받아 object URL 로 쓰고, 키가 바뀌거나 화면을 떠날 때 해제한다.
export const useBlobUrl = (key: Ref<string | null>, loader: (key: string) => Promise<Blob>) => {
  const url = ref<string | null>(null)
  const loading = ref(false)
  const failed = ref(false)
  let seq = 0

  const revoke = () => {
    if (url.value) URL.revokeObjectURL(url.value)
    url.value = null
  }

  const load = async (k: string | null) => {
    const mine = ++seq
    revoke()
    failed.value = false
    if (!k) return
    loading.value = true
    try {
      const blob = await loader(k)
      if (mine === seq) url.value = URL.createObjectURL(blob)
    } catch {
      if (mine === seq) failed.value = true
    } finally {
      if (mine === seq) loading.value = false
    }
  }

  watch(key, load, { immediate: true })

  onBeforeUnmount(revoke)

  return { url, loading, failed }
}
