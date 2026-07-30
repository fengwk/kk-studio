import type { TextDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'

export function SystemMessageBlock({ message }: { message: TextDialogueMessage }) {
  return (
    <section className={`thread-block thread-block-system ${message.status === 'error' ? 'error' : ''}`}>
      <div className="thread-block-body">{message.text}</div>
    </section>
  )
}
