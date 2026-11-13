import type { EditReview } from '@/features/thread/runtime/mutation-review'
import { parseJsonPrefix } from '@/features/thread/runtime/thread-panel/messages/json-prefix'
import { isRecord, getString } from '@/shared/lib/value'

export interface ParsedToolArguments {
  raw: string
  complete: boolean
  values: Record<string, unknown>
}

interface WriteCallPreview {
  kind: 'write'
  path: string
  workdir?: string
  content: string
  hasContent: boolean
  lines: string[]
  review?: EditReview
}

/** 编辑条目是模型输入，在真实 diff 生成前供核对将要替换的文本。 */
interface EditInputEntry {
  oldText: string
  newText: string
  replaceAll: boolean
}

type EditCallPreview =
  | {
      kind: 'edit'
      mode: 'input'
      path: string
      entries: EditInputEntry[]
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
      sha256: string | null
      lines: string[]
    }

export type ToolCallPreview = WriteCallPreview | EditCallPreview

interface EditPreviewOptions {
  editReview?: EditReview | null
  streaming?: boolean
  isWaitingReview?: boolean
  isTerminalWithoutReview?: boolean
}

/** 参数可能仍在流式生成；完整 JSON 之外按已到达的前缀解析。 */
export function parseToolArguments(raw: string): ParsedToolArguments {
  const parsed = parseJsonPrefix(raw)
  return {
    raw,
    complete: parsed.complete,
    values: isRecord(parsed.value) ? parsed.value : {},
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
    return formatWritePreview(parsed, options)
  }
  if (name === 'edit') {
    return formatEditPreview(parsed, options)
  }
  return null
}

export function formatWritePreview(
  parsed: ParsedToolArguments,
  options?: EditPreviewOptions,
): WriteCallPreview {
  const hasContent = 'content' in parsed.values
  const content = getString(parsed.values.content)
  const workdir = getString(parsed.values.workdir)
  const review = options?.editReview ?? undefined
  return {
    kind: 'write',
    path: review?.path ?? getString(parsed.values.path),
    workdir: workdir || undefined,
    content,
    hasContent,
    lines: splitContentLines(content),
    review,
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

  const path = getString(parsed.values.path)
  const isStreaming = options?.streaming ?? !parsed.complete

  if (isStreaming) {
    return {
      kind: 'edit',
      mode: 'input',
      path,
      entries: editInputEntries(parsed.values.edits),
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

/** `edits` 是模型输入的替换清单；未成形的条目按已解析出的部分展示。 */
function editInputEntries(value: unknown): EditInputEntry[] {
  if (!Array.isArray(value)) {
    return []
  }
  return value.map((entry) => {
    const record = isRecord(entry) ? entry : {}
    return {
      oldText: getString(record.old_string),
      newText: getString(record.new_string),
      replaceAll: record.replace_all === true,
    }
  })
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
