import { useMemo, type CSSProperties, type ReactNode } from 'react'
import {
  createTaskLevelStateMap,
  type TaskStatusState,
} from '@/features/ai/runtime/task-status'
import { runStateKey } from '@/features/ai/runtime/task-tool-parser'
import type { DialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import { useI18n } from '@/shared/i18n'

export type TaskApprovalDecision = 'ALLOW' | 'DENY'

/**
 * 子任务运行状态 widget（挂载在 ThreadWidgetStack.children 中）。
 *
 * - 只读消费 timeline 消息，通过 {@link createTaskLevelStateMap} 一次 reduce
 *   完成聚合：同一子 Thread 的 heartbeat 只保留最新一帧；
 * - 无活动 task 时返回 null（不占位）；
 * - waiting_approval / 存在审批项时展示子工具名称、原因与 Allow/Deny；
 * - 祖先 heartbeat 中的 descendants 会成为独立状态行，决策按各自 status.threadId
 *   回传实际目标 Thread，由宿主 controller 转发。
 */
export function TaskStatusWidget({
  messages,
  parentTaskLevel = 0,
  approvalPending = false,
  onDecideApproval,
}: {
  messages: readonly DialogueMessage[]
  parentTaskLevel?: number
  /** 进行中的全局审批请求：所有未决的审批按钮都禁用。 */
  approvalPending?: boolean
  onDecideApproval?: (
    threadId: string,
    invocationId: string,
    decision: TaskApprovalDecision,
  ) => void
}) {
  const { t } = useI18n()
  const levels = useMemo(
    () => createTaskLevelStateMap(messages, parentTaskLevel),
    [messages, parentTaskLevel],
  )
  if (levels.size === 0) {
    return null
  }
  let total = 0
  const rows: ReactNode[] = []
  // 按 level（parentTaskLevel + depth）升序，先展示更靠近父 Thread 的任务。
  for (const [, statuses] of [...levels.entries()].sort(([a], [b]) => a - b)) {
    total += statuses.length
    for (const status of statuses) {
      rows.push(
        <TaskStatusRow
          key={status.threadId}
          status={status}
          approvalPending={approvalPending}
          onDecideApproval={onDecideApproval}
        />,
      )
    }
  }
  return (
    <div className="task-status-widget" aria-label={t('ai.runtime.task.widget')}>
      <div className="task-status-heading" aria-live="polite">
        <span className="thread-working-dot" aria-hidden="true" />
        <span>{t('ai.runtime.task.running', { count: total })}</span>
      </div>
      <ol className="task-status-list" aria-label={t('ai.runtime.task.widget')}>
        {rows}
      </ol>
    </div>
  )
}

function TaskStatusRow({
  status,
  approvalPending,
  onDecideApproval,
}: {
  status: TaskStatusState
  approvalPending: boolean
  onDecideApproval?: (
    threadId: string,
    invocationId: string,
    decision: TaskApprovalDecision,
  ) => void
}) {
  const { t } = useI18n()
  // 有审批项（无论当前 state 是否已标记 waiting_approval）都需要展示决策入口。
  const approvals =
    status.state === 'waiting_approval' || status.approvals.length > 0
      ? status.approvals
      : []
  return (
    <li
      className="task-status-item"
      // 树前缀按子任务在任务树中的 depth 缩进（与聚合 level 的偏移解耦）。
      style={{ '--task-depth': status.depth ?? 0 } as CSSProperties}
    >
      <div className="task-status-line">
        <span className={`task-status-dot ${status.state}`} aria-hidden="true" />
        <span className="task-status-subagent">{status.subagentType}</span>
        <span className={`task-status-state ${status.state}`}>
          {t(runStateKey(status.state))}
        </span>
        {status.turns != null ? (
          <span className="task-status-metric">
            {t('ai.runtime.task.turns', { count: status.turns })}
          </span>
        ) : null}
        {status.toolCalls != null ? (
          <span className="task-status-metric">
            {t('ai.runtime.task.toolCalls', { count: status.toolCalls })}
          </span>
        ) : null}
        {status.lastActivity != null ? (
          <span className="task-status-activity">{status.lastActivity}</span>
        ) : null}
      </div>
      {approvals.length > 0 ? (
        <div className="task-status-approvals" role="group" aria-label={t('ai.runtime.task.widget')}>
          {approvals.map((approval) => (
            <div key={approval.invocationId} className="task-status-approval">
              <span className="task-status-approval-text">
                {t('ai.runtime.task.approvalRequested', { toolName: approval.toolName })}
                {approval.reason ? ` — ${approval.reason}` : ''}
              </span>
              <button
                type="button"
                className="btn-primary"
                disabled={approvalPending}
                aria-label={t('ai.runtime.task.allowTool', { toolName: approval.toolName })}
                onClick={() => onDecideApproval?.(status.threadId, approval.invocationId, 'ALLOW')}
              >
                {t('ai.runtime.approval.allow')}
              </button>
              <button
                type="button"
                className="ghost-btn danger"
                disabled={approvalPending}
                aria-label={t('ai.runtime.task.denyTool', { toolName: approval.toolName })}
                onClick={() => onDecideApproval?.(status.threadId, approval.invocationId, 'DENY')}
              >
                {t('ai.runtime.approval.deny')}
              </button>
            </div>
          ))}
        </div>
      ) : null}
    </li>
  )
}
