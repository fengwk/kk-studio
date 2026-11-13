import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { SystemMessageBody } from '@/features/thread/runtime/thread-panel/messages/SystemMessageBody'
import { SystemMessageCard } from '@/features/thread/runtime/thread-panel/messages/SystemMessageCard'
import { ThreadLink } from '@/features/thread/runtime/ThreadLink'
import {
  compactionRetryDisabledReasonKey,
  useCompactionActions,
} from '@/features/thread/runtime/thread-panel/compaction-actions-context'
import type { CompactionCardState, EntryEventDialogueMessage } from '@/features/thread/runtime/thread-timeline-types'
import {
  compactionCardTitleKey,
  compactionFailureReasonKey,
} from '@/features/thread/runtime/thread-timeline/compaction-turn'

const CARD_TONES = {
  running: 'pending',
  compacted: 'neutral',
  failed: 'error',
  stopped: 'info',
} as const

/**
 * 压缩卡：四个状态都只来自压缩回合的持久事实。
 *
 * <p>已压缩时保留原行为——默认 0 行、展开显示完整摘要；进行中/失败/停止只显示标题与
 * 一句结果说明。带分支 Thread id 的卡片提供“查看执行”，失败与停止卡片在主对话内
 * 提供与命令面板同源可用性的“重新压缩”。
 */
export function CompactionEntryBlock({ message }: { message: EntryEventDialogueMessage }) {
  const { t } = useI18n()
  const actions = useCompactionActions()
  const fact = message.compaction
  const state: CompactionCardState = fact?.state ?? 'compacted'
  const titleText = t(compactionCardTitleKey(state))
  const noteKey = state === 'failed'
    ? compactionFailureReasonKey(fact?.errorCode ?? null)
    : state === 'stopped'
      ? 'ai.runtime.entry.compactionStoppedHint'
      : state === 'running' ? 'ai.runtime.entry.compactionRunningHint' : null
  const childThreadId = fact?.childThreadId ?? null
  const cutEntryId = fact?.cutEntryId ?? null
  const showRetry = actions != null && (state === 'failed' || state === 'stopped')
  const retryDisabledReason = actions != null && showRetry
    ? compactionRetryDisabledReasonKey(actions)
    : null
  const viewExecutionLabel = t('ai.runtime.entry.compactionViewExecution')
  const retryLabel = t('ai.runtime.entry.compactionRetry')
  const actionsNode = childThreadId != null || showRetry ? (
    <>
      {childThreadId != null ? (
        <ThreadLink
          className="thread-compaction-action"
          threadId={childThreadId}
          afterEntryId={cutEntryId ?? undefined}
          title={viewExecutionLabel}
        >
          {viewExecutionLabel}
        </ThreadLink>
      ) : null}
      {showRetry && actions != null ? (
        <Button
          variant="ghost"
          size="compact"
          className="thread-compaction-action"
          disabled={retryDisabledReason != null}
          title={retryDisabledReason != null ? t(retryDisabledReason) : retryLabel}
          onClick={actions.compact}
        >
          {retryLabel}
        </Button>
      ) : null}
    </>
  ) : null
  return (
    <SystemMessageCard
      className={`thread-compaction state-${state}`}
      data-entry-kind="compaction"
      data-compaction-state={state}
      tone={CARD_TONES[state]}
      label={titleText}
      title={noteKey == null
        ? titleText
        : (
          <>
            <span>{titleText}</span>
            <span className="thread-compaction-note">{t(noteKey)}</span>
          </>
        )}
      actions={actionsNode}
    >
      {state === 'compacted' && message.text.trim() !== ''
        ? <SystemMessageBody content={message.text} />
        : null}
    </SystemMessageCard>
  )
}
