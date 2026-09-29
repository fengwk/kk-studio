import { useQueryClient } from '@tanstack/react-query'
import { AlertCircle, Check, ShieldAlert, X } from 'lucide-react'
import { useMemo, useState } from 'react'
import { isConflictError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import { useI18n } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { createUuid } from '@/shared/lib/uuid'

export interface ApprovalCardProps {
  threadId: string
  invocationId: string
  toolName: string
  approvalJson?: string | null
  argumentsJson?: string
  onSuccess?: () => void
}

export function ApprovalCard({
  threadId,
  invocationId,
  toolName,
  approvalJson,
  argumentsJson,
  onSuccess,
}: ApprovalCardProps) {
  const { t } = useI18n()
  const queryClient = useQueryClient()

  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [isNonRecoverable, setIsNonRecoverable] = useState(false)

  // 幂等 decisionId
  const [decisionIdMap] = useState(() => new Map<string, string>())

  const approvalData = useMemo(() => {
    if (!approvalJson) return null
    try {
      return JSON.parse(approvalJson)
    } catch {
      return null
    }
  }, [approvalJson])

  const reason = approvalData?.reason || null
  const decided = approvalData?.decision === 'ALLOWED' || approvalData?.decision === 'DENIED'

  const handleDecision = async (decision: 'ALLOW' | 'DENY') => {
    if (busy || isNonRecoverable || decided) return
    setBusy(true)
    setError(null)

    const key = `${decision}`
    let decisionId = decisionIdMap.get(key)
    if (!decisionId) {
      decisionId = createUuid()
      decisionIdMap.set(key, decisionId)
    }

    try {
      await harnessService.decideApproval(threadId, invocationId, {
        decision,
        decisionId,
        reason: null,
      })
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.interactions.all }),
        queryClient.invalidateQueries({
          queryKey: queryKeys.threads.snapshot(threadId),
        }),
      ])
      onSuccess?.()
    } catch (err: unknown) {
      if (isConflictError(err) || (err instanceof Error && err.message.includes('409'))) {
        setIsNonRecoverable(true)
        setError(t('ai.interaction.nonRecoverable'))
      } else {
        const msg = err instanceof Error ? err.message : t('ai.common.operationFailed')
        setError(msg)
      }
    } finally {
      setBusy(false)
    }
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
            <span className="interaction-approval-reason-text">{reason}</span>
          </div>
        ) : null}
      </div>

      {argumentsJson ? (
        <div className="interaction-approval-arguments">
          <pre className="interaction-raw-pre">{argumentsJson}</pre>
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

      <div className="interaction-actions-bar">
        <button
          type="button"
          className="ghost-btn interaction-deny-btn"
          disabled={busy || isNonRecoverable}
          onClick={() => handleDecision('DENY')}
        >
          {t('ai.interaction.deny')}
        </button>

        <button
          type="button"
          className="btn-primary interaction-allow-btn"
          disabled={busy || isNonRecoverable}
          onClick={() => handleDecision('ALLOW')}
        >
          {t('ai.interaction.allow')}
        </button>
      </div>
    </div>
  )
}
