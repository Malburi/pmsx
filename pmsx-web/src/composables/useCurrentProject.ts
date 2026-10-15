import { storeToRefs } from 'pinia'
import { onMounted, watch } from 'vue'
import { useProjectStore } from '@/stores/project'

/**
 * 현재 선택된 프로젝트 ID 와, 프로젝트가 바뀔 때마다 다시 읽는 훅.
 * R2 화면은 모두 프로젝트 단위이므로 이 한 곳에서 처리한다.
 */
export const useCurrentProject = (reload?: (projectId: string) => void | Promise<void>) => {
  const store = useProjectStore()
  const { currentProjectId, currentProject } = storeToRefs(store)

  if (reload) {
    watch(currentProjectId, (id) => { if (id) void reload(id) })
    onMounted(() => { if (currentProjectId.value) void reload(currentProjectId.value) })
  }

  return { projectId: currentProjectId, project: currentProject }
}
