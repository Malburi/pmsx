// As-Is 화면 내부에서만 쓰는 UI 타입 (API 명세 타입은 api.ts)
import type { Lane } from '@/types/api'

/** 연결 추가 대화상자의 출발점 — 요소 또는 워크플로우 단계(노드) */
export interface EdgeFrom {
  elementId?: string
  nodeId?: string
  label: string
  lane: Lane | null
}

/** 워크플로우 단계의 연결 보정 동작 */
export type EdgeAction = 'confirm' | 'unconfirm' | 'reject' | 'activate' | 'edit' | 'delete'
