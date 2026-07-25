import { MessageSquare } from 'lucide-react'
import type { RefObject } from 'react'
import { MessageList } from '@/features/ai/thread-panel/messages/MessageList'
import { isVisibleDialogueMessage } from '@/features/ai/thread-panel/visibility'
import type { DialogueMessage } from '@/features/ai/thread-timeline'

/** Dialogue zone: full-height scroll, block transcript. */
export function ThreadTranscript({
  messages,
  loading,
  error,
  bodyRef,
}: {
  messages: DialogueMessage[]
  loading: boolean
  error: unknown
  bodyRef: RefObject<HTMLDivElement | null>
}) {
  const hasError = Boolean(error)
  const visibleMessages = messages.filter(isVisibleDialogueMessage)
  const empty = !loading && !hasError && visibleMessages.length === 0

  return (
    <div className="thread-dialogue" ref={bodyRef} role="log" aria-label="会话消息" aria-busy={loading}>
      {loading && <div className="thread-state">正在加载会话…</div>}
      {hasError && <div className="thread-state danger">会话加载失败</div>}
      {empty && (
        <div className="thread-empty">
          <MessageSquare aria-hidden="true" />
          <p>发送消息，开始对话</p>
          <small>Enter 发送 · Shift+Enter 换行</small>
        </div>
      )}
      <div className="thread-blocks">
        <MessageList messages={visibleMessages} />
      </div>
    </div>
  )
}
