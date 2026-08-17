import type { ToolDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import type {
  ToolApprovalDecision,
  ToolRenderContext,
} from '@/features/ai/runtime/thread-panel/messages/tool-message-view'
import { useI18n } from '@/shared/i18n'

/** 待决审批闸门：只展示持久化决策或当前可操作的审批请求。 */
export function ToolApprovalBar({
  context,
  message,
  onDecideApproval,
}: {
  context: ToolRenderContext
  message: ToolDialogueMessage
  onDecideApproval?: (
    message: ToolDialogueMessage,
    decision: ToolApprovalDecision,
  ) => void
}) {
  const { t } = useI18n()
  const approval = context.approval
  if (!approval?.required) {
    return null
  }
  if (approval.decision != null) {
    const decidedText =
      approval.decision === 'ALLOWED'
        ? t('ai.runtime.approval.allowed')
        : t('ai.runtime.approval.denied')
    return (
      <div className="thread-tool-approval is-decided" aria-label={t('ai.runtime.approval.title')}>
        <span className="thread-tool-approval-text">
          {decidedText}
          {approval.reason ? ` — ${approval.reason}` : ''}
        </span>
      </div>
    )
  }
  if (!onDecideApproval) {
    return null
  }
  return (
    <div className="thread-tool-approval" role="group" aria-label={t('ai.runtime.approval.title')}>
      <span className="thread-tool-approval-text">{t('ai.runtime.approval.requested')}</span>
      <button
        type="button"
        className="btn-primary"
        disabled={context.approvalPending}
        onClick={() => onDecideApproval(message, 'ALLOW')}
      >
        {t('ai.runtime.approval.allow')}
      </button>
      <button
        type="button"
        className="ghost-btn"
        disabled={context.approvalPending}
        onClick={() => onDecideApproval(message, 'DENY')}
      >
        {t('ai.runtime.approval.deny')}
      </button>
    </div>
  )
}
