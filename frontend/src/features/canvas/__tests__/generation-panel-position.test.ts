import { describe, expect, it } from 'vitest'
import { generationPanelPosition } from '@/features/canvas/generation-panel-position'

describe('generationPanelPosition', () => {
  it('places the workbench directly below the node when space is available', () => {
    // Exact screen coordinates prove the overlay follows React Flow transforms.
    expect(generationPanelPosition({
      node: { x: 500, y: 80, width: 320, height: 260 },
      viewport: { x: 40, y: 20, zoom: 0.5 },
      stage: { width: 1000, height: 800, dockTop: 720 },
      panel: { width: 400, height: 140 },
    })).toEqual({
      left: 290,
      top: 202,
      placement: 'below',
    })
  })

  it('places below when right is too narrow but the dock still leaves room', () => {
    expect(generationPanelPosition({
      node: { x: 200, y: 80, width: 320, height: 260 },
      viewport: { x: 0, y: 0, zoom: 1 },
      stage: { width: 1000, height: 800, dockTop: 720 },
      panel: { width: 560, height: 180 },
    })).toEqual({
      left: 200,
      top: 352,
      placement: 'below',
    })
  })

  it('moves above when the dock leaves insufficient space below', () => {
    // Dock-aware placement prevents the node panel from covering the Agent Dock.
    expect(generationPanelPosition({
      node: { x: 200, y: 420, width: 320, height: 260 },
      viewport: { x: 0, y: 0, zoom: 1 },
      stage: { width: 1000, height: 760, dockTop: 650 },
      panel: { width: 500, height: 180 },
    })).toEqual({
      left: 200,
      top: 228,
      placement: 'above',
    })
  })

  it('falls back above for a tall node without usable space below', () => {
    expect(generationPanelPosition({
      node: { x: 900, y: 40, width: 320, height: 600 },
      viewport: { x: 0, y: 0, zoom: 1 },
      stage: { width: 1600, height: 700, dockTop: 650 },
      panel: { width: 560, height: 180 },
    })).toEqual({
      left: 900,
      top: 12,
      placement: 'above',
    })
  })

  it('clamps both axes when no placement fits completely', () => {
    // The fallback picks the side with the most available space (above) and clamps the panel visible.
    expect(generationPanelPosition({
      node: { x: 100, y: 300, width: 320, height: 260 },
      viewport: { x: 0, y: 0, zoom: 1 },
      stage: { width: 400, height: 600, dockTop: 500 },
      panel: { width: 560, height: 300 },
    })).toEqual({
      left: 12,
      top: 12,
      placement: 'above',
    })
  })

  it('uses the larger vertical space instead of clamping a marginal below placement over the node', () => {
    // Regression geometry from OpenCLI at 1266px: below is comfortable but much smaller than above.
    expect(generationPanelPosition({
      node: { x: 875, y: 204, width: 296, height: 241 },
      viewport: { x: 0, y: 0, zoom: 1 },
      stage: { width: 1266, height: 649, dockTop: 581 },
      panel: { width: 560, height: 199 },
      gap: 8,
      padding: 4,
    })).toEqual({
      left: 702,
      top: 4,
      placement: 'above',
    })
  })

  it('clamps the overlay inside the stage when the node is off screen', () => {
    // Both axes remain reachable even while the selected node is partially outside the viewport.
    expect(generationPanelPosition({
      node: { x: -500, y: -300, width: 320, height: 260 },
      viewport: { x: -20, y: -10, zoom: 2 },
      stage: { width: 600, height: 500, dockTop: 450 },
      panel: { width: 560, height: 180 },
    })).toEqual({
      left: 12,
      top: 12,
      placement: 'below',
    })
  })
})
