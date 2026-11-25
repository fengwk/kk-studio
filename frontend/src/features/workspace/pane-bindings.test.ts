import { describe, expect, it } from 'vitest'
import { bindBeside, bindPane, canOpenDirectly, reconcileBindings } from './pane-bindings'
import type { PaneTarget } from './pane-target'
import { initialPaneState, splitPane } from './panes/model'

const thread: PaneTarget = { kind: 'thread', threadId: 'thread' }
const canvas: PaneTarget = { kind: 'canvas', canvasId: 'canvas' }
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
    for (const target of [thread, ide, canvas]) {
      const moved = bindPane(bindPane(state, first, target), second, target)
      expect(moved.bindings[second]).toBe(target)
      expect(moved.bindings[first]?.kind).toBe(target.kind === 'canvas' ? 'canvas' : 'draft')
      expect(moved.focusedPaneId).toBe(second)
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
