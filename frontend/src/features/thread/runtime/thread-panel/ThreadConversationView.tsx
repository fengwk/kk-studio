import type { RefObject } from 'react'
import { ThreadTranscript } from '@/features/thread/runtime/thread-panel/ThreadTranscript'
import { useChatTranscriptAutoScroll } from '@/features/thread/runtime/panes/useChatTranscriptAutoScroll'
import type { DialogueMessage } from '@/features/thread/runtime/thread-timeline/thread-timeline-types'

/**
 * Conversation 主视图：实际挂载的滚动容器（与 Event 视图互斥，各自拥有独立的
 * 自动贴底生命周期——切到 Event 时本视图卸载，切回时重新挂载并重新绑定
 * 滚动与手势监听）。
 *
 * 跟随状态与定位都在 `useChatTranscriptAutoScroll` 里：只有几何变化会触发贴底，
 * 只有真实用户滚动会翻转跟随。
 *
 * - `initialScrollTop` 非空时挂载即恢复该位置（重新进入 conversation），跟随
 *   状态由恢复后的位置按 32px 阈值决定；
 * - `resetKey`（threadId）变化时重置跟随并重新贴底（Thread 重绑后首次进入）。
 */
export function ThreadConversationView({
  messages,
  loading,
  error,
  errorText,
  onRetry,
  emptyText,
  bodyRef,
  initialScrollTop = null,
  resetKey = null,
}: {
  messages: DialogueMessage[]
  loading: boolean
  error: unknown
  errorText?: string
  onRetry?: () => void
  emptyText?: string
  bodyRef: RefObject<HTMLDivElement | null>
  /** 重新进入 conversation 视图时恢复的 scrollTop；null 表示首次进入（贴底）。 */
  initialScrollTop?: number | null
  /** 变化时重置跟随并重新贴底（Thread 重绑后新线程首次进入）。 */
  resetKey?: string | number | null
}) {
  useChatTranscriptAutoScroll(bodyRef, initialScrollTop, resetKey)
  return (
    <ThreadTranscript
      messages={messages}
      loading={loading}
      error={error}
      errorText={errorText}
      onRetry={onRetry}
      emptyText={emptyText}
      bodyRef={bodyRef}
    />
  )
}
