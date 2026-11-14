import { Button } from '@/shared/ui/controls/Button'
import { ConfirmActionModal } from '@/shared/ui/overlays/ConfirmActionModal'
import { useI18n } from '@/shared/i18n'
import type {
  HarnessThreadSnapshotDTO,
  ThreadExecutionControl,
} from '@/shared/api/generated/contracts'
import { useUnconfirmedExecutionAbandon } from '@/features/thread/runtime/useUnconfirmedExecutionAbandon'
import '@/features/thread/runtime/unconfirmed-executions.css'

type UnconfirmedExecution = HarnessThreadSnapshotDTO['unconfirmedExecutions'][number]

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
  executionControl,
  executions,
  onStop,
  stopPending = false,
}: {
  threadId: string
  rootThreadId: string
  expectedVersion: string
  executionControl: ThreadExecutionControl
  executions: readonly UnconfirmedExecution[]
  onStop?: () => void
  stopPending?: boolean
}) {
  const { t } = useI18n()
  const {
    target,
    setTarget,
    pending,
    error,
    setError,
    canAbandon,
    confirmAbandon,
  } = useUnconfirmedExecutionAbandon({
    threadId,
    rootThreadId,
    expectedVersion,
    executionControl,
    executions,
  })

  if (executions.length === 0) {
    return null
  }

  return (
    <section
      className="thread-unconfirmed-executions"
      data-execution-control={executionControl}
      aria-label={t('ai.runtime.unconfirmedExecutions.title')}
    >
      <header className="thread-unconfirmed-executions-header">
        <h3 className="thread-unconfirmed-executions-title">
          {t('ai.runtime.unconfirmedExecutions.title')}
        </h3>
        <p className="thread-unconfirmed-executions-hint">
          {executionControl === 'STOPPED'
            ? t('ai.runtime.unconfirmedExecutions.hint')
            : t('ai.runtime.unconfirmedExecutions.hintRunnable')}
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
            {canAbandon ? (
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
            ) : null}
          </li>
        ))}
      </ul>
      {!canAbandon && onStop != null ? (
        <div className="thread-unconfirmed-executions-actions">
          <Button
            variant="primary"
            size="compact"
            disabled={stopPending}
            onClick={onStop}
          >
            {t('ai.runtime.unconfirmedExecutions.stopAndHandle')}
          </Button>
        </div>
      ) : null}
      <ConfirmActionModal
        className="modal-touch-target"
        modal={!canAbandon || target == null
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
