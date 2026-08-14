import { useCallback, useEffect, useRef, useState, type RefObject } from 'react'
import type { ThreadEventRecord } from '@/features/ai/runtime/thread-events'

export type ThreadPanelMainMode = 'conversation' | 'events'

interface MainViewScrollPositions {
  conversation: number | null
  events: number | null
}

/**
 * Pane/Thread 级 Conversation/Event 互斥主视图状态（每 Pane 一个实例，Bound Chat
 * 与 Canvas Bound 复用）：
 *
 * - `mode`：当前主视图；切换前捕获当前视图 scrollTop，目标视图把保存位置作为
 *   mount `initialScrollTop` 传入（视图内部 `useChatTranscriptAutoScroll` 挂载时
 *   应用并按 210px 阈值决定 stick，不靠父 effect 派发假 scroll）；
 * - `selectedEventId`：detail widget 选择；按最新 records 刷新、id 消失即清空；
 *   切回 conversation 关闭 detail；
 * - `activeEventId`：Event 列表 active 行；不因 Conversation/Event 互切重置，
 *   只随 threadId 重绑重置；id 从列表消失时回到最新事件；
 * - threadId 重绑：mode/selected/active/两个 scrollTop 全部重置回 conversation。
 * 刻意不用 localStorage：位置只属于当前 mounted Pane。
 */
export function useThreadPanelViewState(
  threadId: string,
  transcriptBodyRef: RefObject<HTMLDivElement | null>,
  events: ThreadEventRecord[],
) {
  const [mode, setModeState] = useState<ThreadPanelMainMode>('conversation')
  const modeRef = useRef<ThreadPanelMainMode>('conversation')
  const positionsRef = useRef<MainViewScrollPositions>({
    conversation: null,
    events: null,
  })
  const [initialConversationScrollTop, setInitialConversationScrollTop] = useState<number | null>(
    null,
  )
  const [initialEventsScrollTop, setInitialEventsScrollTop] = useState<number | null>(null)
  const [selectedEventId, setSelectedEventId] = useState<string | null>(null)
  const [activeEventId, setActiveEventId] = useState<string | null>(null)
  const eventsBodyRef = useRef<HTMLDivElement>(null)

  const switchMode = useCallback(function switchMode(next: ThreadPanelMainMode) {
    const current = modeRef.current
    if (current === next) {
      return
    }
    const container = current === 'events' ? eventsBodyRef.current : transcriptBodyRef.current
    const positions = { ...positionsRef.current }
    if (container) {
      positions[current] = container.scrollTop
    }
    positionsRef.current = positions
    modeRef.current = next
    setInitialConversationScrollTop(next === 'conversation' ? positions.conversation : null)
    setInitialEventsScrollTop(next === 'events' ? positions.events : null)
    if (next === 'conversation') {
      // 切回 conversation 关闭 detail；Event active 保留（跨视图恢复）。
      setSelectedEventId(null)
    }
    setModeState(next)
  }, [transcriptBodyRef])

  // threadId 重绑：全部重置回 conversation（mode/selected/active/scroll 清零）。
  const lastThreadIdRef = useRef(threadId)
  useEffect(() => {
    if (lastThreadIdRef.current === threadId) {
      return
    }
    lastThreadIdRef.current = threadId
    positionsRef.current = { conversation: null, events: null }
    setInitialConversationScrollTop(null)
    setInitialEventsScrollTop(null)
    setSelectedEventId(null)
    setActiveEventId(null)
    modeRef.current = 'conversation'
    setModeState('conversation')
  }, [threadId])

  // selected 按最新 records 刷新（id 消失清空）；active 消失时回到最新事件。
  useEffect(() => {
    setSelectedEventId((current) =>
      current != null && !events.some((event) => event.id === current) ? null : current,
    )
    setActiveEventId((current) => {
      if (current == null || events.some((event) => event.id === current)) {
        return current
      }
      return events.at(-1)?.id ?? null
    })
  }, [events])

  return {
    mode,
    switchMode,
    selectedEventId,
    selectEvent: setSelectedEventId,
    activeEventId,
    setActiveEventId,
    eventsBodyRef,
    initialConversationScrollTop,
    initialEventsScrollTop,
  }
}
