export type VizSyntax = 'kchart' | 'mermaid' | 'layers' | 'blocks' | 'swimlane'

export type MarkdownSegment =
  | { type: 'markdown'; content: string; key: string }
  | { type: 'viz'; syntax: VizSyntax; content: string; key: string }

const VIZ_SYNTAXES = new Set<string>(['kchart', 'mermaid', 'layers', 'blocks', 'swimlane'])
const FENCE_OPEN = /^ {0,3}(`{3,}|~{3,})([^\r\n]*)\r?$/gm

/**
 * 从全文中抽出「已闭合」的图表 fence：kchart JSON 与四种结构图源码。
 * 未闭合 fence 仍留在 markdown 段里（显示为普通代码/文本），避免半截图反复 render。
 * 其他语言的 fence 整体跳过，内容里的 ```mermaid 等文本不会被误认成新图。
 */
export function splitMarkdownSegments(source: string): MarkdownSegment[] {
  const text = source ?? ''
  if (!text) {
    return []
  }

  const segments: MarkdownSegment[] = []
  let lastIndex = 0
  let cursor = 0
  let part = 0

  while (cursor < text.length) {
    FENCE_OPEN.lastIndex = cursor
    const opening = FENCE_OPEN.exec(text)
    if (!opening) {
      break
    }
    const fence = opening[1]!
    const lineEnd = text.indexOf('\n', opening.index)
    const bodyStart = lineEnd < 0 ? text.length : lineEnd + 1
    const closing = findClosingFence(text, bodyStart, fence)
    if (!closing) {
      break
    }

    const language = opening[2]!.trim().toLowerCase()
    if (!VIZ_SYNTAXES.has(language)) {
      cursor = closing.end
      continue
    }

    if (opening.index > lastIndex) {
      const md = text.slice(lastIndex, opening.index)
      if (md.length > 0) {
        segments.push({ type: 'markdown', content: md, key: `md-${part}` })
        part += 1
      }
    }

    const syntax = language as VizSyntax
    const code = text.slice(bodyStart, closing.start).replace(/^\uFEFF/, '').replace(/\s+$/u, '')
    segments.push({
      type: 'viz',
      syntax,
      content: code,
      // 用语言与内容哈希稳定 key：同一图在后续流式追加文字时 key/code 不变 → 不重挂。
      key: `viz-${syntax}-${hashString(code)}`,
    })
    part += 1
    lastIndex = closing.end
    cursor = closing.end
  }

  if (lastIndex < text.length) {
    segments.push({ type: 'markdown', content: text.slice(lastIndex), key: `md-${part}` })
  }

  if (segments.length === 0) {
    return [{ type: 'markdown', content: text, key: 'md-0' }]
  }
  return segments
}

function findClosingFence(text: string, from: number, opening: string) {
  const pattern = new RegExp(`^ {0,3}${opening[0]}{${opening.length},}[ \\t]*\\r?$`, 'gm')
  pattern.lastIndex = from
  const match = pattern.exec(text)
  return match ? { start: match.index, end: match.index + match[0].length } : null
}

function hashString(value: string): string {
  let hash = 0
  for (let i = 0; i < value.length; i += 1) {
    hash = (hash << 5) - hash + value.charCodeAt(i)
    hash |= 0
  }
  return Math.abs(hash).toString(36)
}
