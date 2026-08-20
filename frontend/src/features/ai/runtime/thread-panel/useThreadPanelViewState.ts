import { useCallback, useEffect, useRef, useState, type RefObject } from 'react'
import type { ThreadEventRecord } from '@/features/ai/runtime/thread-events'

export type ThreadPanelMainMode = 'conversation' | 'debug'

interface MainViewScrollPositions {
  conversation: number | null
  debug: number | null
}

/** 每个 Pane 独立维护 Conversation/Debug 的模式、选中行和两套滚动位置。 */
export function useThreadPanelViewState(
  threadId: string,
  transcriptBodyRef: RefObject<HTMLDivElement | null>,
  events: ThreadEventRecord[],
) {
  const [mode, setModeState] = useState<ThreadPanelMainMode>('conversation')
  const modeRef = useRef<ThreadPanelMainMode>('conversation')
  const positionsRef = useRef<MainViewScrollPositions>({
    conversation: null,
    debug: null,
  })
  const [initialConversationScrollTop, setInitialConversationScrollTop] = useState<number | null>(
    null,
  )
  const [initialDebugScrollTop, setInitialDebugScrollTop] = useState<number | null>(null)
  const [selectedEventId, setSelectedEventId] = useState<string | null>(null)
  const debugBodyRef = useRef<HTMLDivElement>(null)

  const switchMode = useCallback(function switchMode(next: ThreadPanelMainMode) {
    const current = modeRef.current
    if (current === next) {
      return
    }
    const container = current === 'debug' ? debugBodyRef.current : transcriptBodyRef.current
    const positions = { ...positionsRef.current }
    if (container) {
      positions[current] = container.scrollTop
    }
    positionsRef.current = positions
    modeRef.current = next
    setInitialConversationScrollTop(next === 'conversation' ? positions.conversation : null)
    setInitialDebugScrollTop(next === 'debug' ? positions.debug : null)
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
    positionsRef.current = { conversation: null, debug: null }
    setInitialConversationScrollTop(null)
    setInitialDebugScrollTop(null)
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
    eventsBodyRef: debugBodyRef,
    initialConversationScrollTop,
    initialEventsScrollTop: initialDebugScrollTop,
  }
}
