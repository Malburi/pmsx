import 'vuetify/styles'
import { createVuetify } from 'vuetify'
import { createVueI18nAdapter } from 'vuetify/locale/adapters/vue-i18n'
import { useI18n } from 'vue-i18n'
import { i18n } from '@/plugins/i18n'
import { iconifyAliases, iconifySet } from '@/plugins/iconify'
import { darkTheme, lightTheme } from '@/themeConfig'

export const vuetify = createVuetify({
  theme: {
    defaultTheme: 'light',
    themes: { light: lightTheme, dark: darkTheme },
  },
  icons: {
    defaultSet: 'iconify',
    aliases: iconifyAliases,
    sets: { iconify: iconifySet },
  },
  locale: {
    // vue-i18n 의 메시지 스키마 제네릭이 Vuetify 어댑터 타입과 맞지 않아 any 로 넘긴다 (런타임 동작은 동일)
    adapter: createVueI18nAdapter({ i18n: i18n as any, useI18n }), // eslint-disable-line @typescript-eslint/no-explicit-any
  },
  // 클린 벤토 기본값 — 채움형 입력(회색 면 + 포커스 블루 링), 둥근 버튼, 그림자 없는 카드(면으로 구분)
  defaults: {
    VBtn: { variant: 'flat', rounded: true },
    VTextField: { variant: 'filled', density: 'compact', hideDetails: 'auto', rounded: true },
    VTextarea: { variant: 'filled', density: 'compact', hideDetails: 'auto', rounded: true },
    VSelect: { variant: 'filled', density: 'compact', hideDetails: 'auto', rounded: true },
    VAutocomplete: { variant: 'filled', density: 'compact', hideDetails: 'auto', rounded: true },
    VCombobox: { variant: 'filled', density: 'compact', hideDetails: 'auto', rounded: true },
    VFileInput: { variant: 'filled', density: 'compact', hideDetails: 'auto', rounded: true },
    VCard: { rounded: 'xl', elevation: 0 },
    VChip: { rounded: 'pill' },
    VAlert: { rounded: 'lg' },
    VMenu: { VList: { rounded: 'lg' } },
    VTabs: { color: 'primary' },
  },
})
