import {
  parseToolArguments,
  type ParsedToolArguments,
} from '@/features/ai/runtime/thread-panel/messages/tool-previews'

export interface ToolCallSummary {
  name: string
  detail: string
  text: string
  coversArguments: boolean
}

export interface ToolTextPreview {
  text: string
  maxLines: number | null
  truncated: boolean
}

export interface ToolLinePreview {
  lines: string[]
  truncated: boolean
}

const COLLAPSED_CALL_PREVIEW_LINES = 7
const STREAMING_CALL_PREVIEW_LINES = 5
const COLLAPSED_TEXT_MAX_CHARS = 2_500
const COLLAPSED_RESULT_LINES: Record<string, number> = {
  bash: 10,
  task: 5,
}
const QUIET_RESULT_TOOLS = new Set([
  'read',
  'grep',
  'find',
  'edit',
  'write',
  'lsp_goto_definition',
  'lsp_workspace_symbols',
  'lsp_java_decompile',
])
const DEFAULT_COLLAPSED_RESULT_LINES = 5

/** 将工具参数压缩成 pi 风格的单行标题；未知工具回退为 `name + JSON`。 */
export function formatToolCallSummary(
  toolName: string,
  rawArguments: string,
): ToolCallSummary {
  const name = toolName.trim() || 'Tool'
  const normalizedName = name.toLowerCase()
  const parsed = parseToolArguments(rawArguments)
  const knownDetail = formatKnownToolDetail(normalizedName, parsed.values)
  const detail = formatInline(knownDetail ?? formatRawArguments(parsed, rawArguments))
  return {
    name,
    detail,
    text: detail ? `${name} ${detail}` : name,
    coversArguments: knownDetail != null,
  }
}

/**
 * write/edit 参数流式生成时固定为五行尾随窗口；稳定 write 收起为头七行；
 * 完整 edit 与主动展开态不截断。
 */
export function formatToolCallLinePreview(
  lines: string[],
  options: {
    expanded: boolean
    streaming?: boolean
    full?: boolean
  },
): ToolLinePreview {
  if (options.expanded || options.full) {
    return { lines, truncated: false }
  }
  if (options.streaming) {
    const bodyLineCount =
      lines.length > STREAMING_CALL_PREVIEW_LINES
        ? STREAMING_CALL_PREVIEW_LINES - 1
        : STREAMING_CALL_PREVIEW_LINES
    const selected = lines.slice(-bodyLineCount)
    const hiddenLineCount = Math.max(0, lines.length - selected.length)
    const formatted = formatBoundedPreview(
      selected.join('\n'),
      hiddenLineCount > 0
        ? [`${hiddenLineCount} earlier ${lineWord(hiddenLineCount)}`]
        : [],
      'before',
    )
    return {
      lines: formatted.text ? formatted.text.split('\n') : [],
      truncated: hiddenLineCount > 0 || formatted.charTruncated,
    }
  }
  const selected = lines.slice(0, COLLAPSED_CALL_PREVIEW_LINES)
  const remaining = Math.max(0, lines.length - selected.length)
  const formatted = formatBoundedPreview(
    selected.join('\n'),
    remaining > 0
      ? [`${remaining} more ${lineWord(remaining)}, ${lines.length} total`]
      : [],
    'after',
  )
  return {
    lines: formatted.text ? formatted.text.split('\n') : [],
    truncated: remaining > 0 || formatted.charTruncated,
  }
}

/**
 * 收起态按工具控制结果预算：静默工具不展示，bash 取末十行，task 取末五行。
 * 失败与展开态始终保留完整结果。
 */
export function formatToolResultPreview(
  toolName: string,
  text: string,
  options: { expanded: boolean; error: boolean },
): ToolTextPreview {
  if (options.expanded || options.error) {
    return { text, maxLines: null, truncated: false }
  }
  const normalizedName = toolName.trim().toLowerCase()
  const maxLines = QUIET_RESULT_TOOLS.has(normalizedName)
    ? 0
    : (COLLAPSED_RESULT_LINES[normalizedName] ?? DEFAULT_COLLAPSED_RESULT_LINES)
  if (maxLines <= 0) {
    return { text: '', maxLines: 0, truncated: text.trim().length > 0 }
  }
  const lines = splitContentLines(text)
  const selected = lines.slice(-maxLines)
  const hiddenLineCount = Math.max(0, lines.length - selected.length)
  const formatted = formatBoundedPreview(
    selected.join('\n'),
    hiddenLineCount > 0
      ? [`${hiddenLineCount} earlier ${lineWord(hiddenLineCount)}`]
      : [],
    'before',
  )
  return {
    text: formatted.text,
    maxLines: maxLines + (formatted.hasHint ? 1 : 0),
    truncated: hiddenLineCount > 0 || formatted.charTruncated,
  }
}

/** task.status 是传输心跳，由 task renderer 消费；默认文本 renderer 不直接泄漏 JSON。 */
export function shouldSuppressTransientToolResult(
  toolName: string,
  text: string,
): boolean {
  if (toolName.trim().toLowerCase() !== 'task') {
    return false
  }
  try {
    const value: unknown = JSON.parse(text)
    return value != null
      && typeof value === 'object'
      && !Array.isArray(value)
      && (value as Record<string, unknown>).kind === 'task.status'
  } catch {
    return false
  }
}

function formatBoundedPreview(
  body: string,
  details: string[],
  hintPosition: 'before' | 'after',
): {
  text: string
  charTruncated: boolean
  hasHint: boolean
} {
  const initialHint = formatTruncationHint(details)
  const initialSeparatorChars = body && initialHint ? 1 : 0
  const charTruncated =
    body.length + initialHint.length + initialSeparatorChars > COLLAPSED_TEXT_MAX_CHARS
  const hint = formatTruncationHint(
    charTruncated ? [...details, 'output truncated'] : details,
  )
  const separatorChars = body && hint ? 1 : 0
  const bodyBudget = Math.max(
    0,
    COLLAPSED_TEXT_MAX_CHARS - hint.length - separatorChars,
  )
  const visibleBody = charTruncated
    ? truncateWithDots(body, bodyBudget)
    : body
  const parts = hintPosition === 'before'
    ? [hint, visibleBody]
    : [visibleBody, hint]
  return {
    text: parts.filter(Boolean).join('\n'),
    charTruncated,
    hasHint: hint.length > 0,
  }
}

function formatTruncationHint(details: string[]): string {
  return details.length > 0 ? `... (${details.join(', ')})` : ''
}

function truncateWithDots(value: string, maxChars: number): string {
  if (value.length <= maxChars) {
    return value
  }
  if (maxChars <= 3) {
    return value.slice(0, maxChars)
  }
  return `${value.slice(0, maxChars - 3)}...`
}

function lineWord(count: number): 'line' | 'lines' {
  return count === 1 ? 'line' : 'lines'
}

function formatKnownToolDetail(
  toolName: string,
  values: Record<string, unknown>,
): string | null {
  switch (toolName) {
    case 'read':
      return withOptions(pathWithWorkdir(values), [
        ['offset', values.offset],
        ['limit', values.limit],
      ])
    case 'write':
      return pathWithWorkdir(values)
    case 'edit':
      return withOptions(pathWithWorkdir(values), [
        ['replace_all', values.replace_all === true ? true : undefined],
      ])
    case 'bash': {
      const command = stringField(values.command)
      if (!command) {
        return null
      }
      return withOptions(
        `${command}${workdirSuffix(values)}`,
        [['timeout_seconds', values.timeout_seconds]],
      )
    }
    case 'grep': {
      const pattern = stringField(values.pattern)
      const path = stringField(values.path)
      if (!pattern && !path) {
        return null
      }
      const base = `${JSON.stringify(pattern)} in ${path || '.'}${fromWorkdirSuffix(values)}`
      return withOptions(base, [
        ['include', values.include],
        ['ignore_case', values.ignore_case === true ? true : undefined],
        ['literal', values.literal === true ? true : undefined],
        ['multiline', values.multiline === true ? true : undefined],
        ['limit', values.limit],
        ['timeout_seconds', values.timeout_seconds],
      ])
    }
    case 'find': {
      const pattern = stringField(values.pattern)
      const path = stringField(values.path)
      if (!pattern && !path) {
        return null
      }
      return withOptions(
        `${pattern} in ${path || '.'}${fromWorkdirSuffix(values)}`,
        [
          ['limit', values.limit],
          ['timeout_seconds', values.timeout_seconds],
        ],
      )
    }
    case 'lsp_goto_definition':
      return withOptions(pathWithWorkdir(values), [
        ['line', values.line],
        ['character', values.character],
      ])
    case 'lsp_workspace_symbols': {
      const base = pathWithWorkdir(values)
      const query = stringField(values.query)
      return withOptions(`${base}${query ? ` ${JSON.stringify(query)}` : ''}`.trim(), [
        ['limit', values.limit],
      ])
    }
    case 'lsp_java_decompile': {
      const base = pathWithWorkdir(values)
      const target = stringField(values.target)
      return `${base}${target ? ` ${JSON.stringify(target)}` : ''}`.trim()
    }
    case 'task': {
      const subagentType = stringField(values.subagent_type)
      if (!subagentType) {
        return null
      }
      return withOptions(subagentType, [
        ['session_id', values.session_id],
        ['maxTurns', values.maxTurns],
      ])
    }
    default:
      return null
  }
}

function formatRawArguments(parsed: ParsedToolArguments, rawArguments: string): string {
  if (!rawArguments.trim()) {
    return ''
  }
  if (parsed.complete) {
    return JSON.stringify(parsed.values)
  }
  return rawArguments
}

function pathWithWorkdir(values: Record<string, unknown>): string {
  const path = stringField(values.path)
  if (!path) {
    return ''
  }
  return `${path}${workdirSuffix(values)}`
}

function workdirSuffix(values: Record<string, unknown>): string {
  const workdir = stringField(values.workdir)
  return workdir ? ` in ${workdir}` : ''
}

function fromWorkdirSuffix(values: Record<string, unknown>): string {
  const workdir = stringField(values.workdir)
  return workdir ? ` from ${workdir}` : ''
}

function withOptions(
  base: string,
  options: Array<[string, unknown]>,
): string {
  const visible = options
    .filter(([, value]) => value !== undefined && value !== null && value !== false)
    .map(([name, value]) => value === true ? name : `${name}=${String(value)}`)
  if (visible.length === 0) {
    return base
  }
  return `${base} [${visible.join(' ')}]`.trim()
}

function formatInline(value: string): string {
  return value.replace(/\s+/g, ' ').trim()
}

function stringField(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function splitContentLines(content: string): string[] {
  if (!content) {
    return []
  }
  const lines = content.split('\n')
  if (content.endsWith('\n') && lines[lines.length - 1] === '') {
    lines.pop()
  }
  return lines
}
