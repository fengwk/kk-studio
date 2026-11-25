import { afterEach, describe, expect, it, vi } from 'vitest'
import { bindTreeDrag } from './tree-drag'

function fixture() {
  const tree = document.createElement('div')
  const row = document.createElement('div')
  row.dataset.treeRow = 'row'
  row.dataset.treeMovable = ''
  tree.append(row)
  const move = vi.fn()
  const dispose = bindTreeDrag(tree, { resolve: () => undefined, move })
  const dispatch = (type: string, x: number) => {
    const event = new Event(type, { bubbles: true, cancelable: true })
    Object.defineProperty(event, 'touches', { value: [{ identifier: 1, clientX: x, clientY: 0 }] })
    row.dispatchEvent(event)
    return event
  }
  return { row, move, dispose, dispatch }
}

afterEach(() => vi.useRealTimers())

describe('directory touch drag hold', () => {
  it('does not intercept an ordinary swipe before the hold threshold', () => {
    vi.useFakeTimers()
    const { row, move, dispose, dispatch } = fixture()
    dispatch('touchstart', 0)
    vi.advanceTimersByTime(300)
    expect(dispatch('touchmove', 9).defaultPrevented).toBe(false)
    vi.advanceTimersByTime(100)
    expect(row.dataset.treeDragging).toBeUndefined()
    expect(move).not.toHaveBeenCalled()
    dispose()
  })

  it('starts only after a sustained hold and clears the marker on cancel or disposal', () => {
    vi.useFakeTimers()
    const { row, move, dispose, dispatch } = fixture()
    dispatch('touchstart', 0)
    vi.advanceTimersByTime(379)
    expect(dispatch('touchmove', 8).defaultPrevented).toBe(false)
    expect(row.dataset.treeDragging).toBeUndefined()
    vi.advanceTimersByTime(1)
    expect(row.dataset.treeDragging).toBe('')
    dispatch('touchcancel', 8)
    expect(row.dataset.treeDragging).toBeUndefined()
    dispatch('touchstart', 0)
    dispose()
    vi.advanceTimersByTime(380)
    expect(row.dataset.treeDragging).toBeUndefined()
    expect(move).not.toHaveBeenCalled()
  })
})
