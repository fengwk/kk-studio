import { useCallback, useState, type Dispatch, type SetStateAction } from 'react'
import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useCanvasScope } from '@/features/canvas/canvas-controller-scope'
import { createInitialCanvasState } from '@/features/canvas/canvas-local-state'
import { useCanvasNavigation } from '@/features/canvas/useCanvasNavigation'
import type { CanvasLocalState } from '@/features/canvas/types'

const CANVAS_ID = '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'
const VIEWPORT_KEY = `kk-studio.canvas.viewport:${CANVAS_ID}`

/**
 * 用「更新函数被调用两次」的 dispatch 驱动导航 hook：复现 StrictMode 开发态对 updater 的重复调用，
 * 任何写在 updater 里的副作用都会翻倍出现。
 */
function useNavigationHarness() {
  const [state, setState] = useState<CanvasLocalState>(createInitialCanvasState)
  const doubledDispatch = useCallback<Dispatch<SetStateAction<CanvasLocalState>>>((action) => {
    setState((current) => {
      if (typeof action === 'function') {
        // 先按 StrictMode 语义多调一次更新函数，再取真实结果。
        action(current)
      }
      return typeof action === 'function' ? action(current) : action
    })
  }, [])
  const scope = useCanvasScope(doubledDispatch, null)
  const navigation = useCanvasNavigation({
    scope,
    initialCanvasId: null,
    setState: doubledDispatch,
    reloadDrafts: vi.fn(),
    resetTransformBatch: vi.fn(),
    resetUploads: vi.fn(),
    resetFunctionConfigDrafts: vi.fn(),
    clearReservedAliases: vi.fn(),
  })
  return { state, navigation }
}

describe('useCanvasNavigation viewport', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('keeps persistence out of the state updater: a re-invoked updater writes nothing', () => {
    const { result } = renderHook(() => useNavigationHarness())
    act(() => result.current.navigation.openEditor(CANVAS_ID))
    localStorage.removeItem(VIEWPORT_KEY)

    act(() => result.current.navigation.setViewport({ x: 3, y: 4, zoom: 0.8 }))

    expect(result.current.state.viewport).toEqual({ x: 3, y: 4, zoom: 0.8 })
    // 视口写入由提交后的副作用合并执行，更新函数（被调用两次）内不得同步写存储。
    expect(localStorage.getItem(VIEWPORT_KEY)).toBeNull()
  })
})
