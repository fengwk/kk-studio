import { describe, expect, it } from 'vitest'
import { preserveCanvasWorldCenter } from '@/features/canvas/viewport-framing'

describe('canvas viewport framing', () => {
  it('preserves the same world center when the Agent panel changes canvas width', () => {
    // React Flow translation is measured in screen pixels, so zoom must not scale the width delta.
    expect(preserveCanvasWorldCenter(
      { x: 40, y: 12, zoom: 0.5 },
      1200,
      840,
    )).toEqual({
      x: -140,
      y: 12,
      zoom: 0.5,
    })
    expect(preserveCanvasWorldCenter(
      { x: -140, y: 12, zoom: 0.5 },
      840,
      1200,
    )).toEqual({
      x: 40,
      y: 12,
      zoom: 0.5,
    })
  })
})
