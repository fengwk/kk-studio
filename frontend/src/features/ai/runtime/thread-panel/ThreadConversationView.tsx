import type { RefObject } from 'react'
import { ThreadTranscript } from '@/features/ai/runtime/thread-panel/ThreadTranscript'
import { useChatTranscriptAutoScroll } from '@/features/ai/runtime/useChatTranscriptAutoScroll'
import type { DialogueMessage } from '@/features/ai/runtime/thread-timeline-types'

/**
 * Conversation 主视图：实际挂载的滚动容器（与 Event 视图互斥，各自拥有独立的
 * 自动贴底生命周期——切到 Event 时本视图卸载，切回时重新挂载并重新绑定
 * scroll listener / ResizeObserver）。
 *
 * - `initialScrollTop` 非空时挂载即恢复该位置（重新进入 conversation），stick
 *   状态由恢复后的位置按 210px 阈值决定；
 * - `resetKey`（threadId）变化时重置 stick 并重新贴底（Thread 重绑后首次进入）；
 * - 流式结束（全部消息进入 done/error）后不再跟随高度变化，避免手动展开或媒体
 *   加载把外层滚动位置拉走。
 */
export function ThreadConversationView({
  messages,
  loading,
  error,
  bodyRef,
  initialScrollTop = null,
  resetKey = null,
  eventCount = 0,
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
}) {
  // 仍在流式增长的唯一权威信号来自消息状态：只有它才允许用高度变化补贴底，
  // 空闲时的高度变化（展开卡片、图片加载）必须保持外层滚动位置不动。
  const streaming = messages.some((message) => message.status === 'streaming')
  useChatTranscriptAutoScroll(
    bodyRef,
    messages.length,
    eventCount,
    initialScrollTop,
    resetKey,
    streaming,
  )
  return (
    <ThreadTranscript
      messages={messages}
      loading={loading}
      error={error}
      bodyRef={bodyRef}
    />
  )
}
