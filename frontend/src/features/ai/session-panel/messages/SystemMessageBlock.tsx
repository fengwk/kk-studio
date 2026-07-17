import type { TextDialogueMessage } from '@/features/ai/session-events'

export function SystemMessageBlock({ message }: { message: TextDialogueMessage }) {
  return (
    <article className="session-row system">
      <div className={`session-bubble system ${message.status === 'error' ? 'error' : ''}`}>
        {message.text}
      </div>
    </article>
  )
}
