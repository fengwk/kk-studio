import { describe, expect, it } from 'vitest'
import { bindBeside, bindPane, canOpenDirectly, reconcileBindings } from './pane-bindings'
import { isPaneTarget, type PaneTarget } from './pane-target'
import { deserializePaneState, serializePaneState } from './panes/usePaneState'
import { closePane, initialPaneState, paneIds, splitPane, swapPanes } from './panes/model'

const thread: PaneTarget = { kind: 'thread', threadId: 'thread' }
const canvas: PaneTarget = { kind: 'canvas', canvasId: 'aaaaaaaa-0000-4000-8000-000000000001' }
const bounds = { x: 0, y: 0, width: 1100, height: 800 }
const noUnsent = () => false

function twoPanes() {
  const initial = initialPaneState<PaneTarget>()
  const first = initial.focusedPaneId
  const state = reconcileBindings(splitPane(initial, first, 'X'), () => true)
  return { first, second: state.focusedPaneId, state }
}

describe('workspace pane bindings', () => {
  it('only bypasses selection in a draft without unsent input', () => {
    const { state, first } = twoPanes()
    expect(canOpenDirectly(state, first, noUnsent)).toBe(true)
    expect(canOpenDirectly(state, first, () => true)).toBe(false)
    expect(canOpenDirectly(bindPane(state, first, thread), first, noUnsent)).toBe(false)
  })

  it('moves a Thread or identical IDE view without duplicating it, but permits Canvas views', () => {
    const { state, first, second } = twoPanes()
    const ide: PaneTarget = { kind: 'vscode', environmentId: 'environment', viewId: 'view' }
    for (const target of [thread, canvas]) {
      const moved = bindPane(bindPane(state, first, target), second, target)
      expect(moved.bindings[second]).toEqual(target.kind === 'canvas' ? { ...target, viewId: second } : target)
      expect(moved.bindings[first]?.kind).toBe(target.kind === 'canvas' ? 'canvas' : 'draft')
      expect(moved.focusedPaneId).toBe(second)
    }
    const movedIde = bindPane(bindPane(state, first, ide), second, ide)
    expect(paneIds(movedIde.layout)).toEqual([second, first])
    expect(movedIde.bindings[first]).toBe(ide)
    expect(movedIde.bindings[second]?.kind).toBe('draft')
    expect(movedIde.focusedPaneId).toBe(first)
  })

  it('keeps one legacy Canvas scope and does not promote a surviving secondary view', () => {
    // Old layouts could store identical Canvas bindings; only the first inherits old data.
    const { state, first, second } = twoPanes()
    const legacy = { ...state, bindings: { [first]: canvas, [second]: canvas } }
    const corrected = reconcileBindings(deserializePaneState(serializePaneState(legacy), isPaneTarget), () => true)
    expect(corrected.bindings).toEqual({ [first]: canvas, [second]: { ...canvas, viewId: second } })

    const swapped = swapPanes(corrected, first, second)
    expect(reconcileBindings(swapped, () => true)).toBe(swapped)
    const closed = closePane(swapped, first)
    const surviving = bindPane(closed, second, canvas)
    expect(surviving.bindings[second]).toEqual({ ...canvas, viewId: second })
    const restored = deserializePaneState(serializePaneState(surviving), isPaneTarget)
    expect(reconcileBindings(restored, () => true)).toEqual(surviving)
    const reopened = bindBeside(restored, second, canvas, noUnsent, bounds, false)!
    expect(reopened.bindings[reopened.focusedPaneId]).toEqual(canvas)
    expect(reopened.bindings[second]).toEqual(surviving.bindings[second])
  })

  it('opens beside in a clean draft before splitting; preserves an unsent draft', () => {
    const { state, first, second } = twoPanes()
    const source = bindPane(state, first, thread)
    expect(bindBeside(source, first, canvas, noUnsent, bounds, false)?.focusedPaneId).toBe(second)
    const next = bindBeside(source, first, canvas, id => id === second, bounds, false)!
    expect(next.focusedPaneId).not.toBe(second)
    expect(next.bindings[next.focusedPaneId]).toBe(canvas)
    expect(next.bindings[second]).toBe(source.bindings[second])
    expect(bindBeside(source, first, canvas, () => true, { ...bounds, height: 300 }, false)).toBeUndefined()
  })

  it('replaces a removed binding with a distinct draft, keeping layout and other drafts', () => {
    const { state, first, second } = twoPanes()
    const bound = bindPane(state, first, canvas)
    const next = reconcileBindings(bound, target => target !== canvas)
    expect(next.layout).toBe(state.layout)
    expect(next.bindings[first]).toMatchObject({ kind: 'draft' })
    expect(next.bindings[first]).not.toEqual(state.bindings[first])
    expect(next.bindings[second]).toBe(state.bindings[second])
  })
})
