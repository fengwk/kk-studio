import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { ThreadLink } from '@/features/thread/runtime/ThreadLink'
import { MetaMessageBlock } from '@/features/thread/runtime/thread-panel/messages/MetaMessageBlock'
import { useCompactionBranch } from '@/features/thread/runtime/useCompactionBranch'
import { createTurnUsageMetaMessage } from '@/features/thread/runtime/thread-timeline/meta-projection'
import { formatCompactTokens } from '@/features/thread/runtime/thread-timeline/content-utils'
import { errorDetailMessage } from '@/shared/lib/user-facing-error'
import '@/features/thread/runtime/child-thread-view.css'
import './compaction-branch-view.css'

/**
 * 压缩执行视图：只读展示分支自己的 Entry（从 FORK 之后加载），没有 Composer。
 *
 * 顶部只保留身份、返回与运行时的 Stop；正文按“继承量 → 指令 → 各次模型调用用量 →
 * 被停用的工具结果”呈现。停止是唯一写操作，其余全部来自持久 Entry 事实。
 */
export function CompactionBranchView({
  threadId,
  afterEntryId,
  onBack,
}: {
  threadId: string
  /** 分支 FORK 之前的切点 Entry：后端只返回其后分支自己的 Entry。 */
  afterEntryId: string
  /** pane 内返回上一层；独立地址没有上一层时提供父 Thread 链接。 */
  onBack?: () => void
}) {
  const { t } = useI18n()
  const branch = useCompactionBranch(threadId, afterEntryId)
  const stopError = branch.stopError == null
    ? null
    : errorDetailMessage(branch.stopError) ?? t('ai.runtime.compactionBranch.stopFailed')
  const parentThreadId = branch.thread?.parentThreadId ?? null
  return (
    <div className="compaction-branch-view" data-testid="compaction-branch-view">
      <header className="compaction-branch-header">
        {onBack != null ? (
          <Button variant="ghost" size="compact" onClick={onBack}>
            {t('ai.runtime.compactionBranch.back')}
          </Button>
        ) : parentThreadId != null ? (
          <ThreadLink className="ghost-btn is-compact" threadId={parentThreadId}>
            {t('ai.runtime.childThread.back')}
          </ThreadLink>
        ) : null}
        <h2 className="compaction-branch-title">{t('ai.runtime.compactionBranch.title')}</h2>
        <span className="thread-readonly-badge">{t('ai.runtime.childThread.readOnly')}</span>
        {branch.running ? (
          <Button
            size="compact"
            variant="ghost"
            className="compaction-branch-stop"
            disabled={branch.stopPending}
            loading={branch.stopPending}
            onClick={branch.stop}
          >
            {t(branch.stopPending
              ? 'ai.runtime.compactionBranch.stopping'
              : 'ai.runtime.compactionBranch.stop')}
          </Button>
        ) : null}
      </header>
      {branch.isLoading ? (
        <p className="compaction-branch-state" role="status">{t('ai.runtime.compactionBranch.loading')}</p>
      ) : null}
      {branch.isError ? (
        <div className="compaction-branch-state is-error" role="alert">
          <p>{t('ai.runtime.compactionBranch.loadFailed')}</p>
          <Button variant="ghost" size="compact" onClick={branch.refetch}>
            {t('ai.runtime.compactionBranch.retry')}
          </Button>
        </div>
      ) : null}
      {stopError != null ? (
        <p className="compaction-branch-state is-error" role="alert">{stopError}</p>
      ) : null}
      {branch.branch != null ? (
        <>
          <p className="compaction-branch-context" data-testid="compaction-branch-inherited">
            {branch.branch.inheritedContextTokens == null
              ? t('ai.runtime.compactionBranch.inheritedContextUnknown')
              : t('ai.runtime.compactionBranch.inheritedContext', {
                tokens: formatCompactTokens(branch.branch.inheritedContextTokens),
              })}
          </p>
          {branch.branch.instruction != null ? (
            <section className="compaction-branch-section" aria-label={t('ai.runtime.compactionBranch.instruction')}>
              <h3 className="compaction-branch-section-title">{t('ai.runtime.compactionBranch.instruction')}</h3>
              <pre className="compaction-branch-instruction">{branch.branch.instruction}</pre>
            </section>
          ) : null}
          <section className="compaction-branch-section" aria-label={t('ai.runtime.compactionBranch.modelCalls')}>
            <h3 className="compaction-branch-section-title">{t('ai.runtime.compactionBranch.modelCalls')}</h3>
            {branch.branch.modelCalls.length === 0
              ? <p className="compaction-branch-state">{t('ai.runtime.compactionBranch.noModelCalls')}</p>
              : branch.branch.modelCalls.map((call) => (
                <div className="compaction-branch-call" key={call.entryId} data-entry-id={call.entryId}>
                  <MetaMessageBlock
                    message={createTurnUsageMetaMessage(call.entryId, call.usage, call.createdAt)}
                  />
                </div>
              ))}
          </section>
          <section className="compaction-branch-section" aria-label={t('ai.runtime.compactionBranch.toolErrors')}>
            <h3 className="compaction-branch-section-title">{t('ai.runtime.compactionBranch.toolErrors')}</h3>
            {branch.branch.toolErrors.length === 0
              ? <p className="compaction-branch-state">{t('ai.runtime.compactionBranch.noToolErrors')}</p>
              : branch.branch.toolErrors.map((toolError) => (
                <div className="compaction-branch-tool-error" key={toolError.id}>
                  <span className="compaction-branch-tool-name">{toolError.toolName}</span>
                  <span className="compaction-branch-tool-text">{toolError.text}</span>
                </div>
              ))}
          </section>
        </>
      ) : null}
    </div>
  )
}
