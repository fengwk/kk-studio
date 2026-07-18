import type { TextDialogueMessage } from '@/features/ai/session-events'

/** Full-width user content block (pi-style transcript line, not a chat bubble). */
export function UserMessageBlock({ message }: { message: TextDialogueMessage }) {
  return (
    <section className="session-block session-block-user">
      <div className="session-block-label">user</div>
      <div className="session-block-body">{message.text}</div>
    </section>
  )
}
