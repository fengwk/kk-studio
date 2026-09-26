import {
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
  type RefObject,
} from 'react'
import type { ThreadEventRecord } from '@/features/ai/runtime/thread-events'
import { ThreadModelRequestDebug } from '@/features/ai/runtime/thread-panel/ThreadModelRequestDebug'
import {
  ThreadDebugInspector,
  type DebugInspectorSelection,
} from '@/features/ai/runtime/thread-panel/ThreadDebugInspector'
import { ThreadEventDetail } from '@/features/ai/runtime/thread-panel/ThreadEventDetail'
import type { ThreadModelRequestDebugData } from '@/features/ai/runtime/thread-timeline-types'
import { useChatTranscriptAutoScroll } from '@/features/ai/runtime/useChatTranscriptAutoScroll'
import { useI18n } from '@/shared/i18n'

function formatRowTime(value: ThreadEventRecord['createdAt']): string {
  let date: Date | null = null
  if (typeof value === 'string') {
    const parsed = new Date(value)
    if (!Number.isNaN(parsed.getTime())) {
      date = parsed
    }
  } else if (typeof value === 'number') {
    date = new Date(value)
  } else if (Array.isArray(value) && typeof value[0] === 'number') {
    date = new Date(value[0])
  }
  if (date == null || Number.isNaN(date.getTime())) {
    return ''
  }
  const pad = (part: number) => String(part).padStart(2, '0')
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

export type DebugViewTab = 'preview' | 'events' | 'detail'

const TAB_KEYS: DebugViewTab[] = ['preview', 'events', 'detail']

export interface ThreadEventViewProps {
  events: ThreadEventRecord[]
  selectedEventId: string | null
  onSelectedEventIdChange: (eventId: string | null) => void
  initialScrollTop?: number | null
  bodyRef?: RefObject<HTMLDivElement | null>
  debug?: ThreadModelRequestDebugData | null
  debugSelection?: DebugInspectorSelection | null
  onSelectInspector?: (selection: DebugInspectorSelection | null) => void
  selectedRecord?: ThreadEventRecord | null
  onCloseDetail?: () => void
}

/**
 * Debug 主视图：支持 3 区响应式布局。
 *
 * - 宽面板 (container >= 1100px)：三等宽列（请求预览 / 事件列表 / 详情），各自唯一纵向滚动，外框无滚动；
 * - 窄面板 (container < 1100px)：单区填满，通过带有 roving tabIndex 的 Tab 导航（请求预览 / 事件 / 详情）切换；
 * - 点击事件 / 工具 / skill / request 自动切换到详情并安全转移焦点；关闭详情返回来源页签并恢复可见焦点；
 * - 所有 ID 均基于 useId 作用域，防止在多 pane split 时冲突。
 */
export function ThreadEventView({
  events,
  selectedEventId,
  onSelectedEventIdChange,
  initialScrollTop = null,
  bodyRef: bodyRefProp,
  debug,
  debugSelection = null,
  onSelectInspector = () => {},
  selectedRecord: selectedRecordProp,
  onCloseDetail,
}: ThreadEventViewProps) {
  const { t } = useI18n()
  const baseId = useId()
  const containerRef = useRef<HTMLDivElement>(null)
  const internalBodyRef = useRef<HTMLDivElement>(null)
  const bodyRef = bodyRefProp ?? internalBodyRef
  const lastScrolledIdRef = useRef<string | null>(selectedEventId)

  // 记录唤起详情的来源以及触发元素，以便关闭详情时切回并恢复可见焦点
  const lastDetailSourceRef = useRef<'events' | 'preview'>(
    debugSelection != null ? 'preview' : 'events',
  )
  const lastFocusedTriggerRef = useRef<HTMLElement | null>(null)
  const detailCloseBtnRef = useRef<HTMLButtonElement>(null)
  const detailSectionRef = useRef<HTMLDivElement>(null)
  const tabButtonRefs = useRef<Record<DebugViewTab, HTMLButtonElement | null>>({
    preview: null,
    events: null,
    detail: null,
  })

  // Tab 状态 (默认事件列)
  const [activeTab, setActiveTab] = useState<DebugViewTab>(() => {
    if (debugSelection != null || selectedEventId != null) {
      return 'detail'
    }
    return 'events'
  })

  // 容器宽度判定：由 ResizeObserver 监听，判定 >= 1100px 为宽模式
  const [layoutMode, setLayoutMode] = useState<'wide' | 'narrow'>('wide')

  useEffect(() => {
    const el = containerRef.current
    if (!el || typeof ResizeObserver === 'undefined') {
      return
    }
    const updateSize = (width: number) => {
      setLayoutMode(width >= 1100 ? 'wide' : 'narrow')
    }
    updateSize(el.clientWidth)
    const observer = new ResizeObserver((entries) => {
      const entry = entries[0]
      if (entry) {
        updateSize(entry.contentRect.width)
      }
    })
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  const selectedIndex = useMemo(
    () => events.findIndex((event) => event.id === selectedEventId),
    [events, selectedEventId],
  )

  const selectedRecord = useMemo(() => {
    if (selectedRecordProp !== undefined) {
      return selectedRecordProp
    }
    if (selectedEventId == null) {
      return null
    }
    return events.find((e) => e.id === selectedEventId) ?? null
  }, [events, selectedEventId, selectedRecordProp])

  useChatTranscriptAutoScroll(bodyRef, events.length, events.length, initialScrollTop)

  useEffect(() => {
    if (layoutMode === 'wide' || activeTab === 'events') {
      bodyRef.current?.focus({ preventScroll: true })
    }
  }, [bodyRef, layoutMode, activeTab])

  useEffect(() => {
    if (selectedEventId == null || lastScrolledIdRef.current === selectedEventId) {
      lastScrolledIdRef.current = selectedEventId
      return
    }
    lastScrolledIdRef.current = selectedEventId
    if (selectedIndex < 0) {
      return
    }
    bodyRef.current
      ?.querySelector<HTMLElement>(`[data-event-id="${CSS.escape(events[selectedIndex]!.id)}"]`)
      ?.scrollIntoView({ block: 'nearest' })
  }, [bodyRef, events, selectedEventId, selectedIndex])

  function moveSelected(delta: number) {
    if (events.length === 0 || selectedIndex < 0) {
      return
    }
    const next = Math.max(0, Math.min(events.length - 1, selectedIndex + delta))
    onSelectedEventIdChange(events[next]!.id)
  }

  function handleListboxKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.nativeEvent.isComposing || event.keyCode === 229) {
      return
    }
    if (event.key === 'Escape') {
      if (selectedEventId != null) {
        event.preventDefault()
        event.stopPropagation()
        handleCloseDetail()
      }
      return
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      if (selectedEventId == null) {
        return
      }
      event.preventDefault()
      event.stopPropagation()
      moveSelected(event.key === 'ArrowDown' ? 1 : -1)
    }
  }

  function handleCloseDetail() {
    if (debugSelection != null) {
      onSelectInspector(null)
    }
    if (selectedEventId != null) {
      onSelectedEventIdChange(null)
    }
    onCloseDetail?.()
    const nextTab = lastDetailSourceRef.current === 'preview' ? 'preview' : 'events'
    setActiveTab(nextTab)

    // 恢复可见焦点到触发元素
    requestAnimationFrame(() => {
      const trigger = lastFocusedTriggerRef.current
      if (trigger && document.body.contains(trigger)) {
        trigger.focus({ preventScroll: true })
      } else if (nextTab === 'events') {
        bodyRef.current?.focus({ preventScroll: true })
      }
    })
  }

  function handleSelectEvent(id: string, element: HTMLElement) {
    lastFocusedTriggerRef.current = element
    lastDetailSourceRef.current = 'events'
    setActiveTab('detail')
    if (onSelectInspector) {
      onSelectInspector(null)
    }
    onSelectedEventIdChange(id)
  }

  function handleSelectInspector(
    selection: DebugInspectorSelection | null,
    triggerElement?: HTMLElement | null,
  ) {
    if (selection != null) {
      lastFocusedTriggerRef.current = triggerElement ?? null
      lastDetailSourceRef.current = 'preview'
      setActiveTab('detail')
      onSelectedEventIdChange(null)
    }
    onSelectInspector(selection)
  }

  // Roving TabIndex 键盘导航: ArrowLeft, ArrowRight, Home, End
  function handleTabKeyDown(event: KeyboardEvent<HTMLButtonElement>, currentTab: DebugViewTab) {
    const currentIndex = TAB_KEYS.indexOf(currentTab)
    let nextIndex = currentIndex

    if (event.key === 'ArrowRight') {
      event.preventDefault()
      nextIndex = (currentIndex + 1) % TAB_KEYS.length
    } else if (event.key === 'ArrowLeft') {
      event.preventDefault()
      nextIndex = (currentIndex - 1 + TAB_KEYS.length) % TAB_KEYS.length
    } else if (event.key === 'Home') {
      event.preventDefault()
      nextIndex = 0
    } else if (event.key === 'End') {
      event.preventDefault()
      nextIndex = TAB_KEYS.length - 1
    }

    if (nextIndex !== currentIndex) {
      const nextTab = TAB_KEYS[nextIndex]!
      setActiveTab(nextTab)
      tabButtonRefs.current[nextTab]?.focus()
    }
  }

  // 详情区域内局部 Esc 处理
  function handleDetailContainerKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.nativeEvent.isComposing || event.keyCode === 229) {
      return
    }
    if (event.key === 'Escape') {
      event.preventDefault()
      event.stopPropagation()
      handleCloseDetail()
    }
  }

  const isWide = layoutMode === 'wide'

  return (
    <div
      ref={containerRef}
      className="thread-events-shell"
      data-layout={layoutMode}
      data-active-tab={activeTab}
    >
      {/* 窄面板下的 WAI-ARIA Tab 导航；宽面板不渲染 tablist */}
      {!isWide ? (
        <div className="thread-debug-tabs" role="tablist" aria-label="Debug views">
          <button
            ref={(el) => {
              tabButtonRefs.current.preview = el
            }}
            type="button"
            role="tab"
            id={`${baseId}-tab-preview`}
            aria-controls={`${baseId}-panel-preview`}
            aria-selected={activeTab === 'preview'}
            tabIndex={activeTab === 'preview' ? 0 : -1}
            className={`thread-debug-tab ${activeTab === 'preview' ? 'active' : ''}`}
            onClick={() => setActiveTab('preview')}
            onKeyDown={(e) => handleTabKeyDown(e, 'preview')}
          >
            {t('ai.runtime.debug.tabPreview')}
          </button>
          <button
            ref={(el) => {
              tabButtonRefs.current.events = el
            }}
            type="button"
            role="tab"
            id={`${baseId}-tab-events`}
            aria-controls={`${baseId}-panel-events`}
            aria-selected={activeTab === 'events'}
            tabIndex={activeTab === 'events' ? 0 : -1}
            className={`thread-debug-tab ${activeTab === 'events' ? 'active' : ''}`}
            onClick={() => setActiveTab('events')}
            onKeyDown={(e) => handleTabKeyDown(e, 'events')}
          >
            {t('ai.runtime.debug.tabEvents')}
          </button>
          <button
            ref={(el) => {
              tabButtonRefs.current.detail = el
            }}
            type="button"
            role="tab"
            id={`${baseId}-tab-detail`}
            aria-controls={`${baseId}-panel-detail`}
            aria-selected={activeTab === 'detail'}
            tabIndex={activeTab === 'detail' ? 0 : -1}
            className={`thread-debug-tab ${activeTab === 'detail' ? 'active' : ''}`}
            onClick={() => setActiveTab('detail')}
            onKeyDown={(e) => handleTabKeyDown(e, 'detail')}
          >
            {t('ai.runtime.debug.tabDetail')}
          </button>
        </div>
      ) : null}

      {/* 3 区内容网格 */}
      <div className="thread-debug-grid" data-active-tab={activeTab}>
        {/* 区域 1：请求预览 */}
        <section
          id={`${baseId}-panel-preview`}
          role={!isWide ? 'tabpanel' : undefined}
          aria-labelledby={!isWide ? `${baseId}-tab-preview` : undefined}
          aria-label={isWide ? t('ai.runtime.debug.tabPreview') : undefined}
          className="thread-debug-col thread-debug-col-preview"
        >
          {debug ? (
            <ThreadModelRequestDebug
              debug={debug}
              onSelectInspector={(selection) => {
                const activeEl = document.activeElement as HTMLElement | null
                handleSelectInspector(selection, activeEl)
              }}
            />
          ) : (
            <div className="thread-debug-placeholder">
              <p>{t('ai.runtime.debug.noPreview')}</p>
            </div>
          )}
        </section>

        {/* 区域 2：事件列表 */}
        <section
          id={`${baseId}-panel-events`}
          role={!isWide ? 'tabpanel' : undefined}
          aria-labelledby={!isWide ? `${baseId}-tab-events` : undefined}
          aria-label={isWide ? t('ai.runtime.debug.tabEvents') : undefined}
          className="thread-debug-col thread-debug-col-events"
        >
          <div
            ref={bodyRef}
            className="thread-events"
            role="listbox"
            tabIndex={0}
            aria-label={t('ai.runtime.event.list')}
            aria-activedescendant={
              selectedIndex >= 0 && events[selectedIndex]
                ? `${baseId}-event-${events[selectedIndex]!.id}`
                : undefined
            }
            onKeyDown={handleListboxKeyDown}
          >
            {events.length === 0 ? (
              <div className="thread-empty">
                <p>{t('ai.runtime.event.empty')}</p>
              </div>
            ) : null}
            {events.map((event) => {
              const selected = event.id === selectedEventId
              return (
                <div
                  key={event.id}
                  id={`${baseId}-event-${event.id}`}
                  data-event-id={event.id}
                  role="option"
                  aria-selected={selected}
                  className={`thread-event kind-${event.kind} status-${event.status} ${selected ? 'active' : ''}`}
                  onClick={(e) => handleSelectEvent(event.id, e.currentTarget)}
                >
                  <span className="thread-event-time" data-event-time>
                    {formatRowTime(event.createdAt)}
                  </span>
                  <span className="thread-event-badge">{event.title}</span>
                  <span className="thread-event-summary">{event.summary}</span>
                  {event.status === 'running' ? (
                    <span className="thread-event-pulse" aria-hidden="true" />
                  ) : null}
                </div>
              )
            })}
          </div>
        </section>

        {/* 区域 3：详情 */}
        <section
          ref={detailSectionRef}
          id={`${baseId}-panel-detail`}
          role={!isWide ? 'tabpanel' : undefined}
          aria-labelledby={!isWide ? `${baseId}-tab-detail` : undefined}
          aria-label={isWide ? t('ai.runtime.debug.tabDetail') : undefined}
          tabIndex={-1}
          className="thread-debug-col thread-debug-col-detail"
          onKeyDown={handleDetailContainerKeyDown}
        >
          {debugSelection && debug ? (
            <ThreadDebugInspector
              selection={debugSelection}
              debug={debug}
              onClose={handleCloseDetail}
              closeButtonRef={detailCloseBtnRef}
            />
          ) : selectedRecord ? (
            <ThreadEventDetail
              record={selectedRecord}
              onClose={handleCloseDetail}
              closeButtonRef={detailCloseBtnRef}
            />
          ) : (
            <div className="thread-debug-placeholder" data-testid="thread-debug-placeholder">
              <p>{t('ai.runtime.debug.noSelection')}</p>
            </div>
          )}
        </section>
      </div>
    </div>
  )
}
