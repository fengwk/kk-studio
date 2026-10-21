import type { EditReview } from '@/features/chat/runtime/mutation-review'

export interface ParsedToolArguments {
  raw: string
  complete: boolean
  values: Record<string, unknown>
}

export interface WriteCallPreview {
  kind: 'write'
  path: string
  workdir?: string
  content: string
  hasContent: boolean
  lines: string[]
}

export type EditCallPreview =
  | {
      kind: 'edit'
      mode: 'input'
      path: string
      oldText: string
      newText: string
      replaceAll: boolean
    }
  | {
      kind: 'edit'
      mode: 'preparing'
      path: string
    }
  | {
      kind: 'edit'
      mode: 'review'
      path: string
      diff: string
      sha256: string
      lines: string[]
    }

export type ToolCallPreview = WriteCallPreview | EditCallPreview

export interface EditPreviewOptions {
  editReview?: EditReview | null
  streaming?: boolean
  isWaitingReview?: boolean
  isTerminalWithoutReview?: boolean
}

export function parseToolArguments(raw: string): ParsedToolArguments {
  const trimmed = raw.trim()
  if (!trimmed) {
    return { raw, complete: false, values: {} }
  }
  try {
    const parsed: unknown = JSON.parse(trimmed)
    return {
      raw,
      complete: true,
      values: parsed && typeof parsed === 'object' && !Array.isArray(parsed)
        ? parsed as Record<string, unknown>
        : {},
    }
  } catch {
    return { raw, complete: false, values: extractPartialObjectFields(trimmed) }
  }
}

export function previewForToolCall(
  toolName: string,
  rawArguments: string,
  options?: EditPreviewOptions,
): ToolCallPreview | null {
  const name = toolName.trim().toLowerCase()
  const parsed = parseToolArguments(rawArguments)
  if (name === 'write') {
    return formatWritePreview(parsed)
  }
  if (name === 'edit') {
    return formatEditPreview(parsed, options)
  }
  return null
}

export function formatWritePreview(parsed: ParsedToolArguments): WriteCallPreview {
  const hasContent = 'content' in parsed.values
  const content = stringField(parsed.values.content)
  const workdir = stringField(parsed.values.workdir)
  return {
    kind: 'write',
    path: stringField(parsed.values.path),
    workdir: workdir || undefined,
    content,
    hasContent,
    lines: splitContentLines(content),
  }
}

export function formatEditPreview(
  parsed: ParsedToolArguments,
  options?: EditPreviewOptions,
): EditCallPreview | null {
  if (options?.editReview) {
    const diff = options.editReview.diff
    return {
      kind: 'edit',
      mode: 'review',
      path: options.editReview.path,
      diff,
      sha256: options.editReview.sha256,
      lines: diff.split('\n'),
    }
  }

  // Terminal without review must not show eternal preparing spinner
  if (options?.isTerminalWithoutReview) {
    return null
  }

  const path = stringField(parsed.values.path)
  const isStreaming = options?.streaming ?? !parsed.complete

  if (isStreaming) {
    const oldText = stringField(parsed.values.old_string)
    const newText = stringField(parsed.values.new_string)
    return {
      kind: 'edit',
      mode: 'input',
      path,
      oldText,
      newText,
      replaceAll: parsed.values.replace_all === true,
    }
  }

  // Once arguments finish, if review is being awaited/prepared
  if (options?.isWaitingReview !== false) {
    return {
      kind: 'edit',
      mode: 'preparing',
      path,
    }
  }

  return null
}

function extractPartialObjectFields(raw: string): Record<string, unknown> {
  const values: Record<string, unknown> = {}
  const pattern =
    /"(path|content|old_string|new_string|replace_all|workdir|offset|limit|pattern|include|ignore_case|literal|multiline|timeout_seconds|command|line|character|query|target|subagent_type|thread_id|max_turns|name|server|tool)"\s*:\s*(true|false|-?\d+(?:\.\d+)?|"((?:\\.|[^"\\])*)("|$))/g
  for (const match of raw.matchAll(pattern)) {
    const key = match[1]
    if (!key) {
      continue
    }
    if (match[2] === 'true' || match[2] === 'false') {
      values[key] = match[2] === 'true'
      continue
    }
    if (match[2] != null && !match[2].startsWith('"')) {
      values[key] = Number(match[2])
      continue
    }
    values[key] = unescapeJsonString(match[3] ?? '')
  }
  return values
}

function unescapeJsonString(value: string): string {
  return value
    .replace(/\\n/g, '\n')
    .replace(/\\t/g, '\t')
    .replace(/\\r/g, '\r')
    .replace(/\\"/g, '"')
    .replace(/\\\\/g, '\\')
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
