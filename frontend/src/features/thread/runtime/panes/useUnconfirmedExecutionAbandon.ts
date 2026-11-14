import { useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { isConflictError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'
import type {
  HarnessThreadSnapshotDTO,
  ThreadExecutionControl,
} from '@/shared/api/generated/contracts'

type UnconfirmedExecution = HarnessThreadSnapshotDTO['unconfirmedExecutions'][number]

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message.trim()) {
    return error.message
  }
  if (typeof error === 'string' && error.trim()) {
    return error
  }
  return ''
}

/**
 * 逐项“放弃等待”的状态机：确认目标、在途执行、错误与权威回读。
 *
 * <p>每次请求只处理用户选定的那一代执行，不做批量或自动重试。成功只让既有
 * snapshot/tree query 失效并回读权威快照，不把本次响应直接写回缓存；CAS 冲突同样只
 * 回读最新事实，让用户按新列表重新选择。
 */
export function useUnconfirmedExecutionAbandon({
  threadId,
  rootThreadId,
  expectedVersion,
  executionControl,
  executions,
}: {
  threadId: string
  rootThreadId: string
  expectedVersion: string
  executionControl: ThreadExecutionControl
  executions: readonly UnconfirmedExecution[]
}) {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const [target, setTarget] = useState<UnconfirmedExecution | null>(null)
  const [pendingExecutionId, setPendingExecutionId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const inFlightRef = useRef(false)
  const pending = pendingExecutionId != null
  // RUNNABLE 只展示退出待确认原因与既有 Stop 入口；放弃确认只在 STOPPED 后开放。
  const canAbandon = executionControl === 'STOPPED'

  // 快照更新（真实退出 ACK 或其它标签页放弃）后，已消失的执行不能继续弹窗。
  useEffect(() => {
    setTarget((current) => (
      current != null && executions.some((item) => item.executionId === current.executionId)
        ? current
        : null
    ))
  }, [executions])

  // executionControl 变为 RUNNABLE（例如恢复执行）时关闭仅 STOPPED 才开放的放弃确认。
  useEffect(() => {
    if (!canAbandon) {
      setTarget(null)
    }
  }, [canAbandon])

  async function refreshFacts(): Promise<void> {
    const invalidations = [
      queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) }),
    ]
    if (rootThreadId) {
      invalidations.push(
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.tree(rootThreadId) }),
      )
    }
    await Promise.all(invalidations)
  }

  async function confirmAbandon(): Promise<void> {
    if (target == null || inFlightRef.current) {
      return
    }
    const executionId = target.executionId
    inFlightRef.current = true
    setPendingExecutionId(executionId)
    setError(null)
    try {
      await harnessService.abandonExecution(threadId, executionId, {
        expectedVersion,
      })
      setTarget(null)
      // 统一由既有 query 回读权威快照：迟到的 abandon 响应不能覆盖更新的 snapshot 状态。
      await refreshFacts()
    } catch (requestError: unknown) {
      setTarget(null)
      if (isConflictError(requestError)) {
        setError(t('ai.runtime.unconfirmedExecutions.stale'))
        await refreshFacts()
      } else {
        setError(t('ai.runtime.unconfirmedExecutions.failed', {
          error: errorMessage(requestError),
        }))
      }
    } finally {
      inFlightRef.current = false
      setPendingExecutionId(null)
    }
  }

  return {
    target,
    setTarget,
    pending,
    error,
    setError,
    canAbandon,
    confirmAbandon,
  }
}
