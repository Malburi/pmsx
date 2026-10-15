import { createApp } from 'vue'
import App from '@/App.vue'
import { i18n } from '@/plugins/i18n'
import { pinia } from '@/plugins/pinia'
import { vuetify } from '@/plugins/vuetify'
import { router } from '@/router'
// Pretendard 가변 폰트 — 동적 서브셋(쓰는 글자 범위만 내려받음), 외부 CDN 없음
import 'pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css'
import '@/styles/main.scss'
import { installRowKeyboard } from '@/utils/a11y'

createApp(App)
  .use(pinia)
  .use(i18n)
  .use(vuetify)
  .use(router)
  .mount('#app')

// 표 행 Enter·Space 열기 (클릭으로 상세를 여는 목록들)
installRowKeyboard()
