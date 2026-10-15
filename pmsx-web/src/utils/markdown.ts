// 업무 규칙 메모용 경량 Markdown 파서 (FR-AS-021).
// HTML 문자열을 만들지 않고 구조(AST)만 만든다 → MarkdownView 가 VNode 로 렌더링하므로
// 입력에 섞인 <script> 등은 그냥 글자로 보인다 (v-html 미사용, security-frontend §2).

export type Inline =
  | { t: 'text'; v: string }
  | { t: 'code'; v: string }
  | { t: 'strong'; c: Inline[] }
  | { t: 'em'; c: Inline[] }
  | { t: 'del'; c: Inline[] }
  | { t: 'link'; href: string; c: Inline[] }
  | { t: 'br' }

export type Block =
  | { t: 'h'; level: 1 | 2 | 3 | 4; c: Inline[] }
  | { t: 'p'; c: Inline[] }
  | { t: 'ul' | 'ol'; items: Inline[][] }
  | { t: 'pre'; v: string; lang: string | null }
  | { t: 'quote'; c: Inline[] }
  | { t: 'hr' }
  | { t: 'table'; head: Inline[][]; rows: Inline[][][] }

/** 링크는 http(s)·메일만 허용 (javascript: 등 차단) */
export const isSafeHref = (href: string) => /^(https?:\/\/|mailto:)/i.test(href.trim())

const INLINE_RULES: { re: RegExp; make: (m: RegExpExecArray) => Inline }[] = [
  { re: /`([^`]+)`/, make: (m) => ({ t: 'code', v: m[1] }) },
  { re: /\*\*([^*]+)\*\*/, make: (m) => ({ t: 'strong', c: parseInline(m[1]) }) },
  { re: /~~([^~]+)~~/, make: (m) => ({ t: 'del', c: parseInline(m[1]) }) },
  { re: /(?<![\w*])\*([^*\s][^*]*)\*(?!\w)/, make: (m) => ({ t: 'em', c: parseInline(m[1]) }) },
  {
    re: /\[([^\]]+)\]\(([^)\s]+)\)/,
    make: (m) => (isSafeHref(m[2]) ? { t: 'link', href: m[2], c: parseInline(m[1]) } : { t: 'text', v: m[0] }),
  },
]

export const parseInline = (src: string): Inline[] => {
  const out: Inline[] = []
  let rest = src
  while (rest.length) {
    // 가장 앞에서 시작하는 규칙을 고른다
    let best: { index: number; m: RegExpExecArray; make: (m: RegExpExecArray) => Inline } | null = null
    for (const rule of INLINE_RULES) {
      const m = rule.re.exec(rest)
      if (m && (best === null || m.index < best.index)) best = { index: m.index, m, make: rule.make }
    }
    if (!best) {
      out.push({ t: 'text', v: rest })
      break
    }
    if (best.index > 0) out.push({ t: 'text', v: rest.slice(0, best.index) })
    out.push(best.make(best.m))
    rest = rest.slice(best.index + best.m[0].length)
  }
  return out
}

const splitRow = (line: string) => line.trim().replace(/^\||\|$/g, '').split('|').map((c) => c.trim())

const isTableSep = (line: string) => /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/.test(line)

export const parseMarkdown = (src: string): Block[] => {
  const lines = src.replace(/\r\n?/g, '\n').split('\n')
  const blocks: Block[] = []
  let i = 0
  const para: string[] = []

  const flushPara = () => {
    if (!para.length) return
    // 문단 안 줄바꿈은 보존한다 (메모는 줄 단위로 쓰는 경우가 많다)
    const c: Inline[] = []
    para.forEach((l, k) => {
      if (k > 0) c.push({ t: 'br' })
      c.push(...parseInline(l))
    })
    blocks.push({ t: 'p', c })
    para.length = 0
  }

  while (i < lines.length) {
    const line = lines[i]
    const fence = /^```\s*([\w-]*)\s*$/.exec(line)
    if (fence) {
      flushPara()
      const body: string[] = []
      i += 1
      while (i < lines.length && !/^```\s*$/.test(lines[i])) body.push(lines[i++])
      i += 1 // 닫는 ``` (없으면 끝까지)
      blocks.push({ t: 'pre', v: body.join('\n'), lang: fence[1] || null })
      continue
    }
    const h = /^(#{1,4})\s+(.+)$/.exec(line)
    if (h) {
      flushPara()
      blocks.push({ t: 'h', level: h[1].length as 1 | 2 | 3 | 4, c: parseInline(h[2].trim()) })
      i += 1
      continue
    }
    if (/^\s*(-{3,}|\*{3,})\s*$/.test(line)) {
      flushPara()
      blocks.push({ t: 'hr' })
      i += 1
      continue
    }
    if (/^\s*[-*+]\s+/.test(line) || /^\s*\d+[.)]\s+/.test(line)) {
      flushPara()
      const ordered = /^\s*\d+[.)]\s+/.test(line)
      const re = ordered ? /^\s*\d+[.)]\s+(.*)$/ : /^\s*[-*+]\s+(.*)$/
      const items: Inline[][] = []
      while (i < lines.length && re.test(lines[i])) {
        items.push(parseInline((re.exec(lines[i]) as RegExpExecArray)[1]))
        i += 1
      }
      blocks.push({ t: ordered ? 'ol' : 'ul', items })
      continue
    }
    if (/^\s*>/.test(line)) {
      flushPara()
      const body: string[] = []
      while (i < lines.length && /^\s*>/.test(lines[i])) body.push(lines[i++].replace(/^\s*>\s?/, ''))
      blocks.push({ t: 'quote', c: parseInline(body.join(' ')) })
      continue
    }
    if (line.includes('|') && i + 1 < lines.length && isTableSep(lines[i + 1])) {
      flushPara()
      const head = splitRow(line).map(parseInline)
      i += 2
      const rows: Inline[][][] = []
      while (i < lines.length && lines[i].includes('|') && lines[i].trim()) rows.push(splitRow(lines[i++]).map(parseInline))
      blocks.push({ t: 'table', head, rows })
      continue
    }
    if (!line.trim()) {
      flushPara()
      i += 1
      continue
    }
    para.push(line)
    i += 1
  }
  flushPara()
  return blocks
}
