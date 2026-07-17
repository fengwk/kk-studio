import { UserRound } from 'lucide-react'
import type { TextDialogueMessage } from '@/features/ai/session-events'

export function UserMessageBlock({ message }: { message: TextDialogueMessage }) {
  return (
    <article className="msg-wrapper user session-msg session-msg-user">
      <div className="msg-avatar">
        <UserRound aria-hidden="true" />
      </div>
      <div className="msg-bubble session-msg-bubble">
        <p className="session-msg-text">{message.text}</p>
      </div>
    </article>
  )
}
