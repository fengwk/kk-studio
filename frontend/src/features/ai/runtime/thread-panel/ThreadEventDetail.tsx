import { useEffect, useRef, type KeyboardEvent, type Ref, type RefObject } from 'react'
import { Eye, LoaderCircle, X } from 'lucide-react'
import type { ThreadEventRecord } from '@/features/ai/runtime/thread-events'
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { IconButton } from '@/shared/ui/controls/IconButton'

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
  // 历史入口以模型输出来源（assistantMetadata）判定，与后端准入一致：普通与含 tool_call 的
  // ASSISTANT、带 metadata 的压缩输出同样可读取；错误/中止/失败重试不可，但绝不因此断言从未调用。
  const canPreviewHistoricalRequest =
    record.historicalPreviewEligible === true &&
    entryId != null &&
    onRequestHistoricalPreview != null
  const historicalPreviewUnsupported =
    !canPreviewHistoricalRequest &&
    record.entryId != null &&
    (record.kind === 'ASSISTANT_ERROR' ||
      record.kind === 'ASSISTANT_ABORTED' ||
      record.kind === 'MODEL_ATTEMPT_FAILURE')

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
        <IconButton
          ref={resolvedCloseBtnRef}
          size="compact"
          label={t('ai.runtime.event.closeDetail')}
          onClick={onClose}
        >
          <X aria-hidden="true" />
        </IconButton>
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
          <Button
            variant="inline"
            data-testid="historical-request-preview"
            title={t('ai.runtime.debug.historicalRequestHint')}
            aria-label={t('ai.runtime.debug.historicalRequestHint')}
            disabled={historicalPreviewLoading}
            onClick={() => onRequestHistoricalPreview(entryId)}
          >
            {historicalPreviewLoading ? (
              <LoaderCircle size={12} className="spin" aria-hidden="true" />
            ) : (
              <Eye size={12} aria-hidden="true" />
            )}
            <span>{t('ai.runtime.debug.historicalRequest')}</span>
          </Button>
        </div>
      ) : null}
      {historicalPreviewUnsupported ? (
        <div className="thread-event-detail-actions">
          <span className="thread-debug-empty-text">
            {t('ai.runtime.debug.historicalUnavailable')}
          </span>
        </div>
      ) : null}
      <pre className="thread-event-detail-payload" tabIndex={0}>{record.rawJson ?? record.summary}</pre>
    </section>
  )
}
