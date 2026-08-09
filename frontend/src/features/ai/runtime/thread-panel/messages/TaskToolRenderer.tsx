import {
  parseTaskArguments,
  parseTaskFinalText,
  runStateKey,
} from '@/features/ai/runtime/task-tool-parser'
import {
  parseTaskStatus,
  type TaskStatusState,
} from '@/features/ai/runtime/task-status'
import { ToolOutputViewport } from '@/features/ai/runtime/thread-panel/messages/ToolOutputViewport'
import type { ToolRendererProps } from '@/platform/extensions/types'
import { useI18n } from '@/shared/i18n'

/** task 工具调用的组件化 renderer：call 展示参数 + 最新运行状态；result 展示最终报告。 */
export function TaskToolRenderer({ message }: ToolRendererProps) {
  if (message.phase === 'call') {
    return <TaskToolCall message={message} />
  }
  return <TaskToolResult message={message} />
}

function TaskToolCall({ message }: ToolRendererProps) {
  const { t } = useI18n()
  const args = parseTaskArguments(message.arguments)
  const status = parseTaskStatus(message.partial ?? '')
  const hasParsedField =
    args.subagentType != null || args.prompt != null || args.sessionId != null
    || args.maxTurns != null
  return (
    <div className="task-tool-renderer">
      {status != null ? <TaskLiveStatus status={status} /> : null}
      {hasParsedField ? (
        <dl className="task-tool-fields">
          {args.subagentType != null ? (
            <div className="task-tool-field">
              <dt>{t('ai.runtime.task.subagent')}</dt>
              <dd>{args.subagentType}</dd>
            </div>
          ) : null}
          {args.sessionId != null ? (
            <div className="task-tool-field">
              <dt>{t('ai.runtime.task.session')}</dt>
              <dd>{args.sessionId}</dd>
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
          <ToolOutputViewport text={args.prompt} />
        </div>
      ) : null}
      {/* 参数不是合法 JSON 时（后端会拒绝，理论上不会发生）回退展示原始参数。 */}
      {!hasParsedField && message.arguments.trim() ? (
        <ToolOutputViewport text={message.arguments} />
      ) : null}
    </div>
  )
}

function TaskToolResult({ message }: ToolRendererProps) {
  const { t } = useI18n()
  const parsed = parseTaskFinalText(message.text)
  if (parsed == null) {
    // 终态文本不是规范的 <task> 格式：回退到原始文本 + 错误消息（不丢信息）。
    return (
      <div className="task-tool-renderer">
        {message.text.trim() ? <ToolOutputViewport text={message.text} /> : null}
        {message.errorMessage && message.errorMessage !== message.text ? (
          <p className="thread-tool-error">{message.errorMessage}</p>
        ) : null}
      </div>
    )
  }
  return (
    <div className="task-tool-renderer">
      {parsed.state != null ? (
        <p className={`task-tool-final ${parsed.state}`}>
          {t(finalStateKey(parsed.state))}
        </p>
      ) : null}
      {parsed.report != null ? (
        <div className="task-tool-section">
          <span className="task-tool-section-label">{t('ai.runtime.task.report')}</span>
          <ToolOutputViewport text={parsed.report} />
        </div>
      ) : null}
      {parsed.error != null ? (
        <div className="task-tool-section">
          <span className="task-tool-section-label">{t('ai.runtime.task.error')}</span>
          {/* 错误可能是并发限制等长文本：同样用五行视口，避免撑爆 transcript。 */}
          <ToolOutputViewport text={parsed.error} className="task-tool-error-viewport" />
        </div>
      ) : null}
    </div>
  )
}

/** call 阶段的最新运行状态条：状态 + 轮数 + 工具调用数 + 最近活动。 */
function TaskLiveStatus({ status }: { status: TaskStatusState }) {
  const { t } = useI18n()
  return (
    <p className={`task-tool-live ${status.state}`}>
      <span className="task-status-dot" aria-hidden="true" />
      <span>{t(runStateKey(status.state))}</span>
      {status.turns != null ? (
        <span>{t('ai.runtime.task.turns', { count: status.turns })}</span>
      ) : null}
      {status.toolCalls != null ? (
        <span>{t('ai.runtime.task.toolCalls', { count: status.toolCalls })}</span>
      ) : null}
      {status.lastActivity != null ? <span>{status.lastActivity}</span> : null}
    </p>
  )
}

function finalStateKey(state: 'completed' | 'error' | 'cancelled'): string {
  switch (state) {
    case 'completed':
      return 'ai.runtime.task.state.completed'
    case 'error':
      return 'ai.runtime.task.state.error'
    case 'cancelled':
      return 'ai.runtime.task.state.cancelled'
  }
}
