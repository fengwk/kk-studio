import type { RefObject } from 'react'
import { ThreadTranscript } from '@/features/ai/runtime/thread-panel/ThreadTranscript'
import { useChatTranscriptAutoScroll } from '@/features/ai/runtime/useChatTranscriptAutoScroll'
import type {
  DialogueMessage,
  ToolDialogueMessage,
} from '@/features/ai/runtime/thread-timeline-types'

/**
 * Conversation 主视图：实际挂载的滚动容器（与 Event 视图互斥，各自拥有独立的
 * 自动贴底生命周期——切到 Event 时本视图卸载，切回时重新挂载并重新绑定
 * scroll listener / ResizeObserver）。
 *
 * - `initialScrollTop` 非空时挂载即恢复该位置（重新进入 conversation），stick
 *   状态由恢复后的位置按 210px 阈值决定；
 * - `resetKey`（threadId）变化时重置 stick 并重新贴底（Thread 重绑后首次进入）。
 */
export function ThreadConversationView({
  messages,
  loading,
  error,
  bodyRef,
  initialScrollTop = null,
  resetKey = null,
  eventCount = 0,
  onDecideApproval,
  approvalPending = false,
}: {
  messages: DialogueMessage[]
  loading: boolean
  error: unknown
  bodyRef: RefObject<HTMLDivElement | null>
  /** 重新进入 conversation 视图时恢复的 scrollTop；null 表示首次进入（贴底）。 */
  initialScrollTop?: number | null
  /** 变化时重置 stick 并重新贴底（Thread 重绑后新线程首次进入）。 */
  resetKey?: string | number | null
  /** 非消息内容的变化计数（控制 Entry/queued 等），用于贴底再评估。 */
  eventCount?: number
  onDecideApproval?: (
    message: ToolDialogueMessage,
    decision: 'ALLOW' | 'DENY',
  ) => void | Promise<void>
  /** 进行中的全局审批请求：所有未决的审批条都会禁用其按钮。 */
  approvalPending?: boolean
}) {
  useChatTranscriptAutoScroll(bodyRef, messages.length, eventCount, initialScrollTop, resetKey)
  return (
    <ThreadTranscript
      messages={messages}
      loading={loading}
      error={error}
      bodyRef={bodyRef}
      onDecideApproval={onDecideApproval}
      approvalPending={approvalPending}
    />
  )
}
