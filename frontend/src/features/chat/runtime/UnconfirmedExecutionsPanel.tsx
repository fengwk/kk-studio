import { useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { Button } from '@/shared/ui/controls/Button'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'
import { isConflictError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'
import type { HarnessThreadSnapshotDTO } from '@/shared/api/generated/contracts'
import '@/features/chat/runtime/unconfirmed-executions.css'

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
 * STOPPED Thread 的未确认退出事实与逐项“放弃等待”入口。
 *
 * <p>该入口只消费投影中的持久事实，不代表执行已终止；每个 executionId 独立确认，
 * 一次请求只处理用户选择的那一代执行，不做批量或自动重试。成功只让既有 snapshot/tree
 * query 失效并回读权威快照，不把本次响应直接写回缓存；CAS 冲突同样只回读最新事实，
 * 让用户按新列表重新选择。
 */
export function UnconfirmedExecutionsPanel({
  threadId,
  rootThreadId,
  expectedVersion,
  executions,
}: {
  threadId: string
  rootThreadId: string
  expectedVersion: string
  executions: readonly UnconfirmedExecution[]
}) {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const [target, setTarget] = useState<UnconfirmedExecution | null>(null)
  const [pendingExecutionId, setPendingExecutionId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const inFlightRef = useRef(false)
  const pending = pendingExecutionId != null

  // 快照更新（真实退出 ACK 或其它标签页放弃）后，已消失的执行不能继续弹窗。
  useEffect(() => {
    setTarget((current) => (
      current != null && executions.some((item) => item.executionId === current.executionId)
        ? current
        : null
    ))
  }, [executions])

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

  if (executions.length === 0) {
    return null
  }

  return (
    <section
      className="thread-unconfirmed-executions"
      aria-label={t('ai.runtime.unconfirmedExecutions.title')}
    >
      <header className="thread-unconfirmed-executions-header">
        <h3 className="thread-unconfirmed-executions-title">
          {t('ai.runtime.unconfirmedExecutions.title')}
        </h3>
        <p className="thread-unconfirmed-executions-hint">
          {t('ai.runtime.unconfirmedExecutions.hint')}
        </p>
      </header>
      {error ? (
        <p className="field-error thread-unconfirmed-executions-error" role="alert">
          {error}
        </p>
      ) : null}
      <ul className="thread-unconfirmed-executions-list">
        {executions.map((execution) => (
          <li
            key={execution.executionId}
            className="thread-unconfirmed-executions-item"
            data-execution-id={execution.executionId}
          >
            <div className="thread-unconfirmed-executions-facts">
              <span>{t('ai.runtime.unconfirmedExecutions.itemKind', { kind: execution.kind })}</span>
              <span>{t('ai.runtime.unconfirmedExecutions.itemAttempt', { attempt: execution.attempt })}</span>
              <code>{t('ai.runtime.unconfirmedExecutions.itemExecutionId', {
                executionId: execution.executionId,
              })}</code>
            </div>
            <Button
              variant="ghost"
              size="compact"
              danger
              disabled={pending}
              aria-label={t('ai.runtime.unconfirmedExecutions.abandonItem', {
                kind: execution.kind,
                attempt: execution.attempt,
              })}
              onClick={() => {
                setError(null)
                setTarget(execution)
              }}
            >
              {t('ai.runtime.unconfirmedExecutions.abandon')}
            </Button>
          </li>
        ))}
      </ul>
      <ConfirmActionModal
        modal={target == null
          ? null
          : {
              title: t('ai.runtime.unconfirmedExecutions.confirmTitle', {
                kind: target.kind,
                attempt: target.attempt,
              }),
              description: t('ai.runtime.unconfirmedExecutions.confirmDescription', {
                executionId: target.executionId,
              }),
              confirmLabel: t('ai.runtime.unconfirmedExecutions.confirmAction'),
              icon: 'delete',
              tone: 'danger',
              onConfirm: () => {
                void confirmAbandon()
              },
            }}
        pending={pending}
        onClose={() => {
          if (!pending) {
            setTarget(null)
          }
        }}
      />
    </section>
  )
}
