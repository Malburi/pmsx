import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as codeApi from '@/api/codeApi'
import { clearCodeCache, useCodes } from '@/composables/useCodes'

vi.mock('@/api/codeApi')

describe('useCodes', () => {
  beforeEach(() => {
    clearCodeCache()
    vi.mocked(codeApi.getCodeMapApi).mockReset()
  })

  it('같은 그룹을 동시에 요청해도 서버는 한 번만 부른다', async () => {
    // Arrange
    vi.mocked(codeApi.getCodeMapApi).mockResolvedValue({
      ORG_TYPE: [{ code: 'OWN', name: '자사', sortOrder: 1, useYn: true, attr1: null, attr2: null }],
    })
    // Act
    const a = useCodes(['ORG_TYPE'])
    const b = useCodes(['ORG_TYPE'])
    await flushPromises()
    // Assert
    expect(codeApi.getCodeMapApi).toHaveBeenCalledTimes(1)
    expect(a.items('ORG_TYPE')).toEqual([{ title: '자사', value: 'OWN' }])
    expect(b.label('ORG_TYPE', 'OWN')).toBe('자사')
  })

  it('모르는 코드는 코드 값 그대로 보여준다', async () => {
    vi.mocked(codeApi.getCodeMapApi).mockResolvedValue({ GRADE: [] })
    const { label } = useCodes(['GRADE'])
    await flushPromises()
    expect(label('GRADE', 'X1')).toBe('X1')
  })
})
