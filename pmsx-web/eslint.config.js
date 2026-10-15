// ESLint flat config — Vue 3 + TypeScript 권장 규칙 (coding-styles · vue-sfc-structure 보조)
import pluginVue from 'eslint-plugin-vue'
import { defineConfigWithVueTs, vueTsConfigs } from '@vue/eslint-config-typescript'
import globals from 'globals'

export default defineConfigWithVueTs(
  {
    name: 'pmsx/ignores',
    ignores: ['dist/**', 'node_modules/**', 'coverage/**', 'src/typed-router.d.ts', 'src/plugins/iconify/ri-subset.json'],
  },
  pluginVue.configs['flat/recommended'],
  vueTsConfigs.recommended,
  {
    name: 'pmsx/rules',
    files: ['**/*.{ts,vue,js,mjs}'],
    languageOptions: {
      globals: { ...globals.browser, ...globals.node },
    },
    rules: {
      // 회사 표준: 함수는 arrow function 으로 선언 (vue-sfc-structure §1)
      'func-style': ['error', 'expression'],
      'no-console': ['warn', { allow: ['warn', 'error'] }],
      'prefer-const': 'error',
      eqeqeq: ['error', 'always', { null: 'ignore' }],
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrors: 'none' }],
      // 외부 응답/어댑터 경계에서만 any 를 쓰며, 신규 코드는 지양한다
      '@typescript-eslint/no-explicit-any': 'warn',
      // 블록 순서 표준 (vue-sfc-structure §1)
      'vue/block-order': ['error', { order: ['route', 'script', 'template', 'style'] }],
      'vue/component-name-in-template-casing': ['error', 'PascalCase', { registeredComponentsOnly: false }],
      'vue/no-v-html': 'error',
      // VDataTable 의 `#item.<key>` 슬롯 이름을 허용
      'vue/valid-v-slot': ['error', { allowModifiers: true }],
      // 포맷팅 성격의 규칙은 끈다 (가독성을 위해 한 줄 속성 나열을 허용)
      'vue/max-attributes-per-line': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/multiline-html-element-content-newline': 'off',
      'vue/html-self-closing': 'off',
      'vue/html-closing-bracket-newline': 'off',
      'vue/html-indent': 'off',
      'vue/first-attribute-linebreak': 'off',
    },
  },
  {
    // 파일 기반 라우팅 페이지·레이아웃은 파일명이 곧 경로라 한 단어여도 된다
    name: 'pmsx/pages',
    files: ['src/pages/**/*.vue', 'src/@layouts/**/*.vue'],
    rules: { 'vue/multi-word-component-names': 'off' },
  },
)
