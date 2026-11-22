/** 搜索摘录在首个命中处之前保留的上下文字数。 */
const EXCERPT_CONTEXT = 12

/** 以空格分词的拉丁词字符；中文等不以空格分词的文字按字截取即可。 */
const LATIN_WORD_CHAR = /[\p{Script=Latin}\p{N}]/u

export interface HighlightSegment {
  text: string
  match: boolean
}

/** 把一行文本切分为命中/未命中片段；token 为小写，多个 token 的区间合并后从左到右输出。 */
export function highlightSegments(text: string, tokens: string[]): HighlightSegment[] {
  if (tokens.length === 0 || text.length === 0) {
    return text.length === 0 ? [] : [{ text, match: false }]
  }
  const haystack = text.toLowerCase()
  const ranges: Array<[number, number]> = []
  for (const token of tokens) {
    if (token.length === 0) {
      continue
    }
    let from = 0
    for (;;) {
      const index = haystack.indexOf(token, from)
      if (index < 0) {
        break
      }
      ranges.push([index, index + token.length])
      from = index + token.length
    }
  }
  if (ranges.length === 0) {
    return [{ text, match: false }]
  }
  ranges.sort((left, right) => left[0] - right[0])
  const merged: Array<[number, number]> = []
  for (const [start, end] of ranges) {
    const last = merged[merged.length - 1]
    if (last != null && start <= last[1]) {
      last[1] = Math.max(last[1], end)
    } else {
      merged.push([start, end])
    }
  }
  const segments: HighlightSegment[] = []
  let cursor = 0
  for (const [start, end] of merged) {
    if (start > cursor) {
      segments.push({ text: text.slice(cursor, start), match: false })
    }
    segments.push({ text: text.slice(start, end), match: true })
    cursor = end
  }
  if (cursor < text.length) {
    segments.push({ text: text.slice(cursor), match: false })
  }
  return segments
}

/**
 * 单行省略文本的搜索摘录：首个命中位置靠后时，从命中处前少量上下文开始并以省略号开头，
 * 窄行也能看到命中词。起点落在拉丁词中间时从该词之后的空格开始，不截半个词；不从代理对中间切开。
 * 没有命中或命中已在开头附近时原样返回。
 */
export function searchExcerpt(text: string, tokens: string[]): string {
  const haystack = text.toLowerCase()
  const first = Math.min(...tokens
    .map((token) => (token.length > 0 ? haystack.indexOf(token) : -1))
    .filter((index) => index >= 0))
  if (!Number.isFinite(first) || first <= EXCERPT_CONTEXT) {
    return text
  }
  let start = first - EXCERPT_CONTEXT
  if (/[\uDC00-\uDFFF]/.test(text[start] ?? '')) {
    start -= 1
  } else if (LATIN_WORD_CHAR.test(text[start - 1] ?? '') && LATIN_WORD_CHAR.test(text[start] ?? '')) {
    const space = text.indexOf(' ', start)
    if (space >= 0 && space < first) {
      start = space + 1
    }
  }
  return `…${text.slice(start).trimStart()}`
}
