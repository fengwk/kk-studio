import { useCallback, useState, type Dispatch, type SetStateAction } from 'react'
import { enterCanvasScope, type CanvasScope } from '@/features/canvas/canvas-controller-scope'
import { sameSelection } from '@/features/canvas/canvas-local-state'
import type { CanvasLocalState } from '@/features/canvas/types'
import {
  hasStoredCanvasViewport,
  loadCanvasViewport,
  type StoredCanvasViewport,
} from '@/features/canvas/viewport-storage'

export interface CanvasNavigationOptions {
  scope: CanvasScope
  initialCanvasId: string | null
  setState: Dispatch<SetStateAction<CanvasLocalState>>
  reloadDrafts: () => void
  resetTransformBatch: () => void
  resetUploads: () => void
  resetFunctionConfigDrafts: () => void
  clearReservedAliases: () => void
}

export interface CanvasNavigationActions {
  openEditor: (canvasId: string) => void
  openLibrary: () => void
  setViewport: (viewport: StoredCanvasViewport) => void
  setSelection: (selectedIds: string[], selectedLinks?: CanvasLocalState['selectedLinks']) => void
  completeInitialFit: () => void
  initialFitPending: boolean
}

/**
 * 画布之间的导航与渲染状态入口：进入编辑器、返回列表、视口更新、选区更新。
 *
 * 离开画布时先清空所有进行中的本地编辑流水线再进入新作用域，任何旧画布的异步结果都不会写进新画布。
 */
export function useCanvasNavigation({
  scope,
  initialCanvasId,
  setState,
  reloadDrafts,
  resetTransformBatch,
  resetUploads,
  resetFunctionConfigDrafts,
  clearReservedAliases,
}: CanvasNavigationOptions): CanvasNavigationActions {
  // 深链进入且没有已存视口时才需要首帧 fit。
  const [initialFitPending, setInitialFitPending] = useState(
    () => Boolean(initialCanvasId && !hasStoredCanvasViewport(initialCanvasId)),
  )

  /** 离开当前画布前清空所有进行中的本地编辑流水线并进入新的作用域。 */
  const leaveCanvas = useCallback((nextCanvasId: string | null) => {
    resetTransformBatch()
    resetUploads()
    resetFunctionConfigDrafts()
    clearReservedAliases()
    enterCanvasScope(scope, nextCanvasId)
  }, [clearReservedAliases, resetFunctionConfigDrafts, resetTransformBatch, resetUploads, scope])

  const openEditor = useCallback((canvasId: string) => {
    leaveCanvas(canvasId)
    reloadDrafts()
    setInitialFitPending(!hasStoredCanvasViewport(canvasId))
    setState((current) => ({
      ...current,
      view: 'editor',
      canvasId,
      selectedIds: [],
      selectedLinks: [],
      positionDrafts: {},
      drafts: {},
      storageError: null,
      viewport: loadCanvasViewport(canvasId),
      conflictKind: null,
      conflictMessage: null,
      textEditor: null,
    }))
  }, [leaveCanvas, reloadDrafts, setState])

  const openLibrary = useCallback(() => {
    leaveCanvas(null)
    setInitialFitPending(false)
    setState((current) => ({
      ...current,
      view: 'library',
      canvasId: null,
      selectedIds: [],
      selectedLinks: [],
      positionDrafts: {},
      drafts: {},
      storageError: null,
      addMenuOpen: false,
      textEditor: null,
      conflictKind: null,
      conflictMessage: null,
    }))
  }, [leaveCanvas, setState])

  /** 视口写入由画布视口落盘（useCanvasViewportPersistence）在提交后合并执行，这里只更新渲染状态。 */
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

  return {
    openEditor,
    openLibrary,
    setViewport,
    setSelection,
    completeInitialFit,
    initialFitPending,
  }
}
