export interface ParsedToolArguments {
  raw: string
  complete: boolean
  values: Record<string, unknown>
}

export interface WriteCallPreview {
  kind: 'write'
  path: string
  content: string
  lines: string[]
}

export interface EditCallPreview {
  kind: 'edit'
  path: string
  replaceAll: boolean
  lines: string[]
}

export type ToolCallPreview = WriteCallPreview | EditCallPreview

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
): ToolCallPreview | null {
  const name = toolName.trim().toLowerCase()
  const parsed = parseToolArguments(rawArguments)
  if (name === 'write') {
    return formatWritePreview(parsed)
  }
  if (name === 'edit') {
    return formatEditPreview(parsed)
  }
  return null
}

export function formatWritePreview(parsed: ParsedToolArguments): WriteCallPreview {
  const content = stringField(parsed.values.content)
  return {
    kind: 'write',
    path: stringField(parsed.values.path),
    content,
    lines: splitContentLines(content),
  }
}

export function formatEditPreview(parsed: ParsedToolArguments): EditCallPreview {
  const oldText = stringField(parsed.values.old_string)
  const newText = stringField(parsed.values.new_string)
  return {
    kind: 'edit',
    path: stringField(parsed.values.path),
    replaceAll: parsed.values.replace_all === true,
    lines: generateSimpleDiffLines(oldText, newText),
  }
}

export function generateSimpleDiffLines(oldText: string, newText: string): string[] {
  if (!oldText && !newText) {
    return []
  }
  const oldLines = splitContentLines(oldText)
  const newLines = splitContentLines(newText)
  const prefix = commonPrefixLength(oldLines, newLines)
  const suffix = commonSuffixLength(oldLines.slice(prefix), newLines.slice(prefix))
  const oldMid = oldLines.slice(prefix, oldLines.length - suffix)
  const newMid = newLines.slice(prefix, newLines.length - suffix)
  const lines: string[] = []
  for (const line of oldLines.slice(0, prefix)) {
    lines.push(` ${line}`)
  }
  for (const line of oldMid) {
    lines.push(`-${line}`)
  }
  for (const line of newMid) {
    lines.push(`+${line}`)
  }
  if (suffix > 0) {
    for (const line of oldLines.slice(oldLines.length - suffix)) {
      lines.push(` ${line}`)
    }
  }
  return lines
}

function extractPartialObjectFields(raw: string): Record<string, unknown> {
  const values: Record<string, unknown> = {}
  const pattern =
    /"(path|content|old_string|new_string|replace_all)"\s*:\s*(true|false|"((?:\\.|[^"\\])*)("|$))/g
  for (const match of raw.matchAll(pattern)) {
    const key = match[1]
    if (!key) {
      continue
    }
    if (match[2] === 'true' || match[2] === 'false') {
      values[key] = match[2] === 'true'
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

function commonPrefixLength(left: string[], right: string[]): number {
  const limit = Math.min(left.length, right.length)
  let index = 0
  while (index < limit && left[index] === right[index]) {
    index += 1
  }
  return index
}

function commonSuffixLength(left: string[], right: string[]): number {
  const limit = Math.min(left.length, right.length)
  let index = 0
  while (
    index < limit
    && left[left.length - 1 - index] === right[right.length - 1 - index]
  ) {
    index += 1
  }
  return index
}
