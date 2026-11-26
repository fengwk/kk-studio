import { useCallback, useState, type Dispatch, type SetStateAction } from 'react'
import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { createInitialCanvasState } from '@/features/canvas/canvas-local-state'
import { useCanvasEditorViewState } from '@/features/canvas/useCanvasEditorViewState'
import type { CanvasLocalState } from '@/features/canvas/types'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'
const VIEWPORT_KEY = `kk-studio.canvas.viewport:${CANVAS_ID}`

function useViewStateHarness() {
  const [state, setState] = useState(() => createInitialCanvasState(CANVAS_ID))
  const doubledDispatch = useCallback<Dispatch<SetStateAction<CanvasLocalState>>>((action) => {
    setState((current) => {
      if (typeof action === 'function') {
        // 模拟 StrictMode 重复调用 updater，以捕获同步写存储的副作用。
        action(current)
        return action(current)
      }
      return action
    })
  }, [])
  const { setViewport } = useCanvasEditorViewState(CANVAS_ID, doubledDispatch)
  return { state, setViewport }
}

describe('useCanvasEditorViewState viewport', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('keeps persistence out of the state updater: a re-invoked updater writes nothing', () => {
    const { result } = renderHook(() => useViewStateHarness())

    act(() => result.current.setViewport({ x: 3, y: 4, zoom: 0.8 }))

    expect(result.current.state.viewport).toEqual({ x: 3, y: 4, zoom: 0.8 })
    expect(localStorage.getItem(VIEWPORT_KEY)).toBeNull()
  })
})
