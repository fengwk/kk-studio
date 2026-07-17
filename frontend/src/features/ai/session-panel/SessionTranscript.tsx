import { MessageSquare } from 'lucide-react'
import type { RefObject } from 'react'
import { MessageList } from '@/features/ai/session-panel/messages/MessageList'
import { isVisibleDialogueMessage } from '@/features/ai/session-panel/visibility'
import type { DialogueMessage } from '@/features/ai/session-events'

export function SessionTranscript({
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

  return (
    <div className="chat-body session-transcript" ref={bodyRef} role="log" aria-label="会话消息">
      {loading && <div className="state-block">正在加载会话</div>}
      {hasError && <div className="state-block danger">会话加载失败</div>}
      {!loading && !hasError && visibleMessages.length === 0 && (
        <div className="empty-dialogue">
          <MessageSquare aria-hidden="true" />
          <p>发送消息以开启全新对话。</p>
        </div>
      )}
      <MessageList messages={visibleMessages} />
    </div>
  )
}
