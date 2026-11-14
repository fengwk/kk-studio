import { AlertCircle } from 'lucide-react'
import { ThreadModelRequestDebug } from '@/features/thread/runtime/thread-panel/ThreadModelRequestDebug'
import type { DebugInspectorSelection } from '@/features/thread/runtime/thread-panel/ThreadDebugInspector'
import type { ThreadModelRequestDebugData } from '@/features/thread/runtime/thread-timeline/thread-timeline-types'
import { useI18n } from '@/shared/i18n'

/**
 * Debug 预览列：本次调用的请求投影（system prompt、tools、skills、cache）。
 *
 * loading/error 是独立的读取状态，不被吞成「暂无请求预览数据」空态；
 * 选中 chip 时把选择与触发元素一起上报，由调用方决定详情列与焦点返回位置。
 */
export function ThreadDebugPreviewColumn({
  tabId,
  tabPanelId,
  wide,
  debug,
  loading,
  error,
  onSelectInspector,
}: {
  tabId: string
  tabPanelId: string
  wide: boolean
  debug?: ThreadModelRequestDebugData | null
  loading: boolean
  error: string | null
  onSelectInspector: (selection: DebugInspectorSelection | null, trigger: HTMLElement | null) => void
}) {
  const { t } = useI18n()
  return (
    <section
      id={tabPanelId}
      role={wide ? undefined : 'tabpanel'}
      aria-labelledby={wide ? undefined : tabId}
      aria-label={wide ? t('ai.runtime.debug.tabPreview') : undefined}
      className="thread-debug-col thread-debug-col-preview"
    >
      {debug ? (
        <ThreadModelRequestDebug
          debug={debug}
          onSelectInspector={(selection) => {
            const activeEl = document.activeElement as HTMLElement | null
            onSelectInspector(selection, activeEl)
          }}
        />
      ) : loading ? (
        <div className="thread-debug-placeholder" data-testid="thread-debug-preview-loading">
          <p>{t('ai.runtime.debug.previewLoading')}</p>
        </div>
      ) : error ? (
        <div
          role="alert"
          className="thread-debug-placeholder thread-debug-preview-load-error"
          data-testid="thread-debug-preview-error"
        >
          <AlertCircle size={14} aria-hidden="true" />
          <p>{error}</p>
        </div>
      ) : (
        <div className="thread-debug-placeholder">
          <p>{t('ai.runtime.debug.noPreview')}</p>
        </div>
      )}
    </section>
  )
}
