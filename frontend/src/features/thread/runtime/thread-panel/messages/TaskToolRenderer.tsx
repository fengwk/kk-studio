import { ToolContentView } from '@/features/thread/runtime/thread-panel/messages/ToolContentView'
import { ToolOutputViewport } from '@/features/thread/runtime/thread-panel/messages/ToolOutputViewport'
import { parseTaskArguments, parseTaskReceipt } from '@/features/thread/runtime/task-tool-parser'
import type { ToolRendererMessage, ToolRendererProps } from '@/platform/extensions/types'

/**
 * task renderer：调用正文只展示 prompt；运行状态由宿主 Header 展示，同步终态正文显示子任务报告或错误。
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
  if (receipt?.status === 'running') {
    // 导航入口与运行状态在宿主 Header 展示，不重复渲染进度回执正文。
    return null
  }
  // 同步终态报告与失败文本都是模型结果正文，完整展示且不推断报告内容。
  return (
    <div className="task-tool-renderer">
      <ToolContentView contents={message.contents} />
    </div>
  )
}
