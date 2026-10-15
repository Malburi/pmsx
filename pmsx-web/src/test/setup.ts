import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, vi } from 'vitest'

beforeEach(() => {
  setActivePinia(createPinia())
})

// Vuetify 등에서 필요한 matchMedia 폴리필
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation((q: string) => ({
    matches: false, media: q, addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn(),
  })),
})

// Vuetify 오버레이(VDialog·VMenu)가 쓰는 브라우저 API 폴리필 — jsdom 에 없다
class ResizeObserverStub {
  observe = vi.fn()
  unobserve = vi.fn()
  disconnect = vi.fn()
}
globalThis.ResizeObserver ??= ResizeObserverStub as unknown as typeof ResizeObserver
Object.defineProperty(window, 'visualViewport', {
  writable: true,
  value: { width: 1280, height: 800, offsetLeft: 0, offsetTop: 0, scale: 1, addEventListener: vi.fn(), removeEventListener: vi.fn() },
})
