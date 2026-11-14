import { AlertCircle } from 'lucide-react'
import {
  ThreadDebugInspector,
  type DebugInspectorSelection,
} from '@/features/thread/runtime/thread-panel/ThreadDebugInspector'
import { ThreadEventDetail } from '@/features/thread/runtime/thread-panel/ThreadEventDetail'
import type { ThreadEventRecord } from '@/features/thread/runtime/thread-events'
import type {
  ThreadModelRequestDebugData,
  ThreadProviderRequestPreviewData,
} from '@/features/thread/runtime/thread-timeline-types'
import { useI18n } from '@/shared/i18n'

/**
 * Debug 详情列：按优先级展示历史请求预览、inspector 选择或选中事件详情。
 *
 * 三种来源互斥且顺序固定：历史预览（只读、由事件列表按需读取）优先于 inspector
 * 选择，inspector 选择优先于事件详情；都没有时给出「未选择」占位。
 */
export function ThreadDebugDetailColumn({
  tabId,
  tabPanelId,
  wide,
  debug,
  selection,
  selectedRecord,
  historicalPreview,
  historicalPreviewError,
  historicalPreviewLoading,
  closeButtonRef,
  onClose,
  onDismissHistoricalPreview,
  onRequestHistoricalPreview,
}: {
  tabId: string
  tabPanelId: string
  wide: boolean
  debug?: ThreadModelRequestDebugData | null
  selection: DebugInspectorSelection | null
  selectedRecord: ThreadEventRecord | null
  historicalPreview: ThreadProviderRequestPreviewData | null
  historicalPreviewError: string | null
  historicalPreviewLoading: boolean
  closeButtonRef: React.Ref<HTMLButtonElement>
  onClose: () => void
  onDismissHistoricalPreview?: () => void
  onRequestHistoricalPreview?: (entryId: string) => void
}) {
  const { t } = useI18n()
  return (
    <section
      id={tabPanelId}
      role={wide ? undefined : 'tabpanel'}
      aria-labelledby={wide ? undefined : tabId}
      aria-label={wide ? t('ai.runtime.debug.tabDetail') : undefined}
      tabIndex={-1}
      className="thread-debug-col thread-debug-col-detail"
    >
      {historicalPreview != null ? (
        <ThreadDebugInspector
          selection={{ type: 'preview', preview: historicalPreview }}
          debug={debug}
          onClose={() => onDismissHistoricalPreview?.()}
          closeButtonRef={closeButtonRef}
          autoFocusCloseButton={!wide}
        />
      ) : selection && (debug || selection.type === 'preview') ? (
        <ThreadDebugInspector
          selection={selection}
          debug={debug}
          onClose={onClose}
          closeButtonRef={closeButtonRef}
          autoFocusCloseButton={!wide}
        />
      ) : selectedRecord ? (
        <>
          {historicalPreviewError ? (
            <div role="alert" className="thread-debug-planning-error thread-debug-preview-error">
              <AlertCircle size={14} aria-hidden="true" />
              <span>{historicalPreviewError}</span>
            </div>
          ) : null}
          <ThreadEventDetail
            record={selectedRecord}
            onClose={onClose}
            closeButtonRef={closeButtonRef}
            autoFocusCloseButton={!wide}
            onRequestHistoricalPreview={onRequestHistoricalPreview}
            historicalPreviewLoading={historicalPreviewLoading}
          />
        </>
      ) : (
        <div className="thread-debug-placeholder" data-testid="thread-debug-placeholder">
          <p>{t('ai.runtime.debug.noSelection')}</p>
        </div>
      )}
    </section>
  )
}
