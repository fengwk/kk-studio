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
 *   mount `initialScrollTop` 传入；
 * - `selectedEventId`：当前选中行；null 表示未选中，详情只在有选中时展示；
 *   切回 conversation / threadId 重绑清空；id 从列表消失即清空；
 * - threadId 重绑：mode/selected/两个 scrollTop 全部重置回 conversation。
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
      setSelectedEventId(null)
    }
    setModeState(next)
  }, [transcriptBodyRef])

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
    modeRef.current = 'conversation'
    setModeState('conversation')
  }, [threadId])

  useEffect(() => {
    setSelectedEventId((current) =>
      current != null && !events.some((event) => event.id === current) ? null : current,
    )
  }, [events])

  return {
    mode,
    switchMode,
    selectedEventId,
    selectEvent: setSelectedEventId,
    eventsBodyRef,
    initialConversationScrollTop,
    initialEventsScrollTop,
  }
}
