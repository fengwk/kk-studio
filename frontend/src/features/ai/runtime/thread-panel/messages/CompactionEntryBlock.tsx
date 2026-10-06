import { ChevronDown, ChevronRight } from 'lucide-react'
import { useState } from 'react'
import { SystemMessageBody } from '@/features/ai/runtime/thread-panel/messages/SystemMessageBody'
import { SystemMessageCard } from '@/features/ai/runtime/thread-panel/messages/SystemMessageCard'
import type { EntryEventDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import { useI18n } from '@/shared/i18n'
import './compaction-entry.css'

/**
 * 完整成功压缩摘要的系统卡片：默认折叠，第一行最右是展开/收起控制。
 *
 * <p>正文只读取 COMPACTION.payload.summaryText，展开后在唯一有界区域内滚动，
 * 不做首 N 行截断，也不解析 <code>&lt;read-files&gt;</code> 等标签。
 * 展开状态依赖 MessageList 以摘要 Entry 的稳定 id 作为 React key：刷新投影
 * 不会重建组件实例，因此普通刷新不重置展开状态。
 *
 * <p>接线位置：C 的通用 transcript-reading（{@code announceTranscriptReading(button, 'compaction')}
 * 与内部回看通知外层）由父在集成时接到下方的 toggle 按钮与正文 body。
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
          onClick={() => setExpanded((current) => !current)}
        >
          {expanded ? <ChevronDown aria-hidden="true" /> : <ChevronRight aria-hidden="true" />}
        </button>
      )}
    >
      {expanded ? (
        <SystemMessageBody className="thread-compaction-body" content={message.text} />
      ) : null}
    </SystemMessageCard>
  )
}
