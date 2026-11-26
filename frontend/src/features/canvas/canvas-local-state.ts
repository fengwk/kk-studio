import type { CanvasLocalState } from '@/features/canvas/types'
import { loadCanvasViewport } from '@/features/canvas/viewport-storage'

export function createInitialCanvasState(canvasId: string): CanvasLocalState {
  return {
    selectedIds: [],
    selectedLinks: [],
    positionDrafts: {},
    drafts: {},
    storageError: null,
    viewport: loadCanvasViewport(canvasId),
    toast: null,
    addMenuOpen: false,
    addMenuIndex: 0,
    uploadProgress: {},
    commandPending: false,
    draftPersistPending: false,
    conflictKind: null,
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
