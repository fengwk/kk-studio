import { translate } from '@/shared/i18n'

/** 收起视图按逻辑行截取：0 不显示，-1 全部，正整数为行数。展开视图始终全部显示。 */
export type DisclosureLimit = number

export interface DisclosureQuota {
  preview: DisclosureLimit
  body: DisclosureLimit
}

const HIDDEN: DisclosureQuota = { preview: 0, body: 0 }

/**
 * 工具卡片的收起配额：active 对应生成调用、排队、等待审批/输入/环境和执行中；
 * settled 对应成功、失败、已停止和结果未知。
 */
export const TOOL_DISCLOSURE: Record<string, { active: DisclosureQuota; settled: DisclosureQuota }> = {
  bash: { active: { preview: 10, body: 0 }, settled: { preview: 10, body: 0 } },
  write: { active: { preview: -1, body: 0 }, settled: { preview: 10, body: 0 } },
  edit: { active: { preview: -1, body: 0 }, settled: { preview: 10, body: 0 } },
  mcp: { active: { preview: 5, body: 0 }, settled: { preview: 5, body: 0 } },
  '*': { active: HIDDEN, settled: HIDDEN },
}

export const SYSTEM_MESSAGE_DISCLOSURE = HIDDEN
export const SUBAGENT_RECEIPT_DISCLOSURE = HIDDEN
/** 收起视图中按行截取的内容最多显示的字符数；-1（全部显示）与展开视图不受限。 */
export const COLLAPSED_MAX_CHARS = 1500
export const ERROR_COLLAPSED_LINES = 3
export const HEADER_COLLAPSED_LINES = 2

export type DisclosurePhase = 'active' | 'settled'
export type DisclosureEdge = 'start' | 'end'

/** 后端 MCP 模型可见工具名固定为 `mcp_<server>_<tool>`。 */
export function isMcpToolName(toolName: string): boolean {
  return toolName.trim().toLowerCase().startsWith('mcp_')
}

export function toolDisclosureQuota(toolName: string, phase: DisclosurePhase): DisclosureQuota {
  const normalizedName = toolName.trim().toLowerCase()
  const entry = isMcpToolName(normalizedName)
    ? TOOL_DISCLOSURE.mcp
    : Object.hasOwn(TOOL_DISCLOSURE, normalizedName)
      ? TOOL_DISCLOSURE[normalizedName]
      : TOOL_DISCLOSURE['*']
  return entry[phase]
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
  if (joined === '' || maxChars === 0) {
    return { text: '', hiddenLines: totalLines, truncated: joined !== '' }
  }
  const codePoints = Array.from(joined)
  if (codePoints.length <= maxChars) {
    return {
      text: joined,
      hiddenLines: totalLines - selected.length,
      truncated: false,
    }
  }
  const suffix = codePoints.slice(-maxChars).join('')
  // 截断恰好落在换行上时，该换行是上一条隐藏行的分隔符，不应变成可见空行。
  const text = suffix.startsWith('\n') ? suffix.slice(1) : suffix
  return {
    text,
    hiddenLines: totalLines - (text === '' ? 0 : text.split('\n').length),
    truncated: true,
  }
}

/** 收起提示行；中英文界面都使用同一英文文案（见 i18n catalog）。 */
export function disclosureHint(
  disclosure: TextDisclosure,
  edge: DisclosureEdge,
): string | null {
  if (disclosure.hiddenLines === 0 && !disclosure.truncated) {
    return null
  }
  if (disclosure.hiddenLines === 0) {
    return translate('ai.runtime.disclosure.truncated')
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
function splitLogicalLines(text: string): string[] {
  const lines = text.split('\n')
  if (lines.length > 1 && lines[lines.length - 1] === '') {
    lines.pop()
  }
  return lines
}
