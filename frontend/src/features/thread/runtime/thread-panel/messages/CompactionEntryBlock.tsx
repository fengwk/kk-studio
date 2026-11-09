import { SystemMessageBody } from '@/features/thread/runtime/thread-panel/messages/SystemMessageBody'
import { SystemMessageCard } from '@/features/thread/runtime/thread-panel/messages/SystemMessageCard'
import type { EntryEventDialogueMessage } from '@/features/thread/runtime/thread-timeline-types'

/**
 * 完整成功压缩摘要的系统卡片：默认 0 行，只显示标题；展开后完整显示，无内部滚动。
 *
 * 正文只读取 COMPACTION.payload.summaryText，不截断，也不解析 read-files 等标签。
 * 展开状态依赖 MessageList 以摘要 Entry 的稳定 id 作为 React key：刷新投影
 * 不会重建组件实例，因此普通刷新不重置展开状态。
 * 展开属于阅读意图，统一容器会通知外层暂停跟随，避免后续正文更新移走卡片。
 */
export function CompactionEntryBlock({ message }: { message: EntryEventDialogueMessage }) {
  return (
    <SystemMessageCard
      className="thread-compaction"
      data-entry-kind="compaction"
      title={message.title}
    >
      {message.text.trim() !== ''
        ? <SystemMessageBody content={message.text} />
        : null}
    </SystemMessageCard>
  )
}
