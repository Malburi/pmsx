import { describe, expect, it } from 'vitest'
import { isSafeHref, parseInline, parseMarkdown } from '@/utils/markdown'

describe('메모 Markdown 파서', () => {
  it('제목·목록·코드 블록·표를 블록으로 나눈다', () => {
    // Arrange
    const src = '# 규칙\n- 입사일 검증\n- 퇴직자 논리삭제\n\n```js\nif (a) b()\n```\n| 권한 | 동작 |\n|---|---|\n| 인사 | 저장 |'
    // Act
    const blocks = parseMarkdown(src)
    // Assert
    expect(blocks.map((b) => b.t)).toEqual(['h', 'ul', 'pre', 'table'])
    expect(blocks[1]).toMatchObject({ t: 'ul', items: [[{ t: 'text', v: '입사일 검증' }], [{ t: 'text', v: '퇴직자 논리삭제' }]] })
    expect(blocks[2]).toMatchObject({ t: 'pre', v: 'if (a) b()', lang: 'js' })
    expect(blocks[3]).toMatchObject({ t: 'table', rows: [[[{ t: 'text', v: '인사' }], [{ t: 'text', v: '저장' }]]] })
  })

  it('굵게·기울임·코드·링크를 인라인 노드로 만든다', () => {
    expect(parseInline('**주의** `USE_YN` *필수* [가이드](https://example.local/g)')).toEqual([
      { t: 'strong', c: [{ t: 'text', v: '주의' }] },
      { t: 'text', v: ' ' },
      { t: 'code', v: 'USE_YN' },
      { t: 'text', v: ' ' },
      { t: 'em', c: [{ t: 'text', v: '필수' }] },
      { t: 'text', v: ' ' },
      { t: 'link', href: 'https://example.local/g', c: [{ t: 'text', v: '가이드' }] },
    ])
  })

  it('HTML 태그는 해석하지 않고 글자로 남긴다 (XSS 방지)', () => {
    const blocks = parseMarkdown('<script>alert(1)</script><img src=x onerror=alert(1)>')
    expect(blocks).toEqual([{ t: 'p', c: [{ t: 'text', v: '<script>alert(1)</script><img src=x onerror=alert(1)>' }] }])
  })

  it('javascript: 링크는 링크로 만들지 않는다', () => {
    expect(isSafeHref('javascript:alert(1)')).toBe(false)
    expect(isSafeHref('https://a.b')).toBe(true)
    expect(parseInline('[x](javascript:alert(1))')).toEqual([{ t: 'text', v: '[x](javascript:alert(1)' }, { t: 'text', v: ')' }])
  })

  it('문단 안 줄바꿈은 보존한다', () => {
    expect(parseMarkdown('첫 줄\n둘째 줄')).toEqual([{ t: 'p', c: [{ t: 'text', v: '첫 줄' }, { t: 'br' }, { t: 'text', v: '둘째 줄' }] }])
  })
})
