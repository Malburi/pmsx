import { createRouter, createWebHistory } from 'vue-router'
import { handleHotUpdate, routes } from 'vue-router/auto-routes'
import { setupLayouts } from 'virtual:generated-layouts'
import { setupGuards } from '@/router/guards'

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: setupLayouts(routes),
})

setupGuards(router)

if (import.meta.hot) handleHotUpdate(router)
