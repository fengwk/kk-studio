import { useEffect, useRef, type KeyboardEvent, type RefObject } from 'react'
import type { ThreadEventRecord } from '@/features/thread/runtime/thread-timeline/thread-events'

/**
 * 事件列表的滚动跟随与键盘导航。
 *
 * - 选中行变化时把对应行滚进可视区（列表自身滚动，不动整个面板）；
 * - 只有已存在选中行时方向键才移动选中：避免在无选择时凭空选中第一行；
 * - Escape 在无历史预览时关闭详情，有预览时留给预览自己处理。
 */
export function useEventListNavigation({
  events,
  selectedEventId,
  selectedIndex,
  bodyRef,
  onSelectedEventIdChange,
  onCloseDetail,
  hasHistoricalPreview,
}: {
  events: ThreadEventRecord[]
  selectedEventId: string | null
  selectedIndex: number
  bodyRef: RefObject<HTMLDivElement | null>
  onSelectedEventIdChange: (eventId: string | null) => void
  onCloseDetail: () => void
  hasHistoricalPreview: boolean
}) {
  const lastScrolledIdRef = useRef<string | null>(selectedEventId)

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
      if (hasHistoricalPreview) {
        return
      }
      if (selectedEventId != null) {
        event.preventDefault()
        event.stopPropagation()
        onCloseDetail()
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

  return { handleListboxKeyDown }
}
