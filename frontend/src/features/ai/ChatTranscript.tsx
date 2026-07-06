import { MessageSquare } from 'lucide-react'
import type { RefObject } from 'react'
import { ChatMessageBubble } from '@/features/ai/ChatMessageBubble'
import type { DialogueMessage } from '@/features/ai/session-events'

export function ChatTranscript({
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
    <div className="chat-body" ref={bodyRef}>
      {loading && <div className="state-block">正在加载会话</div>}
      {hasError && <div className="state-block danger">会话加载失败</div>}
      {!loading && !hasError && visibleMessages.length === 0 && (
        <div className="empty-dialogue">
          <MessageSquare aria-hidden="true" />
          <p>发送消息以开启全新对话。</p>
        </div>
      )}
      {visibleMessages.map((message) => (
        <ChatMessageBubble key={message.id} message={message} />
      ))}
    </div>
  )
}

function isVisibleDialogueMessage(message: DialogueMessage): boolean {
  return message.role !== 'assistant' || message.status === 'error' || message.text.trim().length > 0
}
