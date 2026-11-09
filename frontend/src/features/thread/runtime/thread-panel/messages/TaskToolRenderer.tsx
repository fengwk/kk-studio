import { ToolContentView } from '@/features/thread/runtime/thread-panel/messages/ToolContentView'
import { ToolOutputViewport } from '@/features/thread/runtime/thread-panel/messages/ToolOutputViewport'
import { parseTaskArguments, parseTaskReceipt } from '@/features/thread/runtime/task-tool-parser'
import type { ToolRendererMessage, ToolRendererProps } from '@/platform/extensions/types'

/**
 * task renderer：调用正文只展示 prompt，受理收据由宿主 Header 的「查看执行」承载。
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
      {args.prompt != null ? (
        <ToolOutputViewport bounded={false}>{args.prompt}</ToolOutputViewport>
      ) : null}
      {/* 没有可解析的 prompt 或 thread_id 时回退展示原始参数。 */}
      {!hasParsedField && message.arguments.trim() ? (
        <ToolOutputViewport bounded={false}>
          {message.arguments}
        </ToolOutputViewport>
      ) : null}
    </div>
  )
}

function TaskToolResult({ message }: { message: ToolRendererMessage }) {
  const isError = message.status === 'error' || Boolean(message.errorMessage)
  const receipt = isError ? null : parseTaskReceipt(message.details)
  if (receipt != null) {
    // 导航入口在宿主 Header；正文不再重复链接或自然语言收据。
    return null
  }
  // 终态文本不是合法的受理收据，或者调用失败：完整展示原始结果（不吞错误，不假造报告）。
  return (
    <div className="task-tool-renderer">
      <ToolContentView contents={message.contents} />
    </div>
  )
}
