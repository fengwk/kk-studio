import type { TextDialogueMessage } from '@/features/ai/session-events'

/** Full-width user content block (role conveyed by surface style, no title). */
export function UserMessageBlock({ message }: { message: TextDialogueMessage }) {
  return (
    <section className="session-block session-block-user">
      <div className="session-block-body">{message.text}</div>
    </section>
  )
}
