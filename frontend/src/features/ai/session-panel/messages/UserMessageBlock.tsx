import type { TextDialogueMessage } from '@/features/ai/session-events'

/** Canvas-thread style user bubble (right-aligned, green). */
export function UserMessageBlock({ message }: { message: TextDialogueMessage }) {
  return (
    <article className="session-row user">
      <div className="session-bubble user">{message.text}</div>
    </article>
  )
}
