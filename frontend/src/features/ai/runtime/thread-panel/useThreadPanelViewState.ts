import { useCallback, useEffect, useRef, useState, type RefObject } from 'react'
import type { ThreadEventRecord } from '@/features/ai/runtime/thread-events'

export type ThreadPanelMainMode = 'conversation' | 'debug'

interface MainViewScrollPositions {
  conversation: number | null
  debug: number | null
}

/**
 * 每个 Pane 独立维护 Conversation/Debug 的模式、选中行和两套滚动位置。
 *
 * 状态归属的键是本地「视图身份」而不是 API threadId：本地分支/新建草稿都没有 threadId
 * （都是 ""），只有按目标稳定字段区分身份，才能在换绑目标时整体重置，而不是复用上一份
 * 草稿的 Debug 模式与选中行。身份变化即重置模式、选中行与两套滚动位置。
 */
export function useThreadPanelViewState(
  viewKey: string,
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

  const lastViewKeyRef = useRef(viewKey)
  useEffect(() => {
    if (lastViewKeyRef.current === viewKey) {
      return
    }
    lastViewKeyRef.current = viewKey
    positionsRef.current = { conversation: null, debug: null }
    setInitialConversationScrollTop(null)
    setInitialDebugScrollTop(null)
    setSelectedEventId(null)
    modeRef.current = 'conversation'
    setModeState('conversation')
  }, [viewKey])

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
