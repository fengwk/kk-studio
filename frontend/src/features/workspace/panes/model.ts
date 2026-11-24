import { createUuid } from '@/shared/lib/uuid'

export type Axis = 'X' | 'Y'
export type Leaf = { paneId: string }
export type Split = { splitId: string; axis: Axis; ratio: number; first: Layout; second: Layout }
export type Layout = Leaf | Split
export type PaneState<T> = { layout: Layout; focusedPaneId: string; bindings: Partial<Record<string, T>> }
export type Rect = { x: number; y: number; width: number; height: number }
export type SplitView = { splitId: string; axis: Axis; rect: Rect; line: Rect; ratio: number; min: number; max: number }

export const MAX_PANES = 8
const MIN_PANE = { width: 320, height: 240 } as const
const DIVIDER = 1

export function initialPaneState<T>(): PaneState<T> {
  const paneId = createUuid()
  return { layout: { paneId }, focusedPaneId: paneId, bindings: {} }
}

export function paneIds(layout: Layout): string[] {
  return 'paneId' in layout ? [layout.paneId] : [...paneIds(layout.first), ...paneIds(layout.second)]
}

export function paneLetter(index: number): string { return String.fromCharCode(65 + index) }

export function minimumSize(layout: Layout): { width: number; height: number } {
  if ('paneId' in layout) return { ...MIN_PANE }
  const a = minimumSize(layout.first), b = minimumSize(layout.second)
  return layout.axis === 'X'
    ? { width: a.width + b.width + DIVIDER, height: Math.max(a.height, b.height) }
    : { width: Math.max(a.width, b.width), height: a.height + b.height + DIVIDER }
}

export function canSplit(layout: Layout, axis: Axis, rect?: Rect): boolean {
  if (paneIds(layout).length >= MAX_PANES) return false
  return !rect || (axis === 'X' ? rect.width >= MIN_PANE.width * 2 + DIVIDER : rect.height >= MIN_PANE.height * 2 + DIVIDER)
}

function update(layout: Layout, transform: (node: Layout) => Layout): Layout {
  const next = transform(layout)
  if (next !== layout || 'paneId' in layout) return next
  const first = update(layout.first, transform), second = update(layout.second, transform)
  return first === layout.first && second === layout.second ? layout : { ...layout, first, second }
}

export function splitPane<T>(state: PaneState<T>, paneId: string, axis: Axis): PaneState<T> {
  if (!canSplit(state.layout, axis) || !paneIds(state.layout).includes(paneId)) return state
  const newId = createUuid()
  const layout = update(state.layout, node => 'paneId' in node && node.paneId === paneId
    ? { splitId: createUuid(), axis, ratio: 0.5, first: node, second: { paneId: newId } } : node)
  return { ...state, layout, focusedPaneId: newId }
}

export function closePane<T>(state: PaneState<T>, paneId: string): PaneState<T> {
  let focusedPaneId = state.focusedPaneId
  const layout = update(state.layout, node => {
    if ('paneId' in node) return node
    const sibling = 'paneId' in node.first && node.first.paneId === paneId ? node.second
      : 'paneId' in node.second && node.second.paneId === paneId ? node.first : undefined
    if (sibling && focusedPaneId === paneId) focusedPaneId = paneIds(sibling)[0]!
    return sibling ?? node
  })
  if (layout === state.layout) return state
  const bindings = { ...state.bindings }
  delete bindings[paneId]
  return { layout, focusedPaneId, bindings }
}

export function swapPanes<T>(state: PaneState<T>, a: string, b: string): PaneState<T> {
  const ids = paneIds(state.layout)
  if (a === b || !ids.includes(a) || !ids.includes(b)) return state
  const layout = update(state.layout, node => 'paneId' in node && (node.paneId === a || node.paneId === b)
    ? { paneId: node.paneId === a ? b : a } : node)
  return { ...state, layout }
}

export function clampRatio(ratio: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, ratio))
}

/** Projects a tree into a rectangle at least as large as its recursive minimum. */
export function projectLayout(layout: Layout, rect: Rect) {
  const panes = new Map<string, Rect>(), splits: SplitView[] = []
  function visit(node: Layout, box: Rect) {
    if ('paneId' in node) { panes.set(node.paneId, box); return }
    const horizontal = node.axis === 'X', dimension = horizontal ? 'width' : 'height'
    const usable = box[dimension] - DIVIDER
    const firstMin = minimumSize(node.first)[dimension], secondMin = minimumSize(node.second)[dimension]
    const maxPart = Math.max(firstMin, usable - secondMin)
    const min = firstMin / usable, max = maxPart / usable
    const ratio = clampRatio(node.ratio, min, max)
    const part = clampRatio(usable * ratio, firstMin, maxPart)
    const offset = horizontal ? 'x' : 'y'
    const line = { ...box, [offset]: box[offset] + part, [dimension]: DIVIDER }
    splits.push({ splitId: node.splitId, axis: node.axis, rect: box, line, ratio, min, max })
    visit(node.first, { ...box, [dimension]: part })
    visit(node.second, { ...box, [offset]: line[offset] + DIVIDER, [dimension]: usable - part })
  }
  visit(layout, rect)
  return { panes, splits }
}

export function resizeSplit<T>(state: PaneState<T>, view: SplitView, ratio: number): PaneState<T> {
  if (!Number.isFinite(ratio)) return state
  const next = clampRatio(ratio, view.min, view.max)
  const layout = update(state.layout, node => 'splitId' in node && node.splitId === view.splitId && node.ratio !== next
    ? { ...node, ratio: next } : node)
  return layout === state.layout ? state : { ...state, layout }
}
