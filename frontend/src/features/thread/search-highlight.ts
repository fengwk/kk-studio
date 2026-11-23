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

/** Split at the first hit without losing any text, including boundary whitespace. */
export function splitSearchHit(text: string, tokens: string[]) {
  const segments = highlightSegments(text, tokens)
  const first = segments.findIndex((segment) => segment.match)
  if (first < 0) {
    return { prefix: '', spaceBeforeHit: '', hit: '', suffix: segments }
  }
  const before = first > 0 ? segments[0].text : ''
  // Trailing whitespace in the RTL prefix would be visually reordered to its left edge.
  const prefix = before.trimEnd()
  return { prefix, spaceBeforeHit: before.slice(prefix.length), hit: segments[first].text, suffix: segments.slice(first + 1) }
}
