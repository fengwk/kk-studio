import { translate } from '@/shared/i18n'

/** 收起视图按逻辑行截取：0 不显示，-1 全部，正整数为行数。展开视图始终全部显示。 */
export type DisclosureLimit = number

export interface DisclosureQuota {
  preview: DisclosureLimit
  body: DisclosureLimit
}

const HIDDEN: DisclosureQuota = { preview: 0, body: 0 }

/**
 * 工具卡片的预览配额与执行阶段无关。
 */
export function toolPreviewLines(toolName: string): number {
  const name = toolName.trim().toLowerCase()
  if (name === 'bash' || name === 'write' || name === 'edit') return 10
  return isMcpToolName(name) ? 5 : 0
}

export const SYSTEM_MESSAGE_DISCLOSURE = HIDDEN
export const SUBAGENT_RECEIPT_DISCLOSURE = HIDDEN
/** 收起视图中按行截取的内容最多显示的字符数；-1（全部显示）与展开视图不受限。 */
export const COLLAPSED_MAX_CHARS = 1500
export const ERROR_COLLAPSED_LINES = 3
export const HEADER_COLLAPSED_LINES = 2

export type DisclosureEdge = 'start' | 'end'

/** 后端 MCP 模型可见工具名固定为 `mcp_<server>_<tool>`。 */
export function isMcpToolName(toolName: string): boolean {
  return toolName.trim().toLowerCase().startsWith('mcp_')
}

export interface TextDisclosure {
  /** 收起视图应渲染的文本；-1 时是完整原文。 */
  text: string
  /** 未显示的完整逻辑行数；部分行被字符上限截断时不计入。 */
  hiddenLines: number
  /** 字符上限切断了窗口内的内容。 */
  truncated: boolean
}

/**
 * 按逻辑行和字符数截取文本。
 *
 * - `limit` 为 0 时不显示正文，但仍返回真实隐藏行数；
 * - `edge` 为 end 时保留尾部（bash），隐藏行提示为 earlier；
 * - 字符上限按 Unicode code point 计算，不切开代理对。
 */
export function discloseText(
  text: string,
  limit: DisclosureLimit,
  edge: DisclosureEdge = 'start',
  maxChars: DisclosureLimit = COLLAPSED_MAX_CHARS,
): TextDisclosure {
  if (text === '') {
    return { text: '', hiddenLines: 0, truncated: false }
  }
  const lines = splitLogicalLines(text)
  if (limit < 0) {
    return { text, hiddenLines: 0, truncated: false }
  }
  if (limit === 0) {
    return { text: '', hiddenLines: lines.length, truncated: false }
  }
  const selected = edge === 'end' ? lines.slice(-limit) : lines.slice(0, limit)
  if (maxChars < 0) {
    return { text: selected.join('\n'), hiddenLines: lines.length - selected.length, truncated: false }
  }
  if (edge === 'end') {
    return keepCodePointSuffix(selected, lines.length, maxChars)
  }
  const kept: string[] = []
  let usedChars = 0
  let truncated = false
  for (const line of selected) {
    const separator = kept.length === 0 ? 0 : 1
    if (usedChars + separator > maxChars) {
      truncated = true
      break
    }
    const available = maxChars - usedChars - separator
    const codePoints = Array.from(line)
    if (codePoints.length > available) {
      const partial = codePoints.slice(0, Math.max(0, available)).join('')
      if (partial !== '') {
        kept.push(partial)
      }
      truncated = true
      break
    }
    kept.push(line)
    usedChars += separator + codePoints.length
  }
  return {
    text: kept.join('\n'),
    hiddenLines: lines.length - kept.length,
    truncated,
  }
}

/**
 * bash 等尾部窗口必须保留窗口末尾：先取最后 N 行，再保留最后 maxChars 个
 * code point，确保末尾的退出码/错误说明不会被早期大行挤掉。
 */
function keepCodePointSuffix(
  selected: string[],
  totalLines: number,
  maxChars: number,
): TextDisclosure {
  const joined = selected.join('\n')
  if (maxChars === 0 && joined !== '') {
    return { text: '', hiddenLines: totalLines, truncated: false }
  }
  const codePoints = Array.from(joined)
  if (codePoints.length <= maxChars) {
    return {
      text: joined,
      hiddenLines: totalLines - selected.length,
      truncated: false,
    }
  }
  const start = codePoints.length - maxChars
  const suffix = codePoints.slice(start).join('')
  // 截断恰好落在换行上时，该换行是上一条隐藏行的分隔符，不应变成可见空行。
  const text = suffix.startsWith('\n') ? suffix.slice(1) : suffix
  return {
    text,
    hiddenLines: totalLines - text.split('\n').length,
    truncated: codePoints[start - 1] !== '\n' && codePoints[start] !== '\n',
  }
}

/** 省略整行和截断长行是两种事实，提示分别表达。 */
export function disclosureHint(
  disclosure: TextDisclosure,
  edge: DisclosureEdge,
): string | null {
  if (disclosure.hiddenLines === 0 && !disclosure.truncated) {
    return null
  }
  if (disclosure.hiddenLines === 0) {
    return translate(edge === 'end' ? 'ai.runtime.disclosure.longLineTruncated' : 'ai.runtime.disclosure.truncated')
  }
  const count = disclosure.hiddenLines
  const single = count === 1
  if (edge === 'end') {
    if (disclosure.truncated) {
      return translate(
        single
          ? 'ai.runtime.disclosure.earlierLineTruncated'
          : 'ai.runtime.disclosure.earlierLinesTruncated',
        { count },
      )
    }
    return translate(
      single
        ? 'ai.runtime.disclosure.earlierLine'
        : 'ai.runtime.disclosure.earlierLines',
      { count },
    )
  }
  if (disclosure.truncated) {
    return translate(
      single
        ? 'ai.runtime.disclosure.moreLineTruncated'
        : 'ai.runtime.disclosure.moreLinesTruncated',
      { count },
    )
  }
  return translate(
    single
      ? 'ai.runtime.disclosure.moreLine'
      : 'ai.runtime.disclosure.moreLines',
    { count },
  )
}

/** 末尾换行是行终止符，不额外制造一条空逻辑行。 */
export function splitLogicalLines(text: string): string[] {
  if (text === '') return []
  const lines = text.split('\n')
  if (lines.length > 1 && lines[lines.length - 1] === '') {
    lines.pop()
  }
  return lines
}
