import { defineStore } from 'pinia'
import { getProjectsApi, getSystemsApi } from '@/api/projectApi'
import type { Project, SystemSummary } from '@/types/api'

export const useProjectStore = defineStore('project', {
  state: () => ({
    projects: [] as Project[],
    currentProjectId: null as string | null,
    systems: [] as SystemSummary[],
    loaded: false,
  }),
  getters: {
    currentProject: (s) => s.projects.find((p) => p.projectId === s.currentProjectId) ?? null,
  },
  actions: {
    async loadProjects() {
      this.projects = await getProjectsApi()
      const stillValid = this.projects.some((p) => p.projectId === this.currentProjectId)
      if (!stillValid) this.currentProjectId = this.projects[0]?.projectId ?? null
      this.loaded = true
      await this.loadSystems()
    },
    async selectProject(projectId: string) {
      this.currentProjectId = projectId
      await this.loadSystems()
    },
    async loadSystems() {
      this.systems = this.currentProjectId ? await getSystemsApi(this.currentProjectId) : []
    },
    upsertSystem(system: SystemSummary) {
      const i = this.systems.findIndex((s) => s.systemId === system.systemId)
      if (i >= 0) this.systems.splice(i, 1, system)
      else this.systems.push(system)
    },
  },
  persist: {
    pick: ['currentProjectId'],
  },
})
