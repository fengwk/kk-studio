import { useEffect, useId, useMemo, useRef, type KeyboardEvent } from 'react'
import { shouldDeferToBlockingModal } from '@/shared/ui/blocking-overlay'
import { ThreadEventList } from '@/features/thread/runtime/thread-panel/ThreadEventList'
import { ThreadDebugTabs } from '@/features/thread/runtime/thread-panel/ThreadDebugTabs'
import { ThreadDebugPreviewColumn } from '@/features/thread/runtime/thread-panel/ThreadDebugPreviewColumn'
import { ThreadDebugDetailColumn } from '@/features/thread/runtime/thread-panel/ThreadDebugDetailColumn'
import {
  DEBUG_TAB_LABEL_KEYS,
  type ThreadEventViewProps,
} from '@/features/thread/runtime/thread-panel/thread-debug-view'
import { useDebugLayoutMode } from '@/features/thread/runtime/thread-panel/useDebugLayoutMode'
import { useDebugViewNavigation } from '@/features/thread/runtime/thread-panel/useDebugViewNavigation'
import { useEventListNavigation } from '@/features/thread/runtime/thread-panel/useEventListNavigation'
import { useChatTranscriptAutoScroll } from '@/features/thread/runtime/panes/useChatTranscriptAutoScroll'
import { useI18n } from '@/shared/i18n'
import './thread-conversation.css'

export type { DebugViewTab, ThreadEventViewProps } from '@/features/thread/runtime/thread-panel/thread-debug-view'

/**
 * Debug 主视图：支持 3 区响应式布局。
 *
 * - 宽面板 (container >= 1100px)：规划与历史有界、详情占余宽，各列唯一纵向滚动；
 * - 窄面板 (container < 1100px)：单区填满，通过带有 roving tabIndex 的 Tab 导航（请求预览 / 事件 / 详情）切换；
 * - 点击事件 / 工具 / skill / request 自动切换到详情；关闭详情安全返回来源页签并恢复可见焦点；
 * - 所有 ID 均基于 useId 作用域，防止在多 pane split 时冲突。
 *
 * 本组件只做编排：布局判定、详情来源与页签、三列各自的渲染都在同目录的
 * 协作者里；列表的键盘与滚动跟随同样下沉到 hook。
 */
export function ThreadEventView({
  events,
  historyLoading = false,
  historyError = null,
  onRetryHistory,
  selectedEventId,
  onSelectedEventIdChange,
  initialScrollTop = null,
  bodyRef: bodyRefProp,
  debug,
  debugLoading = false,
  debugError = null,
  debugSelection = null,
  onSelectInspector,
  historicalPreview = null,
  historicalPreviewLoading = false,
  historicalPreviewError = null,
  onRequestHistoricalPreview,
  onDismissHistoricalPreview,
}: ThreadEventViewProps) {
  const { t } = useI18n()
  const baseId = useId()
  const containerRef = useRef<HTMLDivElement>(null)
  const internalBodyRef = useRef<HTMLDivElement>(null)
  const bodyRef = bodyRefProp ?? internalBodyRef

  const layoutMode = useDebugLayoutMode(containerRef)
  const isWide = layoutMode === 'wide'

  const selectedIndex = useMemo(
    () => events.findIndex((event) => event.id === selectedEventId),
    [events, selectedEventId],
  )
  const selectedRecord = useMemo(
    () => (selectedEventId == null
      ? null
      : events.find((event) => event.id === selectedEventId) ?? null),
    [events, selectedEventId],
  )

  const {
    activeTab,
    setActiveTab,
    detailCloseBtnRef,
    handleCloseDetail,
    handleSelectEvent,
    handleSelectInspector,
  } = useDebugViewNavigation({
    selectedEventId,
    debugSelection,
    onSelectedEventIdChange,
    onSelectInspector,
    onDismissHistoricalPreview,
    layoutMode,
    bodyRef,
  })

  const { handleListboxKeyDown } = useEventListNavigation({
    events,
    selectedEventId,
    selectedIndex,
    bodyRef,
    onSelectedEventIdChange,
    onCloseDetail: handleCloseDetail,
    hasHistoricalPreview: historicalPreview != null,
  })

  useChatTranscriptAutoScroll(bodyRef, events.length, events.length, initialScrollTop)

  // 仅在初始挂载时赋予事件列表初始焦点（避免在 wide 模式下抢走 Inspector 或 Tab 的焦点）
  useEffect(() => {
    bodyRef.current?.focus({ preventScroll: true })
  }, [bodyRef])

  // Pane 容器级局部 Escape 拦截，防止逃逸污染其他 Pane 或底层窗口
  function handleContainerKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.defaultPrevented || event.nativeEvent.isComposing || event.keyCode === 229
      || shouldDeferToBlockingModal(event.currentTarget)) {
      return
    }
    if (event.key === 'Escape') {
      if (historicalPreview != null) {
        event.preventDefault()
        event.stopPropagation()
        onDismissHistoricalPreview?.()
      } else if (selectedEventId != null || debugSelection != null) {
        event.preventDefault()
        event.stopPropagation()
        handleCloseDetail()
      }
    }
  }

  return (
    <div
      ref={containerRef}
      className="thread-events-shell"
      data-layout={layoutMode}
      data-active-tab={activeTab}
      onKeyDown={handleContainerKeyDown}
    >
      {/* 窄面板下的 WAI-ARIA Tab 导航；宽面板不渲染 tablist */}
      {isWide ? null : (
        <ThreadDebugTabs
          baseId={baseId}
          activeTab={activeTab}
          onSelect={setActiveTab}
        />
      )}

      {/* 3 区内容网格 */}
      <div className="thread-debug-grid" data-active-tab={activeTab}>
        <ThreadDebugPreviewColumn
          tabId={`${baseId}-tab-preview`}
          tabPanelId={`${baseId}-panel-preview`}
          wide={isWide}
          debug={debug}
          loading={debugLoading}
          error={debugError}
          onSelectInspector={handleSelectInspector}
        />

        <section
          id={`${baseId}-panel-events`}
          role={isWide ? undefined : 'tabpanel'}
          aria-labelledby={isWide ? undefined : `${baseId}-tab-events`}
          aria-label={isWide ? t(DEBUG_TAB_LABEL_KEYS.events) : undefined}
          className="thread-debug-col thread-debug-col-events"
        >
          <ThreadEventList
            baseId={baseId}
            events={events}
            selectedEventId={selectedEventId}
            selectedIndex={selectedIndex}
            loading={historyLoading}
            error={historyError}
            onRetry={onRetryHistory}
            bodyRef={bodyRef}
            onSelectEvent={handleSelectEvent}
            onKeyDown={handleListboxKeyDown}
          />
        </section>

        <ThreadDebugDetailColumn
          tabId={`${baseId}-tab-detail`}
          tabPanelId={`${baseId}-panel-detail`}
          wide={isWide}
          debug={debug}
          selection={debugSelection}
          selectedRecord={selectedRecord}
          historicalPreview={historicalPreview}
          historicalPreviewError={historicalPreviewError}
          historicalPreviewLoading={historicalPreviewLoading}
          closeButtonRef={detailCloseBtnRef}
          onClose={handleCloseDetail}
          onDismissHistoricalPreview={onDismissHistoricalPreview}
          onRequestHistoricalPreview={onRequestHistoricalPreview}
        />
      </div>
    </div>
  )
}
