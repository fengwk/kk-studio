import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type RefObject } from 'react'
import type { ThreadEventItem } from '@/features/ai/runtime/thread-events'
import { useChatTranscriptAutoScroll } from '@/features/ai/runtime/useChatTranscriptAutoScroll'
import { useI18n } from '@/shared/i18n'

const PAGE_STEP = 10

/**
 * Event 主视图：listbox/option 语义，与 Conversation 互斥渲染（只存在一个主滚动区）。
 *
 * - 键盘：ArrowUp/Down、Home/End、PageUp/PageDown 移动 active，Enter/Space 打开详情，
 *   Escape 在详情打开时关闭详情（否则交由全局 Escape 恢复 Composer 焦点）；
 * - 鼠标：只有 mousemove 改变 active；click 同时把该行设为 active 并打开详情；
 * - active id 在事件列表收缩后消失时回到最新事件；
 * - `initialScrollTop` 非空时挂载即恢复该位置（重新进入事件视图）；否则贴底；
 * - Provider delta token 不逐条成行：活跃 invocation 是单条事件。
 */
export function ThreadEventView({
  events,
  detailOpen = false,
  initialScrollTop = null,
  bodyRef: bodyRefProp,
  onActivate,
  onCloseDetail,
}: {
  events: ThreadEventItem[]
  detailOpen?: boolean
  /** 重新进入事件视图时恢复的 scrollTop；null 表示首次进入（贴底）。 */
  initialScrollTop?: number | null
  /** 外部 scroll 容器 ref（Pane 在切换离开前捕获 scrollTop）。 */
  bodyRef?: RefObject<HTMLDivElement | null>
  onActivate: (event: ThreadEventItem) => void
  onCloseDetail: () => void
}) {
  const { t } = useI18n()
  const internalBodyRef = useRef<HTMLDivElement>(null)
  const bodyRef = bodyRefProp ?? internalBodyRef
  const [activeId, setActiveId] = useState<string | null>(() => events.at(-1)?.id ?? null)
  // 初始 active 在挂载时不应触发 scrollIntoView（重新进入时恢复的是历史位置）。
  const activeIdRef = useRef<string | null>(activeId)
  const activeIndex = useMemo(
    () => events.findIndex((event) => event.id === activeId),
    [activeId, events],
  )

  useChatTranscriptAutoScroll(bodyRef, events.length, events.length, initialScrollTop)

  useEffect(() => {
    bodyRef.current?.focus({ preventScroll: true })
  }, [bodyRef])

  // 事件列表收缩后 active id 消失：回到最新事件（空列表时清空）。
  useEffect(() => {
    if (events.length === 0) {
      if (activeId != null) {
        setActiveId(null)
      }
      return
    }
    if (activeId == null || !events.some((event) => event.id === activeId)) {
      const latestId = events.at(-1)!.id
      if (activeId !== latestId) {
        setActiveId(latestId)
      }
    }
  }, [activeId, events])

  useEffect(() => {
    if (activeIdRef.current === activeId) {
      return
    }
    activeIdRef.current = activeId
    if (activeIndex < 0) {
      return
    }
    bodyRef.current
      ?.querySelector<HTMLElement>(`[data-event-id="${CSS.escape(events[activeIndex]!.id)}"]`)
      ?.scrollIntoView({ block: 'nearest' })
  }, [activeIndex, activeId, bodyRef, events])

  function moveActive(delta: number) {
    if (events.length === 0) {
      return
    }
    const current = activeIndex >= 0 ? activeIndex : 0
    const next = Math.max(0, Math.min(events.length - 1, current + delta))
    setActiveId(events[next]?.id ?? null)
  }

  function moveToBoundary(boundary: 'first' | 'last') {
    const next = boundary === 'first' ? events[0] : events.at(-1)
    setActiveId(next?.id ?? null)
  }

  function activateActiveItem() {
    const event = events[activeIndex >= 0 ? activeIndex : 0]
    if (event) {
      onActivate(event)
    }
  }

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.nativeEvent.isComposing || event.keyCode === 229) {
      return
    }
    if (event.key === 'Escape') {
      if (detailOpen) {
        event.preventDefault()
        event.stopPropagation()
        onCloseDetail()
      }
      return
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      event.stopPropagation()
      moveActive(event.key === 'ArrowDown' ? 1 : -1)
      return
    }
    if (event.key === 'PageDown' || event.key === 'PageUp') {
      event.preventDefault()
      event.stopPropagation()
      moveActive(event.key === 'PageDown' ? PAGE_STEP : -PAGE_STEP)
      return
    }
    if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault()
      event.stopPropagation()
      moveToBoundary(event.key === 'Home' ? 'first' : 'last')
      return
    }
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      event.stopPropagation()
      activateActiveItem()
    }
  }

  return (
    <div
      ref={bodyRef}
      className="thread-events"
      role="listbox"
      tabIndex={0}
      aria-label={t('ai.runtime.event.list')}
      aria-activedescendant={
        activeIndex >= 0 && events[activeIndex] ? `thread-event-${events[activeIndex]!.id}` : undefined
      }
      onKeyDown={handleKeyDown}
    >
      {events.length === 0 ? (
        <div className="thread-empty">
          <p>{t('ai.runtime.event.empty')}</p>
        </div>
      ) : null}
      {events.map((event) => {
        const active = event.id === activeId
        return (
          <div
            key={event.id}
            id={`thread-event-${event.id}`}
            data-event-id={event.id}
            role="option"
            aria-selected={active}
            className={`thread-event ${active ? 'active' : ''}`}
            onClick={() => {
              setActiveId(event.id)
              onActivate(event)
            }}
            onMouseMove={() => setActiveId(event.id)}
          >
            <div className="thread-event-title">{event.title}</div>
            <div className="thread-event-text">{event.text}</div>
          </div>
        )
      })}
    </div>
  )
}
