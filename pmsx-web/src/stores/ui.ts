import { defineStore } from 'pinia'

export type ThemeName = 'light' | 'dark'

interface ToastState {
  show: boolean
  messageKey: string
  color: 'error' | 'success' | 'info'
}

export const useUiStore = defineStore('ui', {
  state: () => ({
    theme: (window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light') as ThemeName,
    navRail: false,
    snackbar: { show: false, messageKey: '', color: 'info' } as ToastState,
  }),
  actions: {
    toggleTheme() {
      this.theme = this.theme === 'dark' ? 'light' : 'dark'
    },
    toast(messageKey: string, color: ToastState['color'] = 'info') {
      this.snackbar = { show: true, messageKey, color }
    },
  },
  persist: {
    pick: ['theme', 'navRail'],
  },
})
