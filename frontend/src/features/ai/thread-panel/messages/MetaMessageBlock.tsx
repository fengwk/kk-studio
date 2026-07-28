import { Coins } from 'lucide-react'
import type { MetaDialogueMessage } from '@/features/ai/thread-timeline-types'

/**
 * 特殊 entry / 回合摘要：左对齐，与正文同列，用图标区分。
 */
export function MetaMessageBlock({ message }: { message: MetaDialogueMessage }) {
  return (
    <section
      className={`thread-block thread-block-meta kind-${message.kind}`}
      data-meta-kind={message.kind}
    >
      <div className="thread-meta-row">
        <span className="thread-meta-icon" aria-hidden="true">
          <Coins />
        </span>
        <div className="thread-block-body thread-meta-text">{message.text}</div>
      </div>
    </section>
  )
}
