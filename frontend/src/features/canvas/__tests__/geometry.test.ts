import { describe, expect, it } from 'vitest'
import { createInitialNodes, MAX_ZOOM, MIN_ZOOM } from '@/features/canvas/data'
import {
  clampZoom,
  computeGenerationPanelPosition,
  computeSelectionToolbarPosition,
  findOpenCanvasPosition,
  preferredCreatePosition,
  revealNodeViewportShift,
  selectionBounds,
  viewportsEqual,
  zoomAtPoint,
} from '@/features/canvas/geometry'

describe('canvas geometry', () => {
  it('clamps zoom to design contract and zooms around a stage point', () => {
    expect(MIN_ZOOM).toBe(0.25)
    expect(MAX_ZOOM).toBe(1.45)
    expect(clampZoom(0.01)).toBe(0.25)
    expect(clampZoom(8)).toBe(1.45)
    const next = zoomAtPoint({ x: 80, y: 20, scale: 0.6 }, 200, 100, 0, 0, 1.2)
    expect(next.scale).toBe(1.2)
    expect(viewportsEqual(next, next)).toBe(true)
  })

  it('finds non-overlapping placement and keeps generators above the dock', () => {
    const nodes = createInitialNodes()
    const position = findOpenCanvasPosition(nodes, { width: 300, height: 196 }, { x: 100, y: 100 })
    expect(position.x).toBeGreaterThan(0)
    const preferred = preferredCreatePosition(
      { x: 80, y: 20, scale: 0.6 },
      { width: 1000, height: 700, dockTop: 600 },
      { width: 240, height: 200 },
    )
    expect(Number.isFinite(preferred.x)).toBe(true)
    const shifted = revealNodeViewportShift(
      nodes[1],
      { x: -400, y: -300, scale: 1 },
      { width: 800, height: 600, dockTop: 500 },
    )
    expect(shifted.x).not.toBe(-400)
  })

  it('positions generation workbench and selection toolbar with explicit left/top', () => {
    const node = createInitialNodes()[1]
    const panel = computeGenerationPanelPosition({
      node,
      viewport: { x: 80, y: 20, scale: 0.6 },
      stage: { width: 1000, height: 700, dockTop: 620 },
      expanded: false,
      naturalPanelHeight: 280,
    })
    expect(panel.left).toBeGreaterThanOrEqual(12)
    expect(panel.top).toBeGreaterThanOrEqual(12)

    const bounds = selectionBounds(createInitialNodes().slice(1, 3))
    expect(bounds).not.toBeNull()
    const toolbar = computeSelectionToolbarPosition({
      bounds: bounds!,
      viewport: { x: 80, y: 20, scale: 0.6 },
      stageWidth: 1000,
    })
    expect(typeof toolbar.left).toBe('number')
    expect(typeof toolbar.top).toBe('number')
    expect(toolbar.left).toBeGreaterThan(0)
  })
})
