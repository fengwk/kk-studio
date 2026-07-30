import type { TextDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'

/** Full-width user content block (role conveyed by surface style, no title). */
export function UserMessageBlock({ message }: { message: TextDialogueMessage }) {
  return (
    <section className="thread-block thread-block-user">
      <div className="thread-block-body">{message.text}</div>
    </section>
  )
}
