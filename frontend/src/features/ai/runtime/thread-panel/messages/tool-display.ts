import {
  parseToolArguments,
  type ParsedToolArguments,
} from '@/features/ai/runtime/thread-panel/messages/tool-previews'

/** 工具卡片 Header 的单行摘要；detail 为已知工具的紧凑参数或未知工具的紧凑 JSON。 */
export interface ToolCallSummary {
  name: string
  detail: string
  text: string
}

/**
 * 将工具参数压缩成 pi 风格的单行标题；未知工具/MCP 回退为紧凑 JSON。
 *
 * 紧凑 JSON 由规范值直接序列化：不改变字符串内容，不丢弃 false/0/空值，
 * 也不补充未传入的默认值。超长值由 Header 的换行与 overflow-wrap 承载。
 */
export function formatToolCallSummary(
  toolName: string,
  rawArguments: string,
): ToolCallSummary {
  const name = toolName.trim() || 'Tool'
  const normalizedName = name.toLowerCase()
  const parsed = parseToolArguments(rawArguments)
  const detail = formatKnownToolDetail(normalizedName, parsed.values)
    ?? formatRawArguments(parsed, rawArguments)
  return {
    name,
    detail,
    text: detail ? `${name} ${detail}` : name,
  }
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
      return withOptions(`${command}${workdirSuffix(values)}`, [
        ['timeout_seconds', values.timeout_seconds],
      ])
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
    case 'ask_user':
      // 问题/选项/答案整体下移到只读记录，Header 只保留工具名。
      return ''
    case 'task': {
      const subagentType = stringField(values.subagent_type)
      if (!subagentType) {
        return null
      }
      // thread_id 在正文以可点击 Thread 链接出现，Header 不重复。
      return withOptions(subagentType, [['max_turns', values.max_turns]])
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

function stringField(value: unknown): string {
  return typeof value === 'string' ? value : ''
}
