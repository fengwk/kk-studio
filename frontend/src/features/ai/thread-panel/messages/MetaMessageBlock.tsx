import { Bot, Coins, Cpu, Info } from 'lucide-react'
import type { MetaDialogueMessage, MetaMessageKind } from '@/features/ai/thread-events'

/**
 * 特殊 entry / 回合摘要：左对齐，与正文同列，用图标区分。
 */
export function MetaMessageBlock({ message }: { message: MetaDialogueMessage }) {
  const Icon = iconForKind(message.kind)
  return (
    <section
      className={`thread-block thread-block-meta kind-${message.kind}`}
      data-meta-kind={message.kind}
    >
      <div className="thread-meta-row">
        <span className="thread-meta-icon" aria-hidden="true">
          <Icon />
        </span>
        <div className="thread-block-body thread-meta-text">{message.text}</div>
      </div>
    </section>
  )
}

function iconForKind(kind: MetaMessageKind) {
  switch (kind) {
    case 'agent_change':
      return Bot
    case 'model_change':
      return Cpu
    case 'turn_usage':
      return Coins
    default:
      return Info
  }
}
