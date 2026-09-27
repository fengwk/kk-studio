import { Link } from 'react-router'
import {
  parseTaskArguments,
  parseTaskReceipt,
} from '@/features/ai/runtime/task-tool-parser'
import { formatToolResultPreview } from '@/features/ai/runtime/thread-panel/messages/tool-display'
import { ToolOutputViewport } from '@/features/ai/runtime/thread-panel/messages/ToolOutputViewport'
import type { ToolRendererProps } from '@/platform/extensions/types'
import { useI18n } from '@/shared/i18n'

/** task 工具调用的组件化 renderer：call 展开展示参数；result 展示受理收据（绝不展示已完成/报告）。 */
export function TaskToolRenderer({ message, expanded = false }: ToolRendererProps) {
  if (message.phase === 'call') {
    return <TaskToolCall message={message} expanded={expanded} />
  }
  return <TaskToolResult message={message} expanded={expanded} />
}

function TaskToolCall({ message, expanded = false }: ToolRendererProps) {
  const { t } = useI18n()
  const args = parseTaskArguments(message.arguments)
  const hasParsedField =
    args.subagentType != null
    || args.prompt != null
    || args.threadId != null
    || args.maxTurns != null

  if (!expanded) {
    return null
  }

  return (
    <div className="task-tool-renderer">
      {hasParsedField ? (
        <dl className="task-tool-fields">
          {args.subagentType != null ? (
            <div className="task-tool-field">
              <dt>{t('ai.runtime.task.subagent')}</dt>
              <dd>{args.subagentType}</dd>
            </div>
          ) : null}
          {args.threadId != null ? (
            <div className="task-tool-field">
              <dt>{t('ai.runtime.task.thread')}</dt>
              <dd>
                <Link to={`/threads/${args.threadId}`} className="task-tool-thread-link">
                  {args.threadId}
                </Link>
              </dd>
            </div>
          ) : null}
          {args.maxTurns != null ? (
            <div className="task-tool-field">
              <dt>{t('ai.runtime.task.maxTurns')}</dt>
              <dd>{args.maxTurns}</dd>
            </div>
          ) : null}
        </dl>
      ) : null}
      {args.prompt != null ? (
        <div className="task-tool-section">
          <span className="task-tool-section-label">{t('ai.runtime.task.prompt')}</span>
          <ToolOutputViewport text={args.prompt} maxLines={null} />
        </div>
      ) : null}
      {/* 参数不是合法 JSON 时回退展示原始参数。 */}
      {!hasParsedField && message.arguments.trim() ? (
        <ToolOutputViewport text={message.arguments} maxLines={null} />
      ) : null}
    </div>
  )
}

function TaskToolResult({ message, expanded = false }: ToolRendererProps) {
  const { t } = useI18n()
  const isError = message.status === 'error' || Boolean(message.errorMessage)
  const receipt = isError ? null : parseTaskReceipt(message.text)

  if (receipt == null) {
    // 终态文本不是合法的 accepted 收据，或者调用失败：完整降级到原始文本 + 错误消息（不吞错误，不假造报告）。
    return (
      <div className="task-tool-renderer">
        {message.text.trim() ? (
          <TaskResultOutput
            text={message.text}
            expanded={expanded}
            error={isError}
          />
        ) : null}
        {message.errorMessage && message.errorMessage !== message.text ? (
          <p className="thread-tool-error">{message.errorMessage}</p>
        ) : null}
      </div>
    )
  }

  return (
    <div className="task-tool-renderer">
      <p className="task-tool-final accepted">
        {t('ai.runtime.task.state.accepted')}
      </p>
      <dl className="task-tool-fields">
        <div className="task-tool-field">
          <dt>{t('ai.runtime.task.thread')}</dt>
          <dd>
            <Link to={`/threads/${receipt.threadId}`} className="task-tool-thread-link">
              {receipt.threadId}
            </Link>
          </dd>
        </div>
      </dl>
    </div>
  )
}

function TaskResultOutput({
  text,
  expanded,
  error,
  className,
}: {
  text: string
  expanded: boolean
  error: boolean
  className?: string
}) {
  const preview = formatToolResultPreview('task', text, { expanded, error })
  return (
    <ToolOutputViewport
      text={preview.text}
      maxLines={preview.maxLines}
      className={className}
    />
  )
}
