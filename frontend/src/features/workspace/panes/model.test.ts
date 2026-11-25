import { describe, expect, it } from 'vitest'
import { canSplit, closePane, initialPaneState, minimumSize, needsSinglePane, paneIds, paneLetter, projectLayout, resizeSplit, splitPane, swapPanes, type Split } from './model'
import { deserializePaneState, serializePaneState } from './usePaneState'

const isText = (value: unknown): value is string => typeof value === 'string'
const box = (width: number, height: number) => ({ x: 0, y: 0, width, height })

describe('pane layout', () => {
  it('only adds one unbound pane and promotes a closed pane’s sibling subtree', () => {
    const initial = initialPaneState<string>(), a = initial.focusedPaneId
    initial.bindings[a] = 'resource'
    const second = splitPane(initial, a, 'X'), b = second.focusedPaneId
    const third = splitPane(second, b, 'Y'), c = third.focusedPaneId
    expect(paneIds(third.layout)).toEqual([a, b, c])
    expect(third.bindings).toBe(initial.bindings)
    expect(third.layout).toMatchObject({ ratio: 0.5, first: initial.layout })
    const closed = closePane({ ...third, focusedPaneId: a }, a)
    expect(closed.layout).toBe((third.layout as Split).second)
    expect(closed.focusedPaneId).toBe(b)
    expect(closed.bindings).toEqual({})
    expect(closePane(third, a).focusedPaneId).toBe(c)
    expect(closePane(initial, a)).toBe(initial)
  })

  it('caps logical splits at eight and swaps locations without moving focus, bindings or ratios', () => {
    let state = initialPaneState()
    for (let i = 1; i < 8; i++) state = splitPane(state, state.focusedPaneId, 'Y')
    const ids = paneIds(state.layout)
    expect(ids.map((_, i) => paneLetter(i)).join('')).toBe('ABCDEFGH')
    expect(splitPane(state, state.focusedPaneId, 'X')).toBe(state)
    const swapped = swapPanes(state, ids[0]!, ids[7]!)
    expect(paneIds(swapped.layout)).toEqual([ids[7], ...ids.slice(1, 7), ids[0]])
    expect(swapped.focusedPaneId).toBe(ids[7])
    expect(swapped.bindings).toBe(state.bindings)
    expect((swapped.layout as Split).ratio).toBe((state.layout as Split).ratio)
  })

  it('uses recursive minima for projection and only writes clamped ratios on explicit resize', () => {
    let state = initialPaneState()
    const a = state.focusedPaneId
    state = splitPane(state, a, 'X')
    state = splitPane(state, a, 'X')
    state = splitPane(state, a, 'Y')
    expect(minimumSize(state.layout)).toEqual({ width: 962, height: 481 })
    expect(canSplit(state.layout, 'X', box(640, 500))).toBe(false)
    expect(canSplit(state.layout, 'Y', box(319, 481))).toBe(false)
    expect(canSplit(state.layout, 'Y', box(320, 481))).toBe(true)
    expect(needsSinglePane(state.layout, box(961, 481))).toBe(true)
    expect(needsSinglePane(state.layout, box(962, 480))).toBe(true)
    expect(needsSinglePane(state.layout, box(962, 481))).toBe(false)
    const root = projectLayout(state.layout, box(1001, 501)).splits[0]!
    expect(root.line).toEqual({ x: 641, y: 0, width: 1, height: 501 })
    expect(root.ratio).toBe(0.641)
    expect((state.layout as Split).ratio).toBe(0.5)
    expect((resizeSplit(state, root, 0.5).layout as Split).ratio).toBe(0.641)
    expect((resizeSplit(state, root, 1).layout as Split).ratio).toBe(0.68)
  })

  it('restores valid layout and focus, drops invalid bindings and rejects damaged trees', () => {
    const initial = initialPaneState<string>(), a = initial.focusedPaneId
    const state = splitPane(initial, a, 'X')
    state.bindings[a] = 'resource'
    const raw = serializePaneState(state)
    expect(deserializePaneState(raw, isText)).toEqual(state)
    const stored = JSON.parse(raw)
    stored.bindings[a] = { unknown: true }
    expect(deserializePaneState(JSON.stringify(stored), isText)).toEqual({ ...state, bindings: {} })
    for (const invalid of ['{', JSON.stringify({ ...stored, version: 2 }),
      JSON.stringify({ ...stored, focusedPaneId: 'missing' }),
      JSON.stringify({ ...stored, layout: { ...stored.layout, second: stored.layout.first } }),
      JSON.stringify({ ...stored, layout: { ...stored.layout, ratio: 2 } })]) {
      const restored = deserializePaneState(invalid, isText)
      expect(paneIds(restored.layout)).toEqual([restored.focusedPaneId])
      expect(restored.bindings).toEqual({})
    }
  })
})
