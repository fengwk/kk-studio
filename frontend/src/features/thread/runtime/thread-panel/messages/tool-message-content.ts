import { formatJsonContent } from '@/features/thread/runtime/thread-timeline/content-utils'
import {
  discloseText,
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

/** 将 write/edit 预览按收起配额截取；-1 保持原始结构（含尾随空行）。 */
export function windowMutationPreview(
  preview: ToolCallPreviewData | null,
  limit: number,
  maxChars: number,
): { preview: ToolCallPreviewData | null; window: TextDisclosure | null } {
  if (preview == null || limit < 0) {
    return { preview, window: null }
  }
  if (preview.kind === 'write') {
    const window = discloseText(preview.content, limit, 'start', maxChars)
    return {
      preview: preview.content !== '' && window.text === '' ? null
        : { ...preview, content: window.text, lines: splitWindowLines(window.text) },
      window,
    }
  }
  if (preview.mode === 'review') {
    const window = discloseText(preview.diff, limit, 'start', maxChars)
    return {
      preview: { ...preview, diff: window.text, lines: splitWindowLines(window.text) },
      window,
    }
  }
  return { preview, window: null }
}

function splitWindowLines(text: string): string[] {
  return text === '' ? [] : text.split('\n')
}

/**
 * 展开态的正文内容：按工具类型决定哪些内容进入 body。
 *
 * - ask_user 的问答整体由只读记录承载，body 为空；
 * - write/edit 成功时只保留附件（文本已在审查预览中），失败时完整展示；
 * - task 有回执时正文由回执报告承载；
 * - bash/MCP 的展开态在 preview 中按原始顺序渲染全部内容，不再拆出 body。
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
