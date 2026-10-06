import { useEffect, useRef, type KeyboardEvent, type Ref, type RefObject } from 'react'
import { Eye, LoaderCircle, X } from 'lucide-react'
import type { ThreadEventRecord } from '@/features/ai/runtime/thread-events'
import { useI18n } from '@/shared/i18n'

/**
 * 只读 Event 详情视图：位于 Debug 视图详情列。
 * 展示选中事件的结构化详情字段与原始 payload JSON，由详情列单列整体纵向滚动。
 *
 * 选中的是历史 ASSISTANT 模型调用 Entry，或携带真实模型输出 metadata 的 COMPACTION 结果时，
 * 额外提供按需读取「该次调用之前的请求前缀」的入口：它只是一次只读 GET 预览，不会发生任何写入或重放。
 */
export function ThreadEventDetail({
  record,
  onClose,
  closeButtonRef,
  autoFocusCloseButton = true,
  onRequestHistoricalPreview,
  historicalPreviewLoading = false,
}: {
  record: ThreadEventRecord
  onClose: () => void
  closeButtonRef?: Ref<HTMLButtonElement>
  autoFocusCloseButton?: boolean
  onRequestHistoricalPreview?: (entryId: string) => void
  historicalPreviewLoading?: boolean
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

  const entryId = record.entryId
  const canPreviewHistoricalRequest =
    (record.kind === 'ASSISTANT_MESSAGE' || record.historicalPreviewEligible === true) &&
    entryId != null &&
    onRequestHistoricalPreview != null

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
      {canPreviewHistoricalRequest ? (
        <div className="thread-event-detail-actions">
          <button
            type="button"
            className="ghost-inline-btn"
            data-testid="historical-request-preview"
            disabled={historicalPreviewLoading}
            onClick={() => onRequestHistoricalPreview(entryId)}
          >
            {historicalPreviewLoading ? (
              <LoaderCircle size={12} className="spin" aria-hidden="true" />
            ) : (
              <Eye size={12} aria-hidden="true" />
            )}
            <span>{t('ai.runtime.debug.historicalRequest')}</span>
          </button>
        </div>
      ) : null}
      <pre className="thread-event-detail-payload">{record.rawJson ?? record.summary}</pre>
    </section>
  )
}
