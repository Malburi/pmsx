import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MarkdownView from '@/components/common/MarkdownView.vue'

describe('MarkdownView', () => {
  it('Markdown 을 요소로 그린다', () => {
    // Act
    const w = mount(MarkdownView, { props: { source: '## 규칙\n**굵게** `코드`\n\n- 하나\n- 둘' } })
    // Assert
    expect(w.find('h4').text()).toBe('규칙')
    expect(w.find('strong').text()).toBe('굵게')
    expect(w.find('code').text()).toBe('코드')
    expect(w.findAll('li')).toHaveLength(2)
  })

  it('입력에 섞인 태그는 실행되지 않고 글자로 보인다', () => {
    const w = mount(MarkdownView, { props: { source: '<script>alert(1)</script> <img src=x onerror=alert(1)>' } })
    expect(w.find('script').exists()).toBe(false)
    expect(w.find('img').exists()).toBe(false)
    expect(w.text()).toContain('<script>alert(1)</script>')
  })

  it('외부 링크는 새 창 + noopener 로 연다', () => {
    const w = mount(MarkdownView, { props: { source: '[문서](https://wiki.local/a)' } })
    const a = w.find('a')
    expect(a.attributes('href')).toBe('https://wiki.local/a')
    expect(a.attributes('rel')).toBe('noopener noreferrer')
  })
})
