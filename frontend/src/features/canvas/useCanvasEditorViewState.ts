import { useCallback, useState, type Dispatch, type SetStateAction } from 'react'
import { sameSelection } from '@/features/canvas/canvas-local-state'
import type { CanvasLocalState } from '@/features/canvas/types'
import { hasStoredCanvasViewport, type StoredCanvasViewport } from '@/features/canvas/viewport-storage'

export function useCanvasEditorViewState(
  canvasId: string,
  setState: Dispatch<SetStateAction<CanvasLocalState>>,
) {
  const [initialFitPending, setInitialFitPending] = useState(() => !hasStoredCanvasViewport(canvasId))

  // 视口落盘在提交后执行；状态 updater 不写存储。
  const setViewport = useCallback((viewport: StoredCanvasViewport) => {
    setState((current) => ({ ...current, viewport }))
  }, [setState])

  const completeInitialFit = useCallback(() => {
    setInitialFitPending(false)
  }, [])

  const setSelection = useCallback((
    selectedIds: string[],
    selectedLinks: CanvasLocalState['selectedLinks'] = [],
  ) => {
    setState((current) => {
      if (sameSelection(current.selectedIds, selectedIds, current.selectedLinks, selectedLinks)) {
        return current
      }
      return { ...current, selectedIds, selectedLinks }
    })
  }, [setState])

  return { setViewport, setSelection, completeInitialFit, initialFitPending }
}
