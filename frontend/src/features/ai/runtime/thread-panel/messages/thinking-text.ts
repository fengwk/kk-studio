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
 * 逐字符二分测量真实宽度，不假设固定字符数，也不用 rtl 反排文本阅读顺序。
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

  // 二分最短能放下的后缀起点；measureText 随文本变长单调不减。
  let low = 0
  let high = flattened.length
  while (low < high) {
    const mid = Math.floor((low + high) / 2)
    if (measureText(ellipsis + flattened.slice(mid)) <= availableWidth) {
      high = mid
    } else {
      low = mid + 1
    }
  }
  // 连省略标记都放不下时只保留省略标记，避免渲染超出容器的假尾部。
  if (measureText(ellipsis + flattened.slice(low)) > availableWidth) {
    return { text: '', truncated: true }
  }
  return { text: flattened.slice(low), truncated: true }
}
