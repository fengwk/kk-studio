import type { TextDialogueMessage } from '@/features/ai/session-events'

export function SystemMessageBlock({ message }: { message: TextDialogueMessage }) {
  return (
    <section className={`session-block session-block-system ${message.status === 'error' ? 'error' : ''}`}>
      <div className="session-block-body">{message.text}</div>
    </section>
  )
}
