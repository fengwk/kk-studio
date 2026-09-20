import { useEffect, useMemo, useRef, type KeyboardEvent, type RefObject } from 'react'
import type { ThreadEventRecord } from '@/features/ai/runtime/thread-events'
import { ThreadModelRequestDebug } from '@/features/ai/runtime/thread-panel/ThreadModelRequestDebug'
import type { DebugInspectorSelection } from '@/features/ai/runtime/thread-panel/ThreadDebugInspector'
import type { HarnessModelRequestDebugDTO } from '@/shared/api/contracts/ai-runtime'
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

/**
 * Event 主视图：listbox/option 语义，与 Conversation 互斥渲染（只存在一个主滚动区）。
 *
 * - 行布局：父级网格 + subgrid 对齐时间/枚举标签/JSON；摘要只在行尾 CSS 省略；
 * - 选中：点击选中；↑/↓ 只在已选中时切换相邻行；鼠标移动不改选中；
 * - Esc / X 取消选中；详情只在有选中时展示；
 * - `initialScrollTop` 非空时挂载即恢复该位置；否则贴底。
 */
export function ThreadEventView({
  events,
  selectedEventId,
  onSelectedEventIdChange,
  initialScrollTop = null,
  bodyRef: bodyRefProp,
  debug,
  onSelectInspector = () => {},
}: {
  events: ThreadEventRecord[]
  selectedEventId: string | null
  onSelectedEventIdChange: (eventId: string | null) => void
  initialScrollTop?: number | null
  bodyRef?: RefObject<HTMLDivElement | null>
  debug?: HarnessModelRequestDebugDTO | null
  onSelectInspector?: (selection: DebugInspectorSelection | null) => void
}) {
  const { t } = useI18n()
  const internalBodyRef = useRef<HTMLDivElement>(null)
  const bodyRef = bodyRefProp ?? internalBodyRef
  const lastScrolledIdRef = useRef<string | null>(selectedEventId)
  const selectedIndex = useMemo(
    () => events.findIndex((event) => event.id === selectedEventId),
    [events, selectedEventId],
  )

  useChatTranscriptAutoScroll(bodyRef, events.length, events.length, initialScrollTop)

  useEffect(() => {
    bodyRef.current?.focus({ preventScroll: true })
  }, [bodyRef])

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

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.nativeEvent.isComposing || event.keyCode === 229) {
      return
    }
    if (event.key === 'Escape') {
      if (selectedEventId != null) {
        event.preventDefault()
        event.stopPropagation()
        onSelectedEventIdChange(null)
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

  return (
    <div className="thread-events-shell">
      {debug ? (
        <ThreadModelRequestDebug debug={debug} onSelectInspector={onSelectInspector} />
      ) : null}
      <div
        ref={bodyRef}
        className="thread-events"
        role="listbox"
        tabIndex={0}
        aria-label={t('ai.runtime.event.list')}
        aria-activedescendant={
          selectedIndex >= 0 && events[selectedIndex]
            ? `thread-event-${events[selectedIndex]!.id}`
            : undefined
        }
        onKeyDown={handleKeyDown}
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
              id={`thread-event-${event.id}`}
              data-event-id={event.id}
              role="option"
              aria-selected={selected}
              className={`thread-event kind-${event.kind} status-${event.status} ${selected ? 'active' : ''}`}
              onClick={() => onSelectedEventIdChange(event.id)}
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
    </div>
  )
}
