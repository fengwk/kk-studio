/**
 * 规范化思考文本：仅去除末尾多余的空白与换行符，
 * 保留前导缩进与内部段落格式。展开态原样渲染这份文本。
 */
export function normalizeThinkingText(raw: string): string {
  return raw.replace(/[\r\n\s]+$/, '')
}

/**
 * 收起态单行投影：把全部思考（含换行）折叠为连续单个空格后取整行。
 * 仅用于展示，不改变展开渲染所需的原始 Markdown。
 */
export function flattenThinkingText(raw: string): string {
  return normalizeThinkingText(raw).replace(/\s+/g, ' ').trim()
}

export interface ThinkingLineProjection {
  /** 收起态可见文本；`truncated` 为 true 时前文被省略标记取代。 */
  text: string
  truncated: boolean
}

/**
 * 在可用宽度内保留思考最新尾部：返回能放下的最长后缀，前文放不下时由省略标记取代。
 * 以字素簇（grapheme）为最小单位二分测量真实宽度：不切断 emoji/组合字符，不假设
 * 固定字符数，也不用 rtl 反排文本阅读顺序。
 */
export function projectThinkingLine(
  flattened: string,
  availableWidth: number,
  measureText: (text: string) => number,
  ellipsis = '…',
): ThinkingLineProjection {
  if (!flattened) {
    return { text: '', truncated: false }
  }
  if (availableWidth <= 0 || measureText(flattened) <= availableWidth) {
    return { text: flattened, truncated: false }
  }

  const clusters = splitGraphemes(flattened)
  const suffix = (start: number) => clusters.slice(start).join('')
  let low = 0
  let high = clusters.length
  while (low < high) {
    const mid = Math.floor((low + high) / 2)
    if (measureText(ellipsis + suffix(mid)) <= availableWidth) {
      high = mid
    } else {
      low = mid + 1
    }
  }
  // 快速路径未命中时整行必然放不下，low=0 只可能来自非单调度量；此时按整行处理。
  if (low === 0) {
    return { text: flattened, truncated: false }
  }
  // low = clusters.length 表示连省略标记都放不下，只保留省略标记，不渲染假尾部。
  return { text: suffix(low), truncated: true }
}

/**
 * 按字素簇切分文本。浏览器内建 Intl.Segmenter 保证不切断 emoji ZWJ 序列与组合
 * 字符；不可用时退化为码点切分，至少不切断代理对。
 */
function splitGraphemes(text: string): string[] {
  if (typeof Intl.Segmenter === 'function') {
    const segmenter = new Intl.Segmenter(undefined, { granularity: 'grapheme' })
    return Array.from(segmenter.segment(text), (entry) => entry.segment)
  }
  return Array.from(text)
}
