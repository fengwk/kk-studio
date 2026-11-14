import type { CanvasLocalState } from '@/features/canvas/types'
import {
  DEFAULT_CANVAS_VIEWPORT,
  loadCanvasViewport,
} from '@/features/canvas/viewport-storage'

/** 控制器渲染状态的初始值：深链打开画布时直接进入编辑器并恢复已存视口。 */
export function createInitialCanvasState(initialCanvasId?: string): CanvasLocalState {
  return {
    view: initialCanvasId ? 'editor' : 'library',
    canvasId: initialCanvasId ?? null,
    selectedIds: [],
    selectedLinks: [],
    positionDrafts: {},
    drafts: {},
    storageError: null,
    viewport: initialCanvasId
      ? loadCanvasViewport(initialCanvasId)
      : { ...DEFAULT_CANVAS_VIEWPORT },
    toast: null,
    addMenuOpen: false,
    addMenuIndex: 0,
    uploadProgress: {},
    commandPending: false,
    draftPersistPending: false,
    conflictMessage: null,
    textEditor: null,
  }
}

/** 选区与选中的引用边是否与目标一致；一致时避免无意义的状态替换。 */
export function sameSelection(
  currentIds: string[],
  nextIds: string[],
  currentLinks: CanvasLocalState['selectedLinks'],
  nextLinks: CanvasLocalState['selectedLinks'],
): boolean {
  return currentIds.length === nextIds.length
    && currentIds.every((id, index) => id === nextIds[index])
    && currentLinks.length === nextLinks.length
    && currentLinks.every((link, index) => (
      link.sourceNodeId === nextLinks[index]?.sourceNodeId
      && link.targetNodeId === nextLinks[index]?.targetNodeId
    ))
}
