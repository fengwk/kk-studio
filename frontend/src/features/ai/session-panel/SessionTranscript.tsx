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
  pending,
  activeRun,
}: {
  messages: DialogueMessage[]
  loading: boolean
  error: unknown
  bodyRef: RefObject<HTMLDivElement | null>
  pending?: boolean
  activeRun?: boolean
}) {
  const hasError = Boolean(error)
  const visibleMessages = messages.filter(isVisibleDialogueMessage)
  const empty = !loading && !hasError && visibleMessages.length === 0

  return (
    <div className="session-transcript" ref={bodyRef} role="log" aria-label="会话消息" aria-busy={loading || pending}>
      {loading && (
        <div className="session-state">
          <span className="session-pulse" />
          正在加载会话…
        </div>
      )}
      {hasError && <div className="session-state danger">会话加载失败</div>}
      {empty && (
        <div className="session-empty">
          <MessageSquare aria-hidden="true" />
          <p>发送消息，开始对话</p>
          <small>Enter 发送 · Shift+Enter 换行</small>
        </div>
      )}
      <div className="session-messages">
        <MessageList messages={visibleMessages} />
        {pending && !activeRun ? (
          <div className="session-row pending" aria-live="polite">
            <div className="session-bubble pending">
              <span className="session-pulse" />
              消息发送中…
            </div>
          </div>
        ) : null}
      </div>
    </div>
  )
}
