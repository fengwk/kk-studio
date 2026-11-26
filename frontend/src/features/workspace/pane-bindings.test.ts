import { describe, expect, it } from 'vitest'
import { bindBeside, bindPane, canOpenDirectly, reconcileBindings } from './pane-bindings'
import { isPaneTarget, type PaneOpenTarget, type PaneTarget } from './pane-target'
import { deserializePaneState, serializePaneState } from './panes/usePaneState'
import { closePane, initialPaneState, MAX_PANES, paneIds, splitPane, swapPanes } from './panes/model'

const thread: PaneTarget = { kind: 'thread', threadId: 'thread' }
const canvas: PaneOpenTarget = { kind: 'canvas', canvasId: 'aaaaaaaa-0000-4000-8000-000000000001' }
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
      expect(moved.bindings[second]).toEqual(target.kind === 'canvas' ? { ...target, viewSlot: 1 } : target)
      expect(moved.bindings[first]?.kind).toBe(target.kind === 'canvas' ? 'canvas' : 'draft')
      expect(moved.focusedPaneId).toBe(second)
    }
    const movedIde = bindPane(bindPane(state, first, ide), second, ide)
    expect(paneIds(movedIde.layout)).toEqual([second, first])
    expect(movedIde.bindings[first]).toBe(ide)
    expect(movedIde.bindings[second]?.kind).toBe('draft')
    expect(movedIde.focusedPaneId).toBe(first)
  })

  it.each([undefined, 0, MAX_PANES])('discards an invalid or duplicate persisted Canvas slot (%s)', viewSlot => {
    const { state, first, second } = twoPanes()
    const valid = { ...canvas, viewSlot: 0 }
    const invalid = viewSlot === undefined ? canvas : { ...canvas, viewSlot }
    const snapshot = { ...state, bindings: { [first]: valid, [second]: invalid } }
    const restored = deserializePaneState(serializePaneState(snapshot), isPaneTarget)
    const reconciled = reconcileBindings(restored, () => true)
    expect(reconciled.layout).toEqual(state.layout)
    expect(reconciled.bindings[first]).toEqual(valid)
    expect(reconciled.bindings[second]).toMatchObject({ kind: 'draft' })
  })

  it('keeps the surviving Canvas slot after closing slot 0 and reopening', () => {
    const { state, first, second } = twoPanes()
    const bound = bindPane(bindPane(state, first, canvas), second, canvas)
    const swapped = swapPanes(bound, first, second)
    expect(reconcileBindings(swapped, () => true)).toBe(swapped)
    const closed = closePane(swapped, first)
    const surviving = bindPane(closed, second, canvas)
    expect(surviving.bindings[second]).toEqual({ ...canvas, viewSlot: 1 })
    const restored = deserializePaneState(serializePaneState(surviving), isPaneTarget)
    expect(reconcileBindings(restored, () => true)).toEqual(surviving)
    const reopened = bindBeside(restored, second, canvas, noUnsent, bounds, false)!
    expect(reopened.bindings[reopened.focusedPaneId]).toEqual({ ...canvas, viewSlot: 0 })
    expect(reopened.bindings[second]).toEqual(surviving.bindings[second])
  })

  it('reuses the exact freed Canvas view slot without changing other open views', () => {
    const initial = initialPaneState<PaneTarget>()
    const first = initial.focusedPaneId
    const two = splitPane(initial, first, 'X')
    const second = two.focusedPaneId
    const three = splitPane(two, second, 'Y')
    const third = three.focusedPaneId
    const primary = bindPane(three, first, canvas)
    const secondary = bindPane(primary, second, canvas)
    const tertiary = bindPane(secondary, third, canvas)
    expect(tertiary.bindings).toMatchObject({
      [first]: { ...canvas, viewSlot: 0 },
      [second]: { ...canvas, viewSlot: 1 },
      [third]: { ...canvas, viewSlot: 2 },
    })

    const restored = deserializePaneState(serializePaneState(tertiary), isPaneTarget)
    expect(reconcileBindings(restored, () => true)).toEqual(tertiary)
    const closed = closePane(restored, second)
    const reopened = bindBeside(closed, third, canvas, noUnsent, bounds, false)!
    expect(reopened.focusedPaneId).not.toBe(second)
    expect(reopened.bindings[third]).toEqual({ ...canvas, viewSlot: 2 })
    expect(reopened.bindings[reopened.focusedPaneId]).toEqual({ ...canvas, viewSlot: 1 })
  })

  it('requires an integer Canvas slot in the pane range', () => {
    expect(isPaneTarget(canvas)).toBe(false)
    expect(isPaneTarget({ ...canvas, viewSlot: 0 })).toBe(true)
    expect(isPaneTarget({ ...canvas, viewSlot: MAX_PANES - 1 })).toBe(true)
    for (const viewSlot of [-1, MAX_PANES, 1.5, '1']) {
      expect(isPaneTarget({ ...canvas, viewSlot })).toBe(false)
    }
  })

  it('opens beside in a clean draft before splitting; preserves an unsent draft', () => {
    const { state, first, second } = twoPanes()
    const source = bindPane(state, first, thread)
    expect(bindBeside(source, first, canvas, noUnsent, bounds, false)?.focusedPaneId).toBe(second)
    const next = bindBeside(source, first, canvas, id => id === second, bounds, false)!
    expect(next.focusedPaneId).not.toBe(second)
    expect(next.bindings[next.focusedPaneId]).toEqual({ ...canvas, viewSlot: 0 })
    expect(next.bindings[second]).toBe(source.bindings[second])
    expect(bindBeside(source, first, canvas, () => true, { ...bounds, height: 300 }, false)).toBeUndefined()
  })

  it('replaces a removed binding with a distinct draft, keeping layout and other drafts', () => {
    const { state, first, second } = twoPanes()
    const bound = bindPane(state, first, canvas)
    const next = reconcileBindings(bound, target => target.kind !== 'canvas')
    expect(next.layout).toBe(state.layout)
    expect(next.bindings[first]).toMatchObject({ kind: 'draft' })
    expect(next.bindings[first]).not.toEqual(state.bindings[first])
    expect(next.bindings[second]).toBe(state.bindings[second])
  })
})
