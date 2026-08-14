import { X } from 'lucide-react'
import type { ThreadEventItem } from '@/features/ai/runtime/thread-events'
import { useI18n } from '@/shared/i18n'

/**
 * 只读 Event detail widget：展示在 ThreadWidgetStack 的 Composer 上方。
 * 不是 InteractionPanel：不抢占焦点、不隐藏 Composer；先不加 Copy Raw。
 */
export function ThreadEventDetail({
  item,
  onClose,
}: {
  item: ThreadEventItem
  onClose: () => void
}) {
  const { t } = useI18n()
  return (
    <section
      className="thread-event-detail"
      aria-label={t('ai.runtime.event.detailTitle')}
    >
      <header className="thread-event-detail-header">
        <h3>{item.title}</h3>
        <button
          type="button"
          className="thread-interaction-close"
          aria-label={t('ai.runtime.event.closeDetail')}
          onClick={onClose}
        >
          <X aria-hidden="true" />
        </button>
      </header>
      <p className="thread-event-detail-text">{item.text}</p>
      {item.details.length > 0 ? (
        <dl className="thread-event-detail-rows">
          {item.details.map((row) => (
            <div key={row.label} className="thread-event-detail-row">
              <dt>{row.label}</dt>
              <dd>{row.value}</dd>
            </div>
          ))}
        </dl>
      ) : null}
      {item.payloadJson != null ? (
        <pre className="thread-event-detail-payload">{item.payloadJson}</pre>
      ) : null}
    </section>
  )
}
