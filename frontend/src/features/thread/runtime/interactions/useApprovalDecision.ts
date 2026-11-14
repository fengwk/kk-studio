import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { isConflictError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import { useI18n } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { createUuid } from '@/shared/lib/uuid'

/**
 * 一次审批决定的提交与重试语义。
 *
 * <p>同一 (thread, invocation, decision) 复用冻结的 decisionId，因此网络失败后的重复
 * 提交在服务端仍是同一次决定；409 视为不可恢复（界面保留只读完成态），其他错误可原样
 * 重试。提交前会拒绝在只读完成态、在途、或缺少 mutation review 的放行请求。
 */
export function useApprovalDecision({
  threadId,
  invocationId,
  onSuccess,
}: {
  threadId: string
  invocationId: string
  onSuccess?: () => void
}) {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [isNonRecoverable, setIsNonRecoverable] = useState(false)
  // 幂等 decisionId
  const [decisionIdMap] = useState(() => new Map<string, string>())

  async function decide({
    decision,
    denyReason,
    decided,
    mutationReviewMissing,
  }: {
    decision: 'ALLOW' | 'DENY'
    denyReason: string
    decided: boolean
    mutationReviewMissing: boolean
  }): Promise<void> {
    if (busy || isNonRecoverable || decided) {
      return
    }
    if (decision === 'ALLOW' && mutationReviewMissing) {
      return
    }
    setBusy(true)
    setError(null)

    const key = `${threadId}:${invocationId}:${decision}`
    let decisionId = decisionIdMap.get(key)
    if (!decisionId) {
      decisionId = createUuid()
      decisionIdMap.set(key, decisionId)
    }

    try {
      await harnessService.decideApproval(threadId, invocationId, {
        decision,
        decisionId,
        reason: decision === 'DENY' ? denyReason.trim() || null : null,
      })
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.interactions.all }),
        queryClient.invalidateQueries({
          queryKey: queryKeys.threads.snapshot(threadId),
        }),
      ])
      onSuccess?.()
    } catch (err: unknown) {
      if (isConflictError(err)) {
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

  return { busy, error, isNonRecoverable, decide }
}
