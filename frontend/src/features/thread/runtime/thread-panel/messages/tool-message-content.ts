import { PROCESS_OUTPUT_OMISSION_MARKER } from '@/features/thread/runtime/thread-realtime/process-output-stream'
import { asRecord } from '@/features/thread/runtime/thread-timeline/payload-json'
import { formatJsonContent } from '@/features/thread/runtime/thread-timeline/content-utils'
import {
  discloseText,
  splitLogicalLines,
  type TextDisclosure,
} from '@/features/thread/runtime/thread-panel/messages/disclosure-policy'
import type { ToolCallPreview as ToolCallPreviewData } from '@/features/thread/runtime/thread-panel/messages/tool-previews'
import type { ToolContent, ToolDialogueMessage } from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import type { TaskToolReceipt } from '@/features/thread/runtime/state/task-tool-parser'

/** 正文与附件分离：文本类内容参与单文本窗口，resource 始终逐条渲染。 */
export function splitContents(
  contents: ToolContent[],
): { text: ToolContent[]; resources: ToolContent[] } {
  const text: ToolContent[] = []
  const resources: ToolContent[] = []
  for (const content of contents) {
    if (content.type === 'resource') {
      resources.push(content)
    } else {
      text.push(content)
    }
  }
  return { text, resources }
}

/** 文本类内容按行合并：json 内容用其规范序列化，resource 不参与。 */
export function joinTextContents(contents: ToolContent[]): string {
  return contents
    .map((content) => {
      if (content.type === 'text') {
        return content.text
      }
      return content.type === 'json' ? formatJsonContent(content.value) : ''
    })
    .join('\n')
}

/** Daemon 的结构化结果把退出说明和落盘信息附在日志后；它们不属于输出行。 */
export function bashOutput(raw: string, details: ToolDialogueMessage['details'], failed: boolean) {
  const process = asRecord(details?.process)
  const note = failed && typeof process.outcome === 'string'
    ? /(?:^|\n)(\[(?:Command (?:timed out after |exited with code |was terminated by signal )|Operation cancelled by the caller\.)[^\n]*\])$/.exec(raw)
    : null
  let text = note == null ? raw : raw.slice(0, note.index + (note[0].startsWith('\n') ? 1 : 0))
  const stored = asRecord(details?.textOutput)
  const footer = typeof stored.totalBytes === 'number'
    ? text.lastIndexOf('\n\n[Full output')
    : -1
  if (footer >= 0) text = text.slice(0, footer + 1)

  const liveOmission = text === PROCESS_OUTPUT_OMISSION_MARKER
    || text.startsWith(`${PROCESS_OUTPUT_OMISSION_MARKER}\n`)
  if (liveOmission) text = text.slice(PROCESS_OUTPUT_OMISSION_MARKER.length + 1)
  if (footer >= 0) {
    const marker = /(?:^|\n)\[\.\.\. \d+ bytes omitted here; the middle of the output is not shown \.\.\.\]\n/.exec(text)
    if (marker != null) text = text.slice(marker.index + marker[0].length)
  }
  return {
    text,
    note: note?.[1],
    incomplete: liveOmission || typeof stored.totalBytes === 'number',
    storedPath: typeof stored.path === 'string' && stored.path !== '' ? stored.path : null,
    timedOut: process.outcome === 'TIMED_OUT' || details?.code === 'TIMEOUT',
  }
}

/** 去掉与错误块完全相同的正文，避免展开后同一失败文本出现两次。 */
export function withoutExactError(
  message: ToolDialogueMessage,
  errorText: string,
  isBash: boolean,
): ToolDialogueMessage {
  if (isBash || errorText === '') {
    return message
  }
  const contents = message.contents.filter((content) =>
    !(content.type === 'text' && content.text.trim() === errorText))
  return contents.length === message.contents.length ? message : { ...message, contents }
}

/**
 * 预览保留尾部；edit 草稿只取替换文本，各条目共享同一个窗口。待审批的 write 已有冻结
 * 审查 diff 时与 edit 一致，只开一个 diff 尾部窗口，正文不再重复占用预览配额。
 */
export function windowMutationPreview(
  preview: ToolCallPreviewData | null,
  limit: number,
  maxChars: number,
): { preview: ToolCallPreviewData | null; window: TextDisclosure | null; hasFullDetails?: boolean } {
  if (preview == null) {
    return { preview, window: null }
  }
  if (preview.kind === 'write') {
    if (preview.review != null) {
      const window = discloseText(preview.review.diff, limit, 'end', maxChars)
      return {
        preview: window.text === '' ? null : {
          ...preview,
          review: { ...preview.review, diff: window.text },
        },
        window,
        hasFullDetails: window.hiddenLines > 0 || window.truncated,
      }
    }
    if (!preview.hasContent) return { preview: null, window: null }
    const window = discloseText(preview.content, limit, 'end', maxChars)
    return {
      preview: preview.content !== '' && window.hiddenLines === splitLogicalLines(preview.content).length ? null
        : { ...preview, content: window.text || (preview.content !== '' ? '\n' : '') },
      window,
    }
  }
  if (preview.mode === 'review') {
    const window = discloseText(preview.diff, limit, 'end', maxChars)
    return {
      preview: window.text === '' ? null : { ...preview, diff: window.text, lines: window.text.split('\n') },
      window,
    }
  }

  let remainingLines = limit
  let remainingChars = maxChars
  let hiddenLines = 0
  let truncated = false
  let hasFullDetails = false
  const entries = [...preview.entries].reverse().map((entry) => {
    const count = splitLogicalLines(entry.newText).length
    const window = discloseText(entry.newText, remainingLines, 'end', remainingChars)
    const shown = count - window.hiddenLines
    remainingLines -= shown
    remainingChars -= Array.from(window.text).length
    hiddenLines += window.hiddenLines
    truncated ||= window.truncated
    hasFullDetails ||= entry.oldText !== '' || window.hiddenLines > 0 || window.truncated
    return { ...entry, oldText: '', newText: shown > 0 ? window.text || '\n' : '' }
  }).reverse()
  return {
    preview: preview.mode === 'preparing' || entries.some((entry) => entry.newText !== '')
      ? { ...preview, entries } : null,
    window: { text: '', hiddenLines, truncated },
    hasFullDetails,
  }
}

/**
 * 展开态的正文内容：按工具类型决定哪些内容进入 body。
 *
 * - ask_user 的问答整体由只读记录承载，body 为空；
 * - write/edit 成功时只保留附件（文本已在审查预览中），失败时完整展示；
 * - task 有回执时正文由回执报告承载；
 * - bash/MCP 的文本由宿主分配到预览与详情，不在通用结果区重复渲染。
 */
export function toolBodyContents(
  contents: ToolContent[],
  {
    isTask,
    taskReceipt,
    isAskUser,
    isMutation,
    isMutationSuccess,
    showTextPreview,
  }: {
    isTask: boolean
    taskReceipt: TaskToolReceipt | null
    isAskUser: boolean
    isMutation: boolean
    isMutationSuccess: boolean
    showTextPreview: boolean
  },
): ToolContent[] {
  if (isAskUser) {
    return []
  }
  if (isMutation) {
    return isMutationSuccess ? contents.filter((content) => content.type === 'resource') : contents
  }
  if (isTask) {
    return taskReceipt == null ? contents : []
  }
  return showTextPreview ? [] : contents
}
