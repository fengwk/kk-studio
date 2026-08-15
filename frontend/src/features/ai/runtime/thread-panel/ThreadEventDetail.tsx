import { X } from 'lucide-react'
import type { ThreadEventRecord } from '@/features/ai/runtime/thread-events'
import { useI18n } from '@/shared/i18n'

/**
 * 只读 Event detail widget：展示在 ThreadWidgetStack 的 Composer 上方（TaskStatus
 * 之前）。不是 InteractionPanel：不隐藏 Composer、不抢焦点、无 backdrop、无
 * auto focus、无 Copy；展示结构化 details 与原始 payload JSON（pre 内滚动）。
 */
export function ThreadEventDetail({
  record,
  onClose,
}: {
  record: ThreadEventRecord
  onClose: () => void
}) {
  const { t } = useI18n()
  return (
    <section
      className="thread-event-detail"
      aria-label={t('ai.runtime.event.detailTitle')}
    >
      <header className="thread-event-detail-header">
        <h3>{record.title}</h3>
        <button
          type="button"
          className="thread-interaction-close"
          aria-label={t('ai.runtime.event.closeDetail')}
          onClick={onClose}
        >
          <X aria-hidden="true" />
        </button>
      </header>
      <p className="thread-event-detail-text">{record.summary}</p>
      {record.details.length > 0 ? (
        <dl className="thread-event-detail-rows">
          {record.details.map((row) => (
            <div key={row.label} className="thread-event-detail-row">
              <dt>{row.label}</dt>
              <dd>{row.value}</dd>
            </div>
          ))}
        </dl>
      ) : null}
      {record.rawJson != null ? (
        <pre className="thread-event-detail-payload">{record.rawJson}</pre>
      ) : null}
    </section>
  )
}
