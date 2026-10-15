import { defineStore } from 'pinia'
import { getPortalProjectsApi } from '@/api/portalApi'
import type { Project } from '@/types/api'

// 고객 SR 포털(SCR-SM-010) 의 프로젝트 선택 상태 — 내부 화면의 project 스토어와 분리한다
export const usePortalStore = defineStore('portal', {
  state: () => ({
    projects: [] as Project[],
    currentProjectId: null as string | null,
    loaded: false,
  }),
  getters: {
    currentProject: (s) => s.projects.find((p) => p.projectId === s.currentProjectId) ?? null,
  },
  actions: {
    async load() {
      this.projects = await getPortalProjectsApi()
      if (!this.projects.some((p) => p.projectId === this.currentProjectId)) this.currentProjectId = this.projects[0]?.projectId ?? null
      this.loaded = true
    },
  },
  persist: {
    pick: ['currentProjectId'],
  },
})
