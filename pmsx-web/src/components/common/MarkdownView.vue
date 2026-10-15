<script setup lang="ts">
import { computed, h, type VNode } from 'vue'
import { parseMarkdown, type Block, type Inline } from '@/utils/markdown'

// 메모 Markdown 을 VNode 로 직접 그린다 — HTML 문자열·v-html 을 쓰지 않으므로
// 입력에 섞인 태그·스크립트는 글자로만 보인다 (security-frontend §2, XSS 방지).
const props = defineProps<{ source: string }>()

// ─────────── 헬퍼 ───────────
const renderInline = (nodes: Inline[]): (VNode | string)[] => nodes.map((n) => {
  switch (n.t) {
    case 'text': return n.v
    case 'br': return h('br')
    case 'code': return h('code', { class: 'md-code' }, n.v)
    case 'strong': return h('strong', renderInline(n.c))
    case 'em': return h('em', renderInline(n.c))
    case 'del': return h('del', renderInline(n.c))
    case 'link': return h('a', { href: n.href, target: '_blank', rel: 'noopener noreferrer' }, renderInline(n.c))
    default: return ''
  }
})

const renderBlock = (b: Block, i: number): VNode => {
  switch (b.t) {
    case 'h': return h(`h${Math.min(b.level + 2, 6)}`, { key: i, class: 'md-h' }, renderInline(b.c))
    case 'p': return h('p', { key: i }, renderInline(b.c))
    case 'ul':
    case 'ol': return h(b.t, { key: i }, b.items.map((it, k) => h('li', { key: k }, renderInline(it))))
    case 'pre': return h('pre', { key: i, class: 'md-pre' }, h('code', b.v))
    case 'quote': return h('blockquote', { key: i }, renderInline(b.c))
    case 'hr': return h('hr', { key: i })
    case 'table': return h('div', { key: i, class: 'md-table-wrap' }, h('table', [
      h('thead', h('tr', b.head.map((c, k) => h('th', { key: k }, renderInline(c))))),
      h('tbody', b.rows.map((r, k) => h('tr', { key: k }, r.map((c, j) => h('td', { key: j }, renderInline(c)))))),
    ]))
    default: return h('span')
  }
}

// ─────────── computed ───────────
const blocks = computed(() => parseMarkdown(props.source))
const Rendered = () => blocks.value.map(renderBlock)
</script>

<template>
  <div class="md"><Rendered /></div>
</template>

<style scoped>
.md {
  font-size: 13px;
  line-height: 1.6;
  word-break: break-word;
}

.md :deep(p),
.md :deep(ul),
.md :deep(ol),
.md :deep(blockquote),
.md :deep(.md-pre),
.md :deep(.md-table-wrap) {
  margin: 0 0 8px;
}

.md :deep(ul),
.md :deep(ol) {
  padding-left: 20px;
}

.md :deep(.md-h) {
  margin: 4px 0 6px;
  font-size: 14px;
  font-weight: 700;
}

.md :deep(.md-code) {
  padding: 0 4px;
  border-radius: 3px;
  background: rgb(var(--v-theme-surface-2));
  font-family: var(--pmsx-mono);
  font-size: 12px;
}

.md :deep(.md-pre) {
  padding: 8px 10px;
  border-radius: 6px;
  background: rgb(var(--v-theme-code-bg));
  color: rgb(var(--v-theme-code-fg));
  font-family: var(--pmsx-mono);
  font-size: 12px;
  overflow-x: auto;
}

.md :deep(blockquote) {
  padding-left: 10px;
  border-left: 3px solid rgb(var(--v-theme-line));
  color: rgb(var(--v-theme-muted));
}

.md :deep(.md-table-wrap) {
  overflow-x: auto;
}

.md :deep(table) {
  border-collapse: collapse;
  font-size: 12px;
}

.md :deep(th),
.md :deep(td) {
  border: 1px solid rgb(var(--v-theme-line));
  padding: 3px 8px;
  text-align: left;
}

.md :deep(a) {
  color: rgb(var(--v-theme-primary));
}

.md :deep(> :last-child) {
  margin-bottom: 0;
}
</style>
