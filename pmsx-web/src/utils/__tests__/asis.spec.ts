import { describe, expect, it } from 'vitest'
import { boxToPercent, isButtonLike, laneColor, typeColor } from '@/utils/asis'

describe('asis 표시 규칙', () => {
  it('박스를 캡처 크기 기준 백분율로 바꾼다', () => {
    // Arrange
    const box = { x: 90, y: 56, w: 180, h: 28 }
    // Act
    const pct = boxToPercent(box, 900, 560)
    // Assert
    expect(pct).toEqual({ left: '10%', top: '10%', width: '20%', height: '5%' })
  })

  it('요소 타입을 테마 색 이름으로 매핑하고 모르는 타입은 기타 색을 쓴다', () => {
    expect(typeColor('button')).toBe('type-button')
    expect(typeColor('date')).toBe('type-input')
    expect(typeColor('other')).toBe('type-other')
  })

  it('레이어 색 이름과 버튼 계열 판별', () => {
    expect(laneColor('SERVER')).toBe('lane-server')
    expect(isButtonLike('popup')).toBe(true)
    expect(isButtonLike('grid')).toBe(false)
  })
})
