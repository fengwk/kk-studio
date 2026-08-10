import { describe, expect, it } from 'vitest'
import {
  selectionToolbarPosition,
  selectionWorldBounds,
} from '@/features/canvas/selection-toolbar-position'

describe('selectionWorldBounds', () => {
  it('unions the world bounds of the selected flow nodes', () => {
    expect(selectionWorldBounds([
      { position: { x: 100, y: 200 }, measured: { width: 320, height: 260 } },
      { position: { x: 500, y: 120 }, measured: { width: 220, height: 160 } },
    ])).toEqual({
      x: 100,
      y: 120,
      width: 620,
      height: 340,
    })
  })

  it('falls back to style sizes and default node sizes', () => {
    expect(selectionWorldBounds([
      { position: { x: 10, y: 20 }, style: { width: 200, height: 100 } },
      { position: { x: 260, y: 20 } },
    ])).toEqual({
      x: 10,
      y: 20,
      width: 570,
      height: 260,
    })
  })

  it('returns null for an empty selection', () => {
    expect(selectionWorldBounds([])).toBeNull()
  })
})

describe('selectionToolbarPosition', () => {
  const viewport = { x: 0, y: 0, zoom: 1 }
  const stage = { width: 1200, height: 800 }

  it('places the toolbar above the selection, centered on its world bounds', () => {
    // Exact screen coordinates prove the overlay follows React Flow world transforms.
    expect(selectionToolbarPosition({
      bounds: { x: 100, y: 100, width: 300, height: 200 },
      viewport,
      stage,
      toolbar: { width: 200, height: 40 },
    })).toEqual({
      left: 150,
      top: 48,
      placement: 'above',
    })
  })

  it('falls back below the selection when there is no room above', () => {
    expect(selectionToolbarPosition({
      bounds: { x: 100, y: 20, width: 300, height: 80 },
      viewport,
      stage,
      toolbar: { width: 200, height: 40 },
    })).toEqual({
      left: 150,
      top: 112,
      placement: 'below',
    })
  })

  it('uses the lower anchor for a selected Function when requested', () => {
    expect(selectionToolbarPosition({
      bounds: { x: 500, y: 200, width: 320, height: 260 },
      viewport,
      stage: { width: 1200, height: 720 },
      toolbar: { width: 360, height: 40 },
      preferBelow: true,
    })).toEqual({
      left: 480,
      top: 472,
      placement: 'below',
    })
  })

  it('clamps left and right inside the stage for off-screen selections', () => {
    expect(selectionToolbarPosition({
      bounds: { x: -500, y: 100, width: 300, height: 200 },
      viewport,
      stage: { width: 600, height: 800 },
      toolbar: { width: 200, height: 40 },
    })).toEqual({
      left: 12,
      top: 48,
      placement: 'above',
    })
    expect(selectionToolbarPosition({
      bounds: { x: 800, y: 100, width: 300, height: 200 },
      viewport,
      stage: { width: 600, height: 800 },
      toolbar: { width: 200, height: 40 },
    })).toEqual({
      left: 388,
      top: 48,
      placement: 'above',
    })
  })

  it('applies the viewport pan and zoom before projecting screen space', () => {
    expect(selectionToolbarPosition({
      bounds: { x: 500, y: 80, width: 320, height: 260 },
      viewport: { x: 40, y: 20, zoom: 0.5 },
      stage: { width: 1000, height: 800 },
      toolbar: { width: 200, height: 40 },
    })).toEqual({
      left: 270,
      top: 202,
      placement: 'below',
    })
  })
})
