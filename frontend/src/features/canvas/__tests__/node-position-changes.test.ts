import { describe, expect, it } from 'vitest'
import { extractPositionUpdates } from '@/features/canvas/node-position-changes'
import { canvasReducer, createInitialCanvasState } from '@/features/canvas/reducer'

describe('controlled node position contract', () => {
  it('extracts multi-node position updates without RF domain types', () => {
    // Stage integration: RF onNodesChange -> extractPositionUpdates -> move-nodes.
    const updates = extractPositionUpdates([
      { type: 'select', id: 'web' },
      { type: 'position', id: 'web', position: { x: 120, y: 180 } },
      { type: 'position', id: 'image', position: { x: 300, y: 190 } },
      { type: 'dimensions', id: 'web' },
      { type: 'position', id: 'file', position: null },
    ])
    expect(updates).toEqual([
      { id: 'web', x: 120, y: 180 },
      { id: 'image', x: 300, y: 190 },
    ])
  })

  it('applies multi-select drag updates into domain coordinates and skips no-ops', () => {
    let state = createInitialCanvasState()
    const updates = extractPositionUpdates([
      { type: 'position', id: 'web', position: { x: 200, y: 210 } },
      { type: 'position', id: 'image', position: { x: 400, y: 220 } },
    ])
    state = canvasReducer(state, { type: 'move-nodes', updates })
    expect(state.nodes.find((node) => node.id === 'web')).toMatchObject({ x: 200, y: 210 })
    expect(state.nodes.find((node) => node.id === 'image')).toMatchObject({ x: 400, y: 220 })
    expect(state.saveState).toBe('saving')

    const unchanged = canvasReducer(state, { type: 'move-nodes', updates })
    expect(unchanged).toBe(state)
  })
})
