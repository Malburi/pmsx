// 핫스팟 편집기(FR-AS-019) 좌표 계산 — 화면(픽셀)과 캡처 원본 좌표 사이 변환, 드래그 이동·크기 조절.
// DOM 에 의존하지 않는 순수 함수만 둔다 (단위 테스트 대상).
import type { Box } from '@/types/api'

/** 새로 그리거나 줄일 때 허용하는 최소 크기 (캡처 원본 px) */
export const MIN_BOX = 8

export type ResizeHandle = 'n' | 's' | 'e' | 'w' | 'ne' | 'nw' | 'se' | 'sw'

export const RESIZE_HANDLES: ResizeHandle[] = ['nw', 'n', 'ne', 'e', 'se', 's', 'sw', 'w']

export interface Point {
  x: number
  y: number
}

export interface Size {
  width: number
  height: number
}

/** 화면에 그려진 캡처 영역(getBoundingClientRect) */
export interface StageRect {
  left: number
  top: number
  width: number
  height: number
}

const clamp = (v: number, min: number, max: number) => Math.min(Math.max(v, min), max)

/** 마우스 좌표(clientX/Y) → 캡처 원본 좌표. 캡처 밖으로 나가면 가장자리에 붙인다. */
export const clientToCapture = (clientX: number, clientY: number, rect: StageRect, cap: Size): Point => {
  const sx = rect.width > 0 ? cap.width / rect.width : 1
  const sy = rect.height > 0 ? cap.height / rect.height : 1
  return {
    x: clamp((clientX - rect.left) * sx, 0, cap.width),
    y: clamp((clientY - rect.top) * sy, 0, cap.height),
  }
}

/** 화면 픽셀 이동량 → 캡처 원본 이동량 */
export const deltaToCapture = (dx: number, dy: number, rect: StageRect, cap: Size): Point => ({
  x: rect.width > 0 ? (dx * cap.width) / rect.width : dx,
  y: rect.height > 0 ? (dy * cap.height) / rect.height : dy,
})

/** 두 점(드래그 시작·끝)으로 정규화된 박스를 만든다 (방향과 무관하게 x,y 가 좌상단) */
export const rectFromPoints = (a: Point, b: Point): Box => ({
  x: Math.min(a.x, b.x),
  y: Math.min(a.y, b.y),
  w: Math.abs(b.x - a.x),
  h: Math.abs(b.y - a.y),
})

export const isDrawable = (box: Box, min = MIN_BOX) => box.w >= min && box.h >= min

export const roundBox = (box: Box): Box => ({
  x: Math.round(box.x),
  y: Math.round(box.y),
  w: Math.round(box.w),
  h: Math.round(box.h),
})

/** 박스를 통째로 옮긴다 — 캡처 밖으로 나가지 않게 막는다 */
export const moveBox = (box: Box, dx: number, dy: number, cap: Size): Box => ({
  ...box,
  x: clamp(box.x + dx, 0, Math.max(0, cap.width - box.w)),
  y: clamp(box.y + dy, 0, Math.max(0, cap.height - box.h)),
})

/** 핸들 방향으로 크기를 조절한다. 반대쪽 변은 고정되고, 최소 크기·캡처 경계를 지킨다. */
export const resizeBox = (box: Box, handle: ResizeHandle, dx: number, dy: number, cap: Size, min = MIN_BOX): Box => {
  let left = box.x
  let top = box.y
  let right = box.x + box.w
  let bottom = box.y + box.h
  if (handle.includes('w')) left = clamp(left + dx, 0, right - min)
  if (handle.includes('e')) right = clamp(right + dx, left + min, cap.width)
  if (handle.includes('n')) top = clamp(top + dy, 0, bottom - min)
  if (handle.includes('s')) bottom = clamp(bottom + dy, top + min, cap.height)
  return { x: left, y: top, w: right - left, h: bottom - top }
}

/** 핸들 위치(박스 대비 %) — 템플릿에서 절대 위치로 쓴다 */
export const handlePosition = (h: ResizeHandle) => ({
  left: h.includes('w') ? '0%' : h.includes('e') ? '100%' : '50%',
  top: h.includes('n') ? '0%' : h.includes('s') ? '100%' : '50%',
})

export const handleCursor = (h: ResizeHandle) => `${h}-resize`

/** 수동 요소 ID 자동 제안: manualEl1, manualEl2 … (이미 있는 코드와 겹치지 않게) */
export const nextElementCode = (existing: string[], prefix = 'manualEl') => {
  const used = new Set(existing)
  let n = 1
  while (used.has(`${prefix}${n}`)) n += 1
  return `${prefix}${n}`
}

/** 두 박스가 같은지 (드래그 후 실제로 바뀌었을 때만 저장) */
export const sameBox = (a: Box | null | undefined, b: Box | null | undefined) =>
  !!a && !!b && a.x === b.x && a.y === b.y && a.w === b.w && a.h === b.h
