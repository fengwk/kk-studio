import { useCallback, useEffect, useRef, useState, type RefObject } from 'react'

export type MainViewName = 'conversation' | 'events'

interface MainViewScrollPositions {
  conversation: number | null
  events: number | null
}

/**
 * Conversation/Event 互斥主视图的滚动恢复（每 Pane/Thread 两个 scrollTop）：
 *
 * - 切换前捕获当前主视图的 scrollTop；
 * - 目标视图首次进入时贴底（不恢复），后续进入恢复上次离开时的位置；
 * - conversation 的恢复在转录重新挂载后由 effect 应用（父 effect 晚于子 effect），
 *   并通过一次原生 scroll 事件让自动贴底跟随恢复后的位置；
 * - events 的恢复通过 `initialEventsScrollTop` 传给 ThreadEventView，由其挂载时应用；
 * - threadId 重绑时 `reset()` 清空两个位置并回到 conversation。
 * 刻意不用 localStorage：位置只属于当前 mounted Pane。
 */
export function useMainViewScrollRestore(
  transcriptBodyRef: RefObject<HTMLDivElement | null>,
) {
  const [mainView, setMainViewState] = useState<MainViewName>('conversation')
  const mainViewRef = useRef<MainViewName>('conversation')
  const positionsRef = useRef<MainViewScrollPositions>({
    conversation: null,
    events: null,
  })
  const [initialEventsScrollTop, setInitialEventsScrollTop] = useState<number | null>(null)
  const eventsBodyRef = useRef<HTMLDivElement>(null)

  const switchMainView = useCallback(function switchMainView(next: MainViewName) {
    const current = mainViewRef.current
    if (current === next) {
      return
    }
    const container = current === 'events' ? eventsBodyRef.current : transcriptBodyRef.current
    const positions = { ...positionsRef.current }
    if (container) {
      positions[current] = container.scrollTop
    }
    positionsRef.current = positions
    mainViewRef.current = next
    setInitialEventsScrollTop(next === 'events' ? positions.events : null)
    setMainViewState(next)
  }, [transcriptBodyRef])

  /** threadId 重绑：清空两个视图的已保存位置并回到 conversation。 */
  const reset = useCallback(function reset() {
    positionsRef.current = { conversation: null, events: null }
    setInitialEventsScrollTop(null)
    mainViewRef.current = 'conversation'
    setMainViewState('conversation')
  }, [])

  // 切回 conversation：transcript 重新挂载（子 effect 贴底）后恢复保存的位置，
  // 并派发一次 scroll 事件让自动贴底的 stick 状态跟随恢复后的位置。
  useEffect(() => {
    if (mainView !== 'conversation') {
      return
    }
    const saved = positionsRef.current.conversation
    if (saved == null) {
      return
    }
    const body = transcriptBodyRef.current
    if (!body) {
      return
    }
    body.scrollTop = saved
    body.dispatchEvent(new Event('scroll', { bubbles: false }))
  }, [mainView, transcriptBodyRef])

  return { mainView, switchMainView, reset, eventsBodyRef, initialEventsScrollTop }
}
