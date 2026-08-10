import { describe, expect, it } from 'vitest'
import { extractPositionUpdates } from '@/features/canvas/node-position-changes'

describe('extractPositionUpdates', () => {
  it('keeps local drag state and exposes the drag-stop boundary', () => {
    expect(extractPositionUpdates([
      { type: 'position', id: '2', position: { x: 10, y: 20 }, dragging: true },
      { type: 'position', id: 'group:3', position: { x: 30, y: 40 }, dragging: false },
      { type: 'select', id: '2' },
    ])).toEqual([
      { id: '2', x: 10, y: 20, dragging: true },
      { id: 'group:3', x: 30, y: 40, dragging: false },
    ])
  })

  it('ignores incomplete position changes', () => {
    expect(extractPositionUpdates([
      { type: 'position', position: { x: 1, y: 2 } },
      { type: 'position', id: '2', position: null },
    ])).toEqual([])
  })
})
