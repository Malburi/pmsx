import type { ThemeDefinition } from 'vuetify'

// 전역 테마는 이 파일 한 곳에서 관리한다 (design-system §2-1).
// 화면 분석 뷰어 전용 색(요소 타입·레이어·CRUD·분석 상태)도 테마 색으로 등록해 다크 모드에서 자동 전환되게 한다.
// 사용: color="lane-api" 또는 CSS 에서 rgb(var(--v-theme-lane-api))

export const APP_NAME = 'PMS-X'

const sharedLight = {
  'type-button': '#3182f6',
  'type-input': '#0f9d76',
  'type-combo': '#8b5cf6',
  'type-grid': '#e07b00',
  'type-popup': '#d6336c',
  'type-other': '#64748b',
  'lane-ui': '#3182f6',
  'lane-front': '#0284c7',
  'lane-api': '#7c3aed',
  'lane-server': '#0f9d76',
  'lane-sql': '#d97706',
  'lane-db': '#b45309',
  'lane-post': '#64748b',
  'crud-c': '#12a150',
  'crud-r': '#3182f6',
  'crud-u': '#d97706',
  'crud-d': '#dc2626',
  'status-auto': '#94a3b8',
  'status-working': '#0284c7',
  'status-review': '#d97706',
  'status-done': '#12a150',
  'src-static': '#3182f6',
  'src-dynamic': '#12a150',
  'src-manual': '#7c3aed',
}

const sharedDark = {
  'type-button': '#6c9bff',
  'type-input': '#34d399',
  'type-combo': '#a78bfa',
  'type-grid': '#fb923c',
  'type-popup': '#f472b6',
  'type-other': '#94a3b8',
  'lane-ui': '#6c9bff',
  'lane-front': '#38bdf8',
  'lane-api': '#a78bfa',
  'lane-server': '#34d399',
  'lane-sql': '#fbbf24',
  'lane-db': '#f59e0b',
  'lane-post': '#94a3b8',
  'crud-c': '#22c55e',
  'crud-r': '#6c9bff',
  'crud-u': '#f59e0b',
  'crud-d': '#f87171',
  'status-auto': '#94a3b8',
  'status-working': '#38bdf8',
  'status-review': '#fbbf24',
  'status-done': '#4ade80',
  'src-static': '#6c9bff',
  'src-dynamic': '#4ade80',
  'src-manual': '#c4b5fd',
}

// 방향: 토스·노션풍 클린 벤토 (2026-10) — 웜 그레이 바탕 + 순백 카드, 포인트 블루 + 파스텔 보조색.
// 상태색(success·warning·error·info)은 tonal 칩(파스텔 배경 + 진한 글자)에서 본문 대비 4.5:1 이상이 되도록 진하게 잡는다.
export const lightTheme: ThemeDefinition = {
  dark: false,
  colors: {
    primary: '#1e6ae6',
    secondary: '#4e5968',
    background: '#f4f5f7',
    surface: '#ffffff',
    'on-background': '#191f28',
    'on-surface': '#191f28',
    'surface-2': '#f2f4f6',
    line: '#e9ecf0',
    muted: '#646e7b',
    'code-bg': '#191f28',
    'code-fg': '#e5e8eb',
    error: '#d92d3a',
    warning: '#b45309',
    success: '#08875a',
    info: '#1b64da',
    // 파스텔 보조색 — 벤토 타일 배경·아이콘 배지
    mint: '#00a88f',
    lavender: '#7b61ff',
    peach: '#f2774a',
    'pastel-blue': '#edf4ff',
    'pastel-mint': '#e1f8f2',
    'pastel-lavender': '#f0ecff',
    'pastel-peach': '#fff0e8',
    'pastel-yellow': '#fff8e1',
    ...sharedLight,
  },
}

export const darkTheme: ThemeDefinition = {
  dark: true,
  colors: {
    primary: '#4d94ff',
    secondary: '#b0b8c1',
    background: '#17171c',
    surface: '#202027',
    'on-background': '#ececf1',
    'on-surface': '#ececf1',
    'surface-2': '#2a2a33',
    line: '#2f2f3a',
    muted: '#9e9ea9',
    'code-bg': '#121216',
    'code-fg': '#e5e8eb',
    error: '#ff6b72',
    warning: '#ffb547',
    success: '#3dd598',
    info: '#5ea8ff',
    mint: '#2fd4b8',
    lavender: '#a895ff',
    peach: '#ff9a72',
    'pastel-blue': '#1d2a42',
    'pastel-mint': '#16322d',
    'pastel-lavender': '#29254a',
    'pastel-peach': '#3a2822',
    'pastel-yellow': '#3a3220',
    ...sharedDark,
  },
}
