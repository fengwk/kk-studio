export type MarkdownSegment =
  | { type: 'markdown'; content: string; key: string }
  | { type: 'mermaid'; content: string; key: string }

/**
 * 从全文中抽出「已闭合」的 ```mermaid ... ``` 块。
 * 未闭合 fence 仍留在 markdown 段里（显示为普通代码/文本），避免半截图反复 render。
 */
export function splitMarkdownSegments(source: string): MarkdownSegment[] {
  const text = source ?? ''
  if (!text) {
    return []
  }

  // 仅匹配闭合 fence：```mermaid\n...\n```
  const fencePattern = /```mermaid[ \t]*\r?\n([\s\S]*?)```/gi
  const segments: MarkdownSegment[] = []
  let lastIndex = 0
  let match: RegExpExecArray | null
  let part = 0

  while ((match = fencePattern.exec(text)) !== null) {
    const full = match[0]
    const body = match[1] ?? ''
    const start = match.index

    if (start > lastIndex) {
      const md = text.slice(lastIndex, start)
      if (md.length > 0) {
        segments.push({ type: 'markdown', content: md, key: `md-${part}` })
        part += 1
      }
    }

    const code = body.replace(/^\uFEFF/, '').replace(/\s+$/u, '')
    segments.push({
      type: 'mermaid',
      content: code,
      // 用内容哈希稳定 key：同一图在后续流式追加文字时 key/code 不变 → 不重挂
      key: `mermaid-${hashString(code)}`,
    })
    part += 1
    lastIndex = start + full.length
  }

  if (lastIndex < text.length) {
    segments.push({ type: 'markdown', content: text.slice(lastIndex), key: `md-${part}` })
  }

  if (segments.length === 0) {
    return [{ type: 'markdown', content: text, key: 'md-0' }]
  }
  return segments
}

function hashString(value: string): string {
  let hash = 0
  for (let i = 0; i < value.length; i += 1) {
    hash = (hash << 5) - hash + value.charCodeAt(i)
    hash |= 0
  }
  return Math.abs(hash).toString(36)
}
