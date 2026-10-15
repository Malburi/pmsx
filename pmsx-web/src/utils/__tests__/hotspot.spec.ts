import { describe, expect, it } from 'vitest'
import {
  MIN_BOX, clientToCapture, deltaToCapture, isDrawable, moveBox, nextElementCode, rectFromPoints, resizeBox, roundBox, sameBox,
} from '@/utils/hotspot'

// 캡처 900×560 이 화면에 450×280(절반)으로 그려진 상황
const cap = { width: 900, height: 560 }
const rect = { left: 100, top: 50, width: 450, height: 280 }

describe('핫스팟 좌표 변환', () => {
  it('화면 좌표를 캡처 원본 좌표로 바꾼다 (배율 2배)', () => {
    // Act
    const p = clientToCapture(145, 78, rect, cap)
    // Assert
    expect(p).toEqual({ x: 90, y: 56 })
  })

  it('캡처 밖으로 나간 좌표는 가장자리에 붙인다', () => {
    expect(clientToCapture(0, 0, rect, cap)).toEqual({ x: 0, y: 0 })
    expect(clientToCapture(9999, 9999, rect, cap)).toEqual({ x: 900, y: 560 })
  })

  it('화면 이동량을 캡처 이동량으로 바꾼다', () => {
    expect(deltaToCapture(10, -5, rect, cap)).toEqual({ x: 20, y: -10 })
  })

  it('어느 방향으로 드래그해도 좌상단 기준 박스가 된다', () => {
    expect(rectFromPoints({ x: 200, y: 100 }, { x: 120, y: 160 })).toEqual({ x: 120, y: 100, w: 80, h: 60 })
  })

  it('최소 크기보다 작은 박스는 그리지 않는다', () => {
    expect(isDrawable({ x: 0, y: 0, w: MIN_BOX, h: MIN_BOX })).toBe(true)
    expect(isDrawable({ x: 0, y: 0, w: 3, h: 40 })).toBe(false)
  })
})

describe('핫스팟 이동·크기 조절', () => {
  const box = { x: 690, y: 84, w: 60, h: 24 }

  it('이동은 캡처 경계 안으로 제한된다', () => {
    expect(moveBox(box, 500, 0, cap)).toEqual({ ...box, x: 840 })
    expect(moveBox(box, -1000, -1000, cap)).toEqual({ ...box, x: 0, y: 0 })
  })

  it('남동(se) 핸들은 오른쪽·아래 변만 늘린다', () => {
    expect(resizeBox(box, 'se', 20, 6, cap)).toEqual({ x: 690, y: 84, w: 80, h: 30 })
  })

  it('북서(nw) 핸들은 왼쪽·위 변을 움직이고 반대 변은 고정한다', () => {
    expect(resizeBox(box, 'nw', -10, -4, cap)).toEqual({ x: 680, y: 80, w: 70, h: 28 })
  })

  it('줄일 때 최소 크기 아래로 내려가지 않는다', () => {
    const r = resizeBox(box, 'e', -500, 0, cap)
    expect(r.w).toBe(MIN_BOX)
    expect(r.x).toBe(690)
  })

  it('반올림·비교 헬퍼', () => {
    expect(roundBox({ x: 1.4, y: 2.6, w: 10.5, h: 3.49 })).toEqual({ x: 1, y: 3, w: 11, h: 3 })
    expect(sameBox(box, { ...box })).toBe(true)
    expect(sameBox(box, null)).toBe(false)
  })

  it('수동 요소 ID 는 기존 코드와 겹치지 않게 제안한다', () => {
    expect(nextElementCode(['btnSave', 'manualEl1', 'manualEl2'])).toBe('manualEl3')
    expect(nextElementCode([])).toBe('manualEl1')
  })
})
