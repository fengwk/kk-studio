import { describe, expect, it } from 'vitest'
import { generationPanelPosition } from '@/features/canvas/generation-panel-position'

describe('generationPanelPosition', () => {
  it('anchors below a world-space node after applying viewport pan and zoom', () => {
    // Exact screen coordinates prove the overlay follows React Flow transforms.
    expect(generationPanelPosition({
      node: { x: 500, y: 80, width: 320, height: 260 },
      viewport: { x: 40, y: 20, zoom: 0.5 },
      stage: { width: 1000, height: 800, dockTop: 720 },
      panel: { width: 400, height: 140 },
    })).toEqual({
      left: 170,
      top: 202,
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
      left: 110,
      top: 228,
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
