import { useRef } from 'react'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { ThreadLink } from '@/features/thread/runtime/panes/ThreadLink'
import { ThreadPanel } from '@/features/thread/runtime/thread-panel'
import { MetaMessageBlock } from '@/features/thread/runtime/thread-panel/messages/MetaMessageBlock'
import { useCompactionBranch } from '@/features/thread/runtime/queries/useCompactionBranch'
import { createTurnUsageMetaMessage } from '@/features/thread/runtime/thread-timeline/meta-projection'
import { formatCompactTokens } from '@/features/thread/runtime/thread-timeline/content-utils'
import { errorDetailMessage } from '@/shared/lib/user-facing-error'
import './child-thread-view.css'
import './compaction-branch-view.css'

/** 压缩执行复用只读对话外壳。Stop 是唯一写操作。 */
export function CompactionBranchView({
  threadId,
  afterEntryId,
  onBack,
}: {
  threadId: string
  /** 分支 FORK 之前的切点。 */
  afterEntryId: string
  onBack?: () => void
}) {
  const { t } = useI18n()
  const bodyRef = useRef<HTMLDivElement>(null)
  const { branch, thread, messages, running, ...state } = useCompactionBranch(threadId, afterEntryId)
  const stopError = state.stopError == null
    ? null
    : errorDetailMessage(state.stopError) ?? t('ai.runtime.compactionBranch.stopFailed')
  const outcome = branch?.outcome
  const status = outcome === 'STOPPED' || (!running && thread?.executionControl === 'STOPPED')
    ? t('ai.runtime.entry.compactionStopped')
    : outcome === 'FAILED' || outcome === 'CANCELLED'
      ? t('ai.runtime.entry.compactionFailed')
      : running
        ? t('ai.runtime.compactionBranch.generating')
        : null
  const candidate = !running && messages.length > 0 ? t('ai.runtime.compactionBranch.candidate') : null
  const hasDiagnostics = branch != null && (
    branch.instruction != null || branch.modelCalls.length > 0 || branch.toolErrors.length > 0
  )
  return (
    <div className="bound-thread-view compaction-branch-view" data-testid="compaction-branch-view">
      <ThreadPanel
        heading={(
          <>
            <header className="compaction-branch-header">
              {onBack != null ? (
                <Button variant="secondary" size="compact" onClick={onBack}>{t('ai.runtime.compactionBranch.back')}</Button>
              ) : thread?.parentThreadId != null ? (
                <ThreadLink className="btn-secondary is-compact" threadId={thread.parentThreadId}>
                  {t('ai.runtime.childThread.back')}
                </ThreadLink>
              ) : null}
              <h2 className="compaction-branch-title">{t('ai.runtime.compactionBranch.title')}</h2>
              <span className="thread-readonly-badge">{t('ai.runtime.childThread.readOnly')}</span>
              {running ? (
                <Button size="compact" variant="secondary" className="compaction-branch-stop"
                  disabled={state.stopPending} loading={state.stopPending} onClick={state.stop}>
                  {t(state.stopPending ? 'ai.runtime.compactionBranch.stopping' : 'ai.runtime.compactionBranch.stop')}
                </Button>
              ) : null}
            </header>
            {branch != null && (status != null || candidate != null) ? (
              <p className="compaction-branch-state" role="status">
                {[status, candidate].filter(Boolean).join(' · ')}
              </p>
            ) : null}
          </>
        )}
        transcript={{
          messages,
          queuedMessages: [],
          loading: state.isLoading,
          error: state.isError,
          errorText: t(state.unavailable ? 'ai.runtime.thread.unavailable' : 'ai.runtime.compactionBranch.loadFailed'),
          onRetry: state.unavailable ? undefined : state.refetch,
          emptyText: messages.length === 0 ? t('ai.runtime.compactionBranch.empty') : undefined,
          bodyRef,
          resetKey: threadId,
        }}
        activity={{ working: false, actionError: stopError }}
        slots={{ footer: hasDiagnostics ? (
          <details className="compaction-branch-diagnostics" key={threadId}>
            <summary>{t('ai.runtime.compactionBranch.diagnostics')}</summary>
            {branch.inheritedContextTokens != null ? (
              <p className="compaction-branch-context" data-testid="compaction-branch-inherited">
                {t('ai.runtime.compactionBranch.inheritedContext', {
                  tokens: formatCompactTokens(branch.inheritedContextTokens),
                })}
              </p>
            ) : null}
            {branch.instruction != null ? (
              <section aria-label={t('ai.runtime.compactionBranch.instruction')}>
                <h3>{t('ai.runtime.compactionBranch.instruction')}</h3>
                <pre className="compaction-branch-instruction">{branch.instruction}</pre>
              </section>
            ) : null}
            {branch.modelCalls.length > 0 ? (
              <section aria-label={t('ai.runtime.compactionBranch.modelCalls')}>
                <h3>{t('ai.runtime.compactionBranch.modelCalls')}</h3>
                {branch.modelCalls.map((call) => (
                  <div className="compaction-branch-call" key={call.entryId}>
                    <MetaMessageBlock message={createTurnUsageMetaMessage(call.entryId, call.usage, call.createdAt)} />
                  </div>
                ))}
              </section>
            ) : null}
            {branch.toolErrors.length > 0 ? (
              <section aria-label={t('ai.runtime.compactionBranch.toolErrors')}>
                <h3>{t('ai.runtime.compactionBranch.toolErrors')}</h3>
                {branch.toolErrors.map((toolError) => (
                  <div className="compaction-branch-tool-error" key={toolError.id}>
                    <strong>{toolError.toolName}</strong> {toolError.text}
                  </div>
                ))}
              </section>
            ) : null}
          </details>
        ) : undefined }}
      />
    </div>
  )
}
