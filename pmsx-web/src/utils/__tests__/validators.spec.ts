import { describe, expect, it } from 'vitest'
import { excelFileName } from '@/utils/download'
import { checkFile, MAX_FILE_SIZE } from '@/utils/files'
import { isCellRef, isColumnRef, isPasswordPolicyOk } from '@/utils/validators'

describe('폼 검증 규칙', () => {
  it('비밀번호는 9자 이상이고 영문·숫자·특수문자 중 3종을 섞어야 한다', () => {
    expect(isPasswordPolicyOk('pmsx1234!')).toBe(true)
    expect(isPasswordPolicyOk('pmsx12345')).toBe(false) // 2종
    expect(isPasswordPolicyOk('ab1!')).toBe(false) // 길이 부족
  })

  it('엑셀 열/셀 주소 형식을 검사하고 빈 값은 허용한다', () => {
    expect(isColumnRef('B')).toBe(true)
    expect(isColumnRef('AB1')).toBe(false)
    expect(isCellRef('C3')).toBe(true)
    expect(isCellRef('C0')).toBe(false)
    expect(isCellRef('')).toBe(true)
  })

  it('첨부는 용량과 확장자를 먼저 검사한다', () => {
    expect(checkFile({ name: '설계.pdf', size: 1024 })).toBe('ok')
    expect(checkFile({ name: 'run.exe', size: 1024 })).toBe('typeNotAllowed')
    expect(checkFile({ name: 'big.zip', size: MAX_FILE_SIZE + 1 })).toBe('tooLarge')
  })

  it('내보내기 파일명에 날짜를 붙이고 파일명 금지 문자를 바꾼다', () => {
    // Arrange
    const now = new Date(2026, 9, 15, 9, 5)
    // Act
    const name = excelFileName('감사/로그', now)
    // Assert
    expect(name).toBe('감사_로그_20261015_0905.xlsx')
  })
})
