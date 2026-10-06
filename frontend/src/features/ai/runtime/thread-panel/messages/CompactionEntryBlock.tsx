import { ChevronDown, ChevronRight } from 'lucide-react'
import { useState } from 'react'
import { SystemMessageBody } from '@/features/ai/runtime/thread-panel/messages/SystemMessageBody'
import { SystemMessageCard } from '@/features/ai/runtime/thread-panel/messages/SystemMessageCard'
import { announceTranscriptReading } from '@/features/ai/runtime/transcript-reading'
import type { EntryEventDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import { useI18n } from '@/shared/i18n'
import './compaction-entry.css'

/**
 * 完整成功压缩摘要的系统卡片：默认折叠，第一行最右是展开/收起控制。
 *
 * 正文只读取 COMPACTION.payload.summaryText，展开后在唯一有界区域内滚动，
 * 不截断，也不解析 read-files 等标签。
 * 展开状态依赖 MessageList 以摘要 Entry 的稳定 id 作为 React key：刷新投影
 * 不会重建组件实例，因此普通刷新不重置展开状态。
 * 展开和内部回看会通知外层暂停跟随，避免后续正文更新移走正在阅读的卡片。
 */
export function CompactionEntryBlock({ message }: { message: EntryEventDialogueMessage }) {
  const { t } = useI18n()
  const [expanded, setExpanded] = useState(false)
  const toggleLabel = expanded
    ? t('ai.runtime.entry.compactionCollapse')
    : t('ai.runtime.entry.compactionExpand')
  return (
    <SystemMessageCard
      className="thread-compaction"
      data-entry-kind="compaction"
      title={message.title}
      actions={(
        <button
          type="button"
          className="thread-compaction-toggle"
          aria-expanded={expanded}
          aria-label={toggleLabel}
          title={toggleLabel}
          onClick={(event) => {
            announceTranscriptReading(event.currentTarget, 'compaction-toggle')
            setExpanded((current) => !current)
          }}
        >
          {expanded ? <ChevronDown aria-hidden="true" /> : <ChevronRight aria-hidden="true" />}
        </button>
      )}
    >
      {expanded ? (
        <SystemMessageBody
          className="thread-compaction-body"
          content={message.text}
          tabIndex={0}
          aria-label={message.title}
          onScroll={(event) => announceTranscriptReading(event.currentTarget, 'compaction-body')}
        />
      ) : null}
    </SystemMessageCard>
  )
}
