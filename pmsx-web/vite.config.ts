import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import VueRouter from 'unplugin-vue-router/vite'
import Layouts from 'vite-plugin-vue-layouts'
import vuetify from 'vite-plugin-vuetify'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    // 파일 기반 라우팅은 vue() 보다 먼저 등록해야 한다 (routing-auth §2-1)
    VueRouter({
      routesFolder: 'src/pages',
      dts: 'src/typed-router.d.ts',
      exclude: ['**/components/**'],
    }),
    vue(),
    Layouts({
      layoutsDirs: 'src/@layouts',
      defaultLayout: 'default',
    }),
    vuetify({ autoImport: true, styles: { configFile: 'src/styles/settings.scss' } }),
  ],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  css: {
    preprocessorOptions: {
      scss: { api: 'modern-compiler' },
      sass: { api: 'modern-compiler' },
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  build: {
    target: 'es2020',
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia', 'vue-i18n'],
          vuetify: ['vuetify'],
        },
      },
    },
  },
  esbuild: {
    // Production 빌드에서 console/debugger 제거 (performance-optimization)
    drop: process.env.NODE_ENV === 'production' ? ['console', 'debugger'] : [],
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: {
    preprocessorOptions: {
      scss: { api: 'modern-compiler' },
      sass: { api: 'modern-compiler' },
    },
  },
  server: { deps: { inline: ['vuetify'] } },
  },
})
