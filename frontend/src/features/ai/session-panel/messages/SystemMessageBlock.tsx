import { Bot } from 'lucide-react'
import type { TextDialogueMessage } from '@/features/ai/session-events'

export function SystemMessageBlock({ message }: { message: TextDialogueMessage }) {
  return (
    <article className="msg-wrapper bot session-msg session-msg-system">
      <div className="msg-avatar">
        <Bot aria-hidden="true" />
      </div>
      <div className={`msg-bubble session-msg-bubble ${message.status === 'error' ? 'error' : ''}`}>
        <p className="session-msg-text">{message.text}</p>
      </div>
    </article>
  )
}
