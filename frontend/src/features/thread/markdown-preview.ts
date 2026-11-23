import { translate } from '@/shared/i18n'
import { splitMarkdownSegments } from '@/shared/ui/markdown/split-markdown-segments'

// Handle common preview markup, not the full Markdown grammar. Match inline
// code first so literal symbols inside it stay intact.
const inlineMarkup = new RegExp([
  /^[^\S\r\n]*(?:(?:#{1,6}|[-+*]|\d{1,9}[.)])[^\S\r\n]+|>[^\S\r\n]*)+/,
  // A multi-hash heading can also occur inside an already-folded preview.
  /(?<=\s)#{2,6}(?=\s)/,
  /(?<![\\`])(?<ticks>`+)(?!`)(?<code>[\s\S]+?)(?<!`)\k<ticks>(?!`)/,
  /(?<!\\)!?\[(?<label>[^\][\n]*)\]\((?:[^()\n]|\([^()\n]*\))*\)/,
  /(?<![\\*])(?<stars>\*{1,3})(?![\s*])(?<strong>[^*\n]*?[^\s*])\k<stars>(?!\*)/,
  /(?<![\\_\p{L}\p{N}])(?<underscores>_{1,3})(?![\s_])(?<emphasis>[^_\n]*?[^\s_])\k<underscores>(?![_\p{L}\p{N}])/u,
].map((pattern) => pattern.source).join('|'), 'gmu')

const fenceOpening = /^ {0,3}(?:(`{3,})(?!`)[^`\r\n]*|(~{3,})[^\r\n]*)(?:\r?\n|$)/gm
const tableDelimiter = /^\s*\|?\s*:?-+:?\s*(?:\|\s*:?-+:?\s*)*\|?\s*$/
const cellSeparator = /(?<!\\)\|/g

function tableCellCount(row: string): number {
  return row.trim().replace(/^\||(?<!\\)\|$/g, '').split(cellSeparator).length
}

function flattenInline(source: string): string {
  return source.replace(inlineMarkup, (
    _match, _ticks, code, label, _stars, strong, _underscores, emphasis,
  ) => {
    const prose = label ?? strong ?? emphasis
    return code ?? (prose ? flattenInline(prose) : '')
  })
}

function flattenProse(source: string): string {
  const lines = source.split(/\r?\n/)
  for (let index = 0; index + 1 < lines.length; index += 1) {
    const header = lines[index]!
    const delimiter = lines[index + 1]!
    if (header.search(cellSeparator) < 0 || !tableDelimiter.test(delimiter)
      || tableCellCount(header) !== tableCellCount(delimiter)) {
      continue
    }
    lines[index] = header.replace(cellSeparator, ' ')
    lines[index + 1] = ''
    for (index += 2; index < lines.length; index += 1) {
      const row = lines[index]!
      if (row.search(cellSeparator) < 0) {
        break
      }
      lines[index] = row.replace(cellSeparator, ' ')
    }
    index -= 1
  }
  return flattenInline(lines.join('\n'))
}

export interface PreviewCodeLine {
  start: number
  end: number
}

/** Returns single-line visible text; optionally records code line ranges in that text. */
export function markdownPreview(
  text: string | null,
  options?: { codeLines: PreviewCodeLine[]; mermaidLabel: string },
): string {
  let preview = ''
  function append(source: string, isCodeLine = false) {
    const visible = source.replace(/\s+/g, ' ').trim()
    if (!visible) {
      return
    }
    if (preview) {
      preview += ' '
    }
    const start = preview.length
    preview += visible
    if (isCodeLine) {
      options?.codeLines.push({ start, end: preview.length })
    }
  }

  for (const segment of splitMarkdownSegments(text ?? '')) {
    if (segment.type === 'mermaid') {
      append(options?.mermaidLabel ?? translate('shared.mermaidDiagram'))
      continue
    }
    const source = segment.content
    let cursor = 0
    for (const opening of source.matchAll(fenceOpening)) {
      if (opening.index < cursor) {
        continue
      }
      append(flattenProse(source.slice(cursor, opening.index)))
      const fence = (opening[1] ?? opening[2])!
      const closing = new RegExp(`^ {0,3}${fence[0]}{${fence.length},}[^\\S\\r\\n]*$`, 'gm')
      const bodyStart = opening.index + opening[0].length
      closing.lastIndex = bodyStart
      const close = closing.exec(source)
      if (!close) {
        append(source.slice(opening.index))
        cursor = source.length
        break
      }
      for (const line of source.slice(bodyStart, close.index).split(/\r?\n/)) {
        append(line, true)
      }
      cursor = close.index + close[0].length
    }
    append(flattenProse(source.slice(cursor)))
  }
  return preview
}
