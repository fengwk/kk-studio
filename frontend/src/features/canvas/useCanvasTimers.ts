import { useCallback, useEffect, useRef, type Dispatch, type MutableRefObject } from 'react'
import { SAVE_SETTLE_MS } from '@/features/canvas/data'
import type { CanvasAction } from '@/features/canvas/reducer'
import type { CanvasDocumentState, StageMetrics } from '@/features/canvas/types'

/** Toast / save-settling / agent-run interval timers for the canvas controller. */
export function useCanvasTimers({
  state,
  dispatch,
  stageMetricsRef,
}: {
  state: CanvasDocumentState
  dispatch: Dispatch<CanvasAction>
  stageMetricsRef: MutableRefObject<StageMetrics>
}) {
  const toastTimerRef = useRef<number | null>(null)
  const saveTimerRef = useRef<number | null>(null)
  const runTimerRef = useRef<number | null>(null)

  const clearToastTimer = useCallback(() => {
    if (toastTimerRef.current !== null) {
      window.clearTimeout(toastTimerRef.current)
      toastTimerRef.current = null
    }
  }, [])

  const clearSaveTimer = useCallback(() => {
    if (saveTimerRef.current !== null) {
      window.clearTimeout(saveTimerRef.current)
      saveTimerRef.current = null
    }
  }, [])

  const stopRunTimer = useCallback(() => {
    if (runTimerRef.current !== null) {
      window.clearInterval(runTimerRef.current)
      runTimerRef.current = null
    }
  }, [])

  const showToast = useCallback((message: string) => {
    dispatch({ type: 'set-toast', toast: message })
    clearToastTimer()
    toastTimerRef.current = window.setTimeout(() => {
      dispatch({ type: 'set-toast', toast: null })
      toastTimerRef.current = null
    }, 2400)
  }, [clearToastTimer, dispatch])

  useEffect(() => {
    if (!state.toast) {
      return clearToastTimer
    }
    clearToastTimer()
    toastTimerRef.current = window.setTimeout(() => {
      dispatch({ type: 'set-toast', toast: null })
      toastTimerRef.current = null
    }, 2400)
    return clearToastTimer
  }, [state.toast, clearToastTimer, dispatch])

  useEffect(() => {
    if (state.saveState !== 'saving') {
      return clearSaveTimer
    }
    clearSaveTimer()
    saveTimerRef.current = window.setTimeout(() => {
      dispatch({ type: 'set-save-state', saveState: 'saved' })
      saveTimerRef.current = null
    }, SAVE_SETTLE_MS)
    return clearSaveTimer
  }, [state.saveState, state.nodes, state.links, clearSaveTimer, dispatch])

  useEffect(() => {
    const run = state.nodes.find((node) => node.type === 'run')
    if (!run || run.type !== 'run' || run.status !== 'running') {
      stopRunTimer()
      return stopRunTimer
    }
    if (runTimerRef.current !== null) {
      return stopRunTimer
    }
    runTimerRef.current = window.setInterval(() => {
      dispatch({ type: 'tick-agent-run', stage: stageMetricsRef.current })
    }, 620)
    return stopRunTimer
  }, [state.nodes, stopRunTimer, dispatch, stageMetricsRef])

  useEffect(() => () => {
    clearToastTimer()
    clearSaveTimer()
    stopRunTimer()
  }, [clearToastTimer, clearSaveTimer, stopRunTimer])

  return { showToast, stopRunTimer }
}
