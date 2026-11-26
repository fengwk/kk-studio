import { createUuid } from '@/shared/lib/uuid'
import type { PaneTarget } from './pane-target'
import { canSplit, needsSinglePane, paneIds, projectLayout, splitPane, swapPanes, type PaneState, type Rect } from './panes/model'

type BindingState = PaneState<PaneTarget>
type Unsent = (paneId: string) => boolean

export function newDraft(folderId?: string): Extract<PaneTarget, { kind: 'draft' }> {
  return { kind: 'draft', draftId: createUuid(), ...(folderId ? { folderId } : {}) }
}

export function reconcileBindings(state: BindingState, exists: (target: PaneTarget) => boolean): BindingState {
  let bindings = state.bindings
  const primaryCanvases = new Set<string>()
  for (const id of paneIds(state.layout)) {
    const target = bindings[id]
    if (!target || !exists(target)) {
      bindings = { ...bindings, [id]: newDraft() }
    } else if (target.kind === 'canvas' && target.viewId === undefined) {
      // Older layouts could have several unscoped views. Keep the first on the original keys.
      if (primaryCanvases.has(target.canvasId)) bindings = { ...bindings, [id]: { ...target, viewId: id } }
      primaryCanvases.add(target.canvasId)
    }
  }
  return bindings === state.bindings ? state : { ...state, bindings }
}

export function sameTarget(a: PaneTarget | undefined, b: PaneTarget): boolean {
  switch (b.kind) {
    case 'draft': return a?.kind === b.kind && a.draftId === b.draftId
    case 'thread': return a?.kind === b.kind && a.threadId === b.threadId
    case 'canvas': return a?.kind === b.kind && a.canvasId === b.canvasId
    case 'vscode': return a?.kind === b.kind && a.environmentId === b.environmentId && a.viewId === b.viewId
  }
}

export function bindPane(state: BindingState, paneId: string, target: PaneTarget): BindingState {
  const ids = paneIds(state.layout)
  if (!ids.includes(paneId)) return state
  if (target.kind === 'vscode') {
    const source = ids.find(id => id !== paneId && sameTarget(state.bindings[id], target))
    if (source) {
      // Move the leaf, not its renderer: the host keyed by source remains mounted.
      return {
        ...swapPanes(state, source, paneId),
        bindings: { ...state.bindings, [paneId]: newDraft() },
        focusedPaneId: source,
      }
    }
  }
  if (target.kind === 'canvas') {
    const current = state.bindings[paneId]
    if (sameTarget(current, target)) return { ...state, focusedPaneId: paneId }
    const { canvasId } = target
    const primaryOpen = ids.some(id => {
      const other = state.bindings[id]
      return other?.kind === 'canvas' && other.canvasId === canvasId && other.viewId === undefined
    })
    if (primaryOpen) target = { ...target, viewId: paneId }
    else if (target.viewId !== undefined) target = { kind: 'canvas', canvasId }
  }
  const bindings = { ...state.bindings, [paneId]: target }
  if (target.kind === 'thread') {
    for (const id of ids) {
      if (id !== paneId && sameTarget(bindings[id], target)) bindings[id] = newDraft()
    }
  }
  return { ...state, focusedPaneId: paneId, bindings }
}

export function canOpenDirectly(state: BindingState, paneId: string, unsent: Unsent): boolean {
  return state.bindings[paneId]?.kind === 'draft' && !unsent(paneId)
}

export function bindBeside(state: BindingState, sourceId: string, target: PaneTarget, unsent: Unsent,
  bounds: Rect, fullscreen: boolean): BindingState | undefined {
  const ids = paneIds(state.layout)
  if (!ids.includes(sourceId)) return undefined
  const empty = ids.find(id => id !== sourceId && canOpenDirectly(state, id, unsent))
  if (empty) return bindPane(state, empty, target)
  const rect = needsSinglePane(state.layout, bounds) ? undefined
    : fullscreen ? bounds : projectLayout(state.layout, bounds).panes.get(sourceId)
  const axis = canSplit(state.layout, 'X', rect) ? 'X' : canSplit(state.layout, 'Y', rect) ? 'Y' : undefined
  if (!axis) return undefined
  const next = splitPane(state, sourceId, axis)
  return bindPane(next, next.focusedPaneId, target)
}
