import { ToolContentView } from '@/features/ai/runtime/thread-panel/messages/ToolContentView'
import { ToolOutputViewport } from '@/features/ai/runtime/thread-panel/messages/ToolOutputViewport'
import { parseTaskArguments, parseTaskReceipt } from '@/features/ai/runtime/task-tool-parser'
import { ThreadLink } from '@/features/ai/runtime/ThreadLink'
import { jsonContentText } from '@/features/ai/runtime/thread-timeline/content-utils'
import type { ToolRendererMessage, ToolRendererProps } from '@/platform/extensions/types'
import { translate } from '@/shared/i18n'

/**
 * task renderer：Header 展示小参数，正文展示 prompt 与可点击 Thread 链接。
 * 受理只说明子线程已创建，绝不显示「已受理/后台执行」之类的完成暗示，也不展示子任务报告。
 */
export function TaskToolRenderer({ message }: ToolRendererProps) {
  if (message.phase === 'call') {
    return <TaskToolCall message={message} />
  }
  return <TaskToolResult message={message} />
}

function TaskToolCall({ message }: { message: ToolRendererMessage }) {
  const args = parseTaskArguments(message.arguments)
  const hasParsedField = args.prompt != null || args.threadId != null
  return (
    <div className="task-tool-renderer">
      {args.threadId != null ? (
        <ThreadLink threadId={args.threadId} className="task-tool-thread-link">
          {translate('ai.runtime.notification.entry.viewSubagentExecution')}
        </ThreadLink>
      ) : null}
      {args.prompt != null ? (
        <ToolOutputViewport followKey={args.prompt}>{args.prompt}</ToolOutputViewport>
      ) : null}
      {/* 参数不是合法 JSON 时回退展示原始参数。 */}
      {!hasParsedField && message.arguments.trim() ? (
        <ToolOutputViewport followKey={message.arguments}>
          {message.arguments}
        </ToolOutputViewport>
      ) : null}
    </div>
  )
}

function TaskToolResult({ message }: { message: ToolRendererMessage }) {
  const isError = message.status === 'error' || Boolean(message.errorMessage)
  const receipt = isError ? null : parseTaskReceipt(resultJson(message))
  if (receipt == null) {
    // 终态文本不是合法的受理收据，或者调用失败：完整展示原始结果（不吞错误，不假造报告）。
    return (
      <div className="task-tool-renderer">
        <ToolContentView contents={message.contents} />
      </div>
    )
  }
  return (
    <div className="task-tool-renderer">
      <ThreadLink threadId={receipt.threadId} className="task-tool-thread-link">
        {translate('ai.runtime.notification.entry.viewSubagentExecution')}
      </ThreadLink>
    </div>
  )
}

function resultJson(message: ToolRendererMessage): string | null {
  for (const content of message.contents) {
    if (content.type === 'json') {
      return jsonContentText(content.value)
    }
    if (content.type === 'text' && content.text.trim()) {
      return content.text
    }
  }
  return null
}
