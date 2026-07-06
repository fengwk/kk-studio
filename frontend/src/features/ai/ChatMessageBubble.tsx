import { Bot, UserRound, Wrench } from 'lucide-react'
import { ChatToolMessage } from '@/features/ai/ChatToolMessage'
import type { DialogueMessage, ToolDialogueMessage } from '@/features/ai/session-events'

export function ChatMessageBubble({ message }: { message: DialogueMessage }) {
  const wrapperClass =
    message.role === 'user' ? 'user' : message.role === 'tool' ? 'tool' : 'bot'

  return (
    <div className={`msg-wrapper ${wrapperClass}`}>
      <div className="msg-avatar">
        {message.role === 'user' ? (
          <UserRound aria-hidden="true" />
        ) : message.role === 'tool' ? (
          <Wrench aria-hidden="true" />
        ) : (
          <Bot aria-hidden="true" />
        )}
      </div>
      <div
        className={`msg-bubble ${message.role === 'tool' ? 'tool' : ''} ${message.status === 'error' ? 'error' : ''}`}
      >
        {isToolMessage(message) ? (
          <ChatToolMessage message={message} />
        ) : (
          <p>{message.text || (message.status === 'streaming' ? '...' : '')}</p>
        )}
      </div>
    </div>
  )
}

function isToolMessage(message: DialogueMessage): message is ToolDialogueMessage {
  return message.role === 'tool'
}
