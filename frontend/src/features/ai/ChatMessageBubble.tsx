import { Bot, ChevronDown, UserRound, Wrench } from 'lucide-react'
import { ChatToolMessage } from '@/features/ai/ChatToolMessage'
import type { DialogueMessage, TextDialogueMessage, ToolDialogueMessage } from '@/features/ai/session-events'

export function ChatMessageBubble({ message }: { message: DialogueMessage }) {
  const wrapperClass =
    message.role === 'user' ? 'user' : message.role === 'tool' ? 'tool' : 'bot'

  const assistantText = isTextMessage(message) ? message : null
  const showThinking =
    Boolean(assistantText) && Boolean(assistantText && assistantText.thinking)

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
          <>
            {showThinking && assistantText && (
              <details
                className="msg-thinking"
                open={message.status === 'streaming'}
              >
                <summary>
                  <ChevronDown aria-hidden="true" />
                  <span>{message.status === 'streaming' ? '正在思考' : '思考过程'}</span>
                </summary>
                <pre className="msg-thinking-body">{assistantText.thinking}</pre>
              </details>
            )}
            <p>{message.text || (message.status === 'streaming' ? '...' : '')}</p>
          </>
        )}
      </div>
    </div>
  )
}

function isToolMessage(message: DialogueMessage): message is ToolDialogueMessage {
  return message.role === 'tool'
}

function isTextMessage(message: DialogueMessage): message is TextDialogueMessage {
  return message.role === 'user' || message.role === 'assistant' || message.role === 'system'
}
