// As-Is 뷰어 공통 표시 규칙 (색은 themeConfig 의 테마 색 이름, 라벨은 i18n 키)
import type { AnalysisStatus, EdgeSource, ElementType, Lane } from '@/types/api'

export const STATUS_ORDER: AnalysisStatus[] = ['AUTO', 'WORKING', 'REVIEW', 'DONE']

export const statusColor = (s: AnalysisStatus) => `status-${s.toLowerCase()}`

const TYPE_COLOR: Partial<Record<ElementType, string>> = {
  button: 'type-button',
  input: 'type-input',
  date: 'type-input',
  check: 'type-input',
  radio: 'type-input',
  file: 'type-input',
  combo: 'type-combo',
  grid: 'type-grid',
  tree: 'type-grid',
  tab: 'type-grid',
  popup: 'type-popup',
  link: 'type-popup',
}

export const typeColor = (t: ElementType) => TYPE_COLOR[t] ?? 'type-other'

// 범례에 표시할 대표 타입 그룹
export const TYPE_LEGEND: { key: string; color: string }[] = [
  { key: 'button', color: 'type-button' },
  { key: 'input', color: 'type-input' },
  { key: 'combo', color: 'type-combo' },
  { key: 'grid', color: 'type-grid' },
  { key: 'popup', color: 'type-popup' },
]

export const laneColor = (l: Lane) => `lane-${l.toLowerCase()}`

export const sourceColor = (s: EdgeSource) => `src-${s}`

// 신뢰도 90% 미만 연결은 점선으로 표시한다 (03 화면설계 §3-④)
export const WEAK_CONFIDENCE = 0.9

export const isButtonLike = (t: ElementType) => t === 'button' || t === 'popup' || t === 'link'

export const CRUD_LETTERS = ['C', 'R', 'U', 'D'] as const

export const crudColor = (c: string) => `crud-${c.toLowerCase()}`

export const UI_TECH_OPTIONS = [
  'jsp-jquery', 'nexacro', 'miplatform', 'xplatform', 'websquare', 'vue', 'react', 'winforms', 'aspnet-webforms', 'aspnet-mvc', 'other',
]
export const SERVER_TECH_OPTIONS = ['spring-mybatis', 'spring-jpa', 'egov', 'dotnet', 'other']
export const DB_TYPE_OPTIONS = ['oracle', 'tibero', 'mssql', 'postgresql', 'mysql', 'mariadb', 'other']

// 박스를 캡처 크기 기준 백분율로 바꿔 배율과 상관없이 같은 위치에 그린다.
export const boxToPercent = (box: { x: number; y: number; w: number; h: number }, width: number, height: number) => ({
  left: `${(box.x / width) * 100}%`,
  top: `${(box.y / height) * 100}%`,
  width: `${(box.w / width) * 100}%`,
  height: `${(box.h / height) * 100}%`,
})
