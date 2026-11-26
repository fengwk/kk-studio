import { describe, expect, it } from 'vitest'
import { bindBeside, bindPane, canOpenDirectly, reconcileBindings } from './pane-bindings'
import { isPaneTarget, type PaneTarget } from './pane-target'
import { deserializePaneState, serializePaneState } from './panes/usePaneState'
import { closePane, initialPaneState, MAX_PANES, paneIds, splitPane, swapPanes } from './panes/model'

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

  it('keeps one legacy Canvas scope and does not promote a surviving secondary view', () => {
    // Old layouts could store identical Canvas bindings; only the first inherits old data.
    const { state, first, second } = twoPanes()
    const legacy = { ...state, bindings: { [first]: canvas, [second]: canvas } }
    const corrected = reconcileBindings(deserializePaneState(serializePaneState(legacy), isPaneTarget), () => true)
    expect(corrected.bindings).toEqual({ [first]: canvas, [second]: { ...canvas, viewSlot: 1 } })

    const swapped = swapPanes(corrected, first, second)
    expect(reconcileBindings(swapped, () => true)).toBe(swapped)
    const closed = closePane(swapped, first)
    const surviving = bindPane(closed, second, canvas)
    expect(surviving.bindings[second]).toEqual({ ...canvas, viewSlot: 1 })
    const restored = deserializePaneState(serializePaneState(surviving), isPaneTarget)
    expect(reconcileBindings(restored, () => true)).toEqual(surviving)
    const reopened = bindBeside(restored, second, canvas, noUnsent, bounds, false)!
    expect(reopened.bindings[reopened.focusedPaneId]).toEqual(canvas)
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
      [first]: canvas,
      [second]: { ...canvas, viewSlot: 1 },
      [third]: { ...canvas, viewSlot: 2 },
    })

    const restored = deserializePaneState(serializePaneState(tertiary), isPaneTarget)
    const closed = closePane(restored, second)
    const reopened = bindBeside(closed, third, canvas, noUnsent, bounds, false)!
    expect(reopened.focusedPaneId).not.toBe(second)
    expect(reopened.bindings[third]).toEqual({ ...canvas, viewSlot: 2 })
    expect(reopened.bindings[reopened.focusedPaneId]).toEqual({ ...canvas, viewSlot: 1 })
  })

  it('validates Canvas slots within the additional-pane range', () => {
    expect(isPaneTarget({ ...canvas, viewSlot: 1 })).toBe(true)
    expect(isPaneTarget({ ...canvas, viewSlot: MAX_PANES - 1 })).toBe(true)
    for (const viewSlot of [0, MAX_PANES, 1.5, '1']) {
      expect(isPaneTarget({ ...canvas, viewSlot })).toBe(false)
    }
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
