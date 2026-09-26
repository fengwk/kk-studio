import { useEffect, useRef, type KeyboardEvent, type Ref, type RefObject } from 'react'
import { X } from 'lucide-react'
import type { ThreadEventRecord } from '@/features/ai/runtime/thread-events'
import { useI18n } from '@/shared/i18n'

/**
 * 只读 Event 详情视图：位于 Debug 视图详情列。
 * 展示选中事件的结构化详情字段与原始 payload JSON，由详情列单列整体纵向滚动。
 */
export function ThreadEventDetail({
  record,
  onClose,
  closeButtonRef,
  autoFocusCloseButton = true,
}: {
  record: ThreadEventRecord
  onClose: () => void
  closeButtonRef?: Ref<HTMLButtonElement>
  autoFocusCloseButton?: boolean
}) {
  const { t } = useI18n()
  const containerRef = useRef<HTMLElement>(null)
  const internalCloseBtnRef = useRef<HTMLButtonElement>(null)
  const resolvedCloseBtnRef = (closeButtonRef as RefObject<HTMLButtonElement | null>) ?? internalCloseBtnRef

  useEffect(() => {
    if (autoFocusCloseButton) {
      const btn = resolvedCloseBtnRef.current
      if (btn) {
        btn.focus({ preventScroll: true })
      } else {
        containerRef.current?.focus({ preventScroll: true })
      }
    }
  }, [autoFocusCloseButton, resolvedCloseBtnRef])

  function handleKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.nativeEvent.isComposing || event.keyCode === 229) {
      return
    }
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      onClose()
    }
  }

  return (
    <section
      ref={containerRef}
      tabIndex={-1}
      className="thread-event-detail"
      aria-label={t('ai.runtime.event.detailTitle')}
      onKeyDown={handleKeyDown}
    >
      <header className="thread-event-detail-header">
        <h3>{record.title}</h3>
        <button
          ref={resolvedCloseBtnRef}
          type="button"
          className="thread-interaction-close"
          aria-label={t('ai.runtime.event.closeDetail')}
          onClick={onClose}
        >
          <X aria-hidden="true" />
        </button>
      </header>
      {record.details.length > 0 ? (
        <dl className="thread-event-detail-rows">
          {record.details.map((row, index) => (
            <div key={`${row.label}-${index}`} className="thread-event-detail-row">
              <dt>{row.label}</dt>
              <dd>{row.value}</dd>
            </div>
          ))}
        </dl>
      ) : null}
      <pre className="thread-event-detail-payload">{record.rawJson ?? record.summary}</pre>
    </section>
  )
}
