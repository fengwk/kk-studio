import { useI18n } from '@/shared/i18n'
import { SystemMessageBody } from '@/features/thread/runtime/thread-panel/messages/SystemMessageBody'
import { SystemMessageCard } from '@/features/thread/runtime/thread-panel/messages/SystemMessageCard'
import { ThreadInspectionButton } from '@/features/thread/runtime/panes/ThreadInspectionButton'
import type {
  CompactionCardFact,
  CompactionCardState,
  EntryEventDialogueMessage,
} from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
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
 * 一句结果说明。带分支 Thread id 的卡片提供“查看执行”。
 */
export function CompactionEntryBlock({
  message,
  fact,
}: {
  message: EntryEventDialogueMessage
  fact: CompactionCardFact
}) {
  const { t } = useI18n()
  const state: CompactionCardState = fact.state
  const titleText = t(compactionCardTitleKey(state))
  const noteKey = state === 'failed'
    ? compactionFailureReasonKey(fact.errorCode)
    : state === 'stopped'
      ? 'ai.runtime.entry.compactionStoppedHint'
      : state === 'running' ? 'ai.runtime.entry.compactionRunningHint' : null
  const childThreadId = fact.childThreadId
  const cutEntryId = fact.cutEntryId
  const viewExecutionLabel = t('ai.runtime.entry.compactionViewExecution')
  const actionsNode = childThreadId == null ? null : (
    <ThreadInspectionButton
      threadId={childThreadId}
      afterEntryId={cutEntryId ?? undefined}
      title={viewExecutionLabel}
    >
      {viewExecutionLabel}
    </ThreadInspectionButton>
  )
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
