import { AlertCircle, Check, ShieldAlert, X } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { extractApprovalEditReview } from '@/features/thread/runtime/state/mutation-review'
import { asRecord } from '@/features/thread/runtime/thread-timeline/payload-json'
import { previewForToolCall } from '@/features/thread/runtime/thread-panel/messages/tool-previews'
import { ToolCallPreview } from '@/features/thread/runtime/thread-panel/messages/ToolCallPreview'
import { useApprovalDecision } from '@/features/thread/runtime/interactions/useApprovalDecision'
import './interaction-cards.css'

export interface ApprovalCardProps {
  threadId: string
  invocationId: string
  toolName: string
  approval?: unknown
  argumentsJson?: string
  queuedSameFileCount?: number
  onSuccess?: () => void
}

export function ApprovalCard({
  threadId,
  invocationId,
  toolName,
  approval,
  argumentsJson,
  queuedSameFileCount = 0,
  onSuccess,
}: ApprovalCardProps) {
  const { t } = useI18n()
  const [denyReason, setDenyReason] = useState('')
  const { busy, error, isNonRecoverable, decide } = useApprovalDecision({
    threadId,
    invocationId,
    onSuccess,
  })

  const approvalData = useMemo(() => asRecord(approval), [approval])

  const reason = typeof approvalData.reason === 'string' ? approvalData.reason : null
  const decided = approvalData.decision === 'ALLOWED' || approvalData.decision === 'DENIED'

  const normalizedTool = toolName.trim().toLowerCase()
  const isEdit = normalizedTool === 'edit'
  const isWrite = normalizedTool === 'write'
  const isMutation = isWrite || isEdit

  const editReview = useMemo(() => extractApprovalEditReview(approval), [approval])
  const isMutationMissingReview = isMutation && !editReview

  const mutationPreview = useMemo(() => {
    if (!isMutation) return null
    return previewForToolCall(toolName, argumentsJson || '', {
      editReview,
      isWaitingReview: true,
    })
  }, [isMutation, toolName, argumentsJson, editReview])

  const handleDecision = (decision: 'ALLOW' | 'DENY') => {
    void decide({
      decision,
      denyReason,
      decided,
      mutationReviewMissing: isMutationMissingReview,
    })
  }

  if (decided) {
    const isAllowed = approvalData.decision === 'ALLOWED'
    return (
      <div className={`interaction-approval-card is-completed ${isAllowed ? 'is-allowed' : 'is-denied'}`}>
        <span className={`interaction-questionnaire-badge ${isAllowed ? 'completed' : 'declined'}`}>
          {isAllowed ? <Check size={14} /> : <X size={14} />}
          <span>{isAllowed ? t('ai.interaction.allow') : t('ai.interaction.deny')}</span>
        </span>
      </div>
    )
  }

  return (
    <div className="interaction-approval-card">
      <div className="interaction-approval-header">
        <div className="interaction-approval-title-row">
          <ShieldAlert size={16} className="interaction-approval-icon" />
          <span className="interaction-approval-tool-name">{toolName}</span>
        </div>
        {reason ? (
          <div className="interaction-approval-reason">
            <span className="interaction-approval-reason-label">
              {t('ai.interaction.approvalReason')}:
            </span>
            <span className="interaction-approval-reason-text">
              {reason === 'Permission rules require approval'
                ? t('ai.interaction.defaultApprovalReason') : reason}
            </span>
          </div>
        ) : null}
      </div>

      {argumentsJson ? (
        <div className="interaction-approval-mutation-preview">
          <ToolCallPreview
            preview={mutationPreview}
            streaming={false}
            variant="approval"
            toolName={toolName}
            argumentsJson={argumentsJson}
          />
        </div>
      ) : null}

      {queuedSameFileCount > 0 ? (
        <div className="interaction-approval-reason">
          {queuedSameFileCount === 1
            ? t('ai.interaction.queuedSameFile.one')
            : t('ai.interaction.queuedSameFile.other', { count: queuedSameFileCount })}
        </div>
      ) : null}

      {isMutationMissingReview ? (
        <div className="interaction-error-banner">
          <AlertCircle size={16} aria-hidden="true" />
          <span>{t('ai.runtime.mutation.reviewMissing')}</span>
        </div>
      ) : null}

      {error ? (
        <div
          className={`interaction-error-banner ${isNonRecoverable ? 'is-non-recoverable' : ''}`}
        >
          <AlertCircle size={16} aria-hidden="true" />
          <span>{error}</span>
        </div>
      ) : null}

      <label className="interaction-approval-deny-reason">
        <span>{t('ai.interaction.denyReason')}</span>
        <input
          type="text"
          value={denyReason}
          aria-label={t('ai.interaction.denyReason')}
          placeholder={t('ai.interaction.denyReasonOptional')}
          disabled={busy || isNonRecoverable}
          onChange={(event) => setDenyReason(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault()
              handleDecision('DENY')
            }
          }}
        />
      </label>

      <div className="interaction-actions-bar">
        <Button
          variant="primary"
          danger
          className="interaction-deny-btn"
          disabled={busy || isNonRecoverable}
          onClick={() => handleDecision('DENY')}
        >
          {t('ai.interaction.deny')}
        </Button>

        <Button
          variant="primary"
          className="interaction-allow-btn"
          disabled={busy || isNonRecoverable || isMutationMissingReview}
          onClick={() => handleDecision('ALLOW')}
        >
          {t('ai.interaction.allow')}
        </Button>
      </div>
    </div>
  )
}
