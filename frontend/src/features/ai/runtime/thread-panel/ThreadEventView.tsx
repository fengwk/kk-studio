import { useEffect, useMemo, useRef, type KeyboardEvent, type RefObject } from 'react'
import type { ThreadEventRecord } from '@/features/ai/runtime/thread-events'
import { useChatTranscriptAutoScroll } from '@/features/ai/runtime/useChatTranscriptAutoScroll'
import { useI18n } from '@/shared/i18n'

const PAGE_STEP = 8

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

/**
 * Event 主视图：listbox/option 语义，与 Conversation 互斥渲染（只存在一个主滚动区）。
 *
 * - 行布局：固定时间列 + kind badge + 单行 summary ellipsis；failed danger 色、
 *   running pulse dot；首版不虚拟滚动；
 * - 键盘：ArrowUp/Down、Home/End、PageUp/PageDown(±8) 移动 active，Enter/Space
 *   打开详情，Escape 在详情打开时关闭详情（否则交由全局 Escape 恢复 Composer 焦点）；
 * - 鼠标：只有 mousemove 改变 active；click 同时设为 active 并选择详情；
 * - `activeEventId` 由 Pane/Thread 级 state 持有（跨视图互切不重置），本视图只
 *   派生当前有效 active（为 null 时取最新事件）并上报变化；
 * - `initialScrollTop` 非空时挂载即恢复该位置（重新进入事件视图）；否则贴底。
 */
export function ThreadEventView({
  events,
  activeEventId,
  onActiveEventIdChange,
  detailOpen = false,
  initialScrollTop = null,
  bodyRef: bodyRefProp,
  onSelect,
  onCloseDetail,
}: {
  events: ThreadEventRecord[]
  activeEventId: string | null
  onActiveEventIdChange: (eventId: string) => void
  detailOpen?: boolean
  /** 重新进入事件视图时恢复的 scrollTop；null 表示首次进入（贴底）。 */
  initialScrollTop?: number | null
  /** 外部 scroll 容器 ref（Pane 在切换离开前捕获 scrollTop）。 */
  bodyRef?: RefObject<HTMLDivElement | null>
  onSelect: (event: ThreadEventRecord) => void
  onCloseDetail: () => void
}) {
  const { t } = useI18n()
  const internalBodyRef = useRef<HTMLDivElement>(null)
  const bodyRef = bodyRefProp ?? internalBodyRef
  // 初始 active 在挂载时不应触发 scrollIntoView（重新进入时恢复的是历史位置）。
  const effectiveActiveId = activeEventId ?? events.at(-1)?.id ?? null
  const lastScrolledIdRef = useRef<string | null>(effectiveActiveId)
  const activeIndex = useMemo(
    () => events.findIndex((event) => event.id === effectiveActiveId),
    [effectiveActiveId, events],
  )

  useChatTranscriptAutoScroll(bodyRef, events.length, events.length, initialScrollTop)

  useEffect(() => {
    bodyRef.current?.focus({ preventScroll: true })
  }, [bodyRef])

  useEffect(() => {
    if (lastScrolledIdRef.current === effectiveActiveId) {
      return
    }
    lastScrolledIdRef.current = effectiveActiveId
    if (activeIndex < 0) {
      return
    }
    bodyRef.current
      ?.querySelector<HTMLElement>(`[data-event-id="${CSS.escape(events[activeIndex]!.id)}"]`)
      ?.scrollIntoView({ block: 'nearest' })
  }, [activeIndex, bodyRef, effectiveActiveId, events])

  function moveActive(delta: number) {
    if (events.length === 0) {
      return
    }
    const current = activeIndex >= 0 ? activeIndex : 0
    const next = Math.max(0, Math.min(events.length - 1, current + delta))
    onActiveEventIdChange(events[next]!.id)
  }

  function moveToBoundary(boundary: 'first' | 'last') {
    const next = boundary === 'first' ? events[0] : events.at(-1)
    if (next) {
      onActiveEventIdChange(next.id)
    }
  }

  function activateActiveItem() {
    const event = events[activeIndex >= 0 ? activeIndex : 0]
    if (event) {
      onSelect(event)
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
        const active = event.id === effectiveActiveId
        return (
          <div
            key={event.id}
            id={`thread-event-${event.id}`}
            data-event-id={event.id}
            role="option"
            aria-selected={active}
            className={`thread-event kind-${event.kind} status-${event.status} ${active ? 'active' : ''}`}
            onClick={() => {
              onActiveEventIdChange(event.id)
              onSelect(event)
            }}
            onMouseMove={() => onActiveEventIdChange(event.id)}
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
  )
}
