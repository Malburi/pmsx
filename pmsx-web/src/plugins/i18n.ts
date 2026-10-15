import { createI18n } from 'vue-i18n'
import { ko as vuetifyKo } from 'vuetify/locale'
import ko from '@/locales/ko.json'
import koR2 from '@/locales/ko.r2.json'
import koR3 from '@/locales/ko.r3.json'

type Messages = { [key: string]: string | Messages }

// R2·R3 화면 문구는 별도 파일로 두고 기본 문구에 깊게 합친다 (같은 상위 키 admin·asis 를 덮어쓰지 않도록)
const deepMerge = (base: Messages, extra: Messages): Messages => {
  const out: Messages = { ...base }
  Object.entries(extra).forEach(([k, v]) => {
    const prev = out[k]
    out[k] = typeof v === 'object' && typeof prev === 'object' ? deepMerge(prev, v) : v
  })
  return out
}

// R0 는 한국어만 제공. 키 구조(<도메인>.<화면>.<엘리먼트>)는 다국어 추가를 전제로 유지한다.
export const i18n = createI18n({
  legacy: false,
  locale: 'ko',
  fallbackLocale: 'ko',
  missingWarn: import.meta.env.DEV,
  fallbackWarn: import.meta.env.DEV,
  messages: { ko: { ...deepMerge(deepMerge(ko as Messages, koR2 as Messages), koR3 as Messages), $vuetify: vuetifyKo } },
  datetimeFormats: {
    ko: {
      short: { year: 'numeric', month: '2-digit', day: '2-digit' },
      long: { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false },
    },
  },
})
