import { Coins } from 'lucide-react'
import type { MetaDialogueMessage } from '@/features/ai/runtime/thread-timeline-types'
import { useI18n } from '@/shared/i18n'

/**
 * 特殊 entry / 回合摘要：左对齐，与正文同列，用图标区分。
 */
export function MetaMessageBlock({ message }: { message: MetaDialogueMessage }) {
  const { t } = useI18n()
  const title = message.kind === 'turn_usage' ? t('ai.runtime.usage.metaTooltip') : undefined
  return (
    <section
      className={`thread-block thread-block-meta kind-${message.kind}`}
      data-meta-kind={message.kind}
    >
      <div className="thread-meta-row">
        <span className="thread-meta-icon" aria-hidden="true">
          <Coins />
        </span>
        <div className="thread-block-body thread-meta-text" title={title}>
          {message.text}
        </div>
      </div>
    </section>
  )
}
