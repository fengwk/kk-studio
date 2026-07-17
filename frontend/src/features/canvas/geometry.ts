import { MAX_ZOOM, MIN_ZOOM } from '@/features/canvas/data'
import type {
  CanvasNode,
  CanvasPoint,
  CanvasRect,
  CanvasSize,
  CanvasViewport,
  StageMetrics,
} from '@/features/canvas/types'

export function clampZoom(scale: number): number {
  return Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, scale))
}

export function viewportsEqual(a: CanvasViewport, b: CanvasViewport, epsilon = 0.001): boolean {
  return (
    Math.abs(a.x - b.x) <= epsilon
    && Math.abs(a.y - b.y) <= epsilon
    && Math.abs(a.scale - b.scale) <= epsilon
  )
}

export function selectionBounds(nodes: CanvasNode[]): CanvasRect | null {
  if (nodes.length === 0) {
    return null
  }
  let minX = Number.POSITIVE_INFINITY
  let minY = Number.POSITIVE_INFINITY
  let maxX = Number.NEGATIVE_INFINITY
  let maxY = Number.NEGATIVE_INFINITY
  for (const node of nodes) {
    minX = Math.min(minX, node.x)
    minY = Math.min(minY, node.y)
    maxX = Math.max(maxX, node.x + node.width)
    maxY = Math.max(maxY, node.y + node.height)
  }
  return {
    x: minX,
    y: minY,
    width: maxX - minX,
    height: maxY - minY,
  }
}

export function zoomAtPoint(
  viewport: CanvasViewport,
  clientX: number,
  clientY: number,
  stageLeft: number,
  stageTop: number,
  nextScale: number,
): CanvasViewport {
  const scale = clampZoom(nextScale)
  if (scale === viewport.scale) {
    return viewport
  }
  const pointX = clientX - stageLeft
  const pointY = clientY - stageTop
  const worldX = (pointX - viewport.x) / viewport.scale
  const worldY = (pointY - viewport.y) / viewport.scale
  return {
    scale,
    x: pointX - worldX * scale,
    y: pointY - worldY * scale,
  }
}

export function findOpenCanvasPosition(
  nodes: CanvasNode[],
  size: CanvasSize,
  preferredPosition: CanvasPoint | null = null,
  anchor: CanvasNode | null = null,
): CanvasPoint {
  const gap = 28
  const startX = preferredPosition
    ? preferredPosition.x
    : anchor
      ? anchor.x + anchor.width + 100
      : 1060
  const startY = preferredPosition
    ? preferredPosition.y
    : anchor
      ? anchor.y + anchor.height + 100
      : 380
  const rowsPerColumn = 4
  let candidate = { x: startX, y: startY }

  for (let attempt = 0; attempt < 60; attempt += 1) {
    const column = Math.floor(attempt / rowsPerColumn)
    const row = attempt % rowsPerColumn
    candidate = {
      x: startX + column * (size.width + 70),
      y: startY + row * (size.height + gap),
    }
    const blocked = nodes.some((node) => {
      if (node.type === 'frame') {
        return false
      }
      return (
        candidate.x < node.x + node.width + gap
        && candidate.x + size.width + gap > node.x
        && candidate.y < node.y + node.height + gap
        && candidate.y + size.height + gap > node.y
      )
    })
    if (!blocked) {
      return candidate
    }
  }
  return candidate
}

export function computeGenerationPanelPosition(args: {
  node: CanvasNode
  viewport: CanvasViewport
  stage: StageMetrics
  expanded: boolean
  naturalPanelHeight?: number
}): { left: number; top: number; maxHeight: number; placement: 'below' | 'above'; panelWidth: number } {
  const {
    node,
    viewport,
    stage,
    expanded,
    naturalPanelHeight = 330,
  } = args
  const scale = Number.isFinite(viewport.scale) && viewport.scale > 0 ? viewport.scale : 0.6
  const margin = 12
  const nodeGap = 12
  const toolbarClearance = 48
  const safeTop = margin
  const fallbackDockTop = Math.max(safeTop, stage.height - 88)
  const dockTop = Number.isFinite(stage.dockTop) && stage.dockTop > 0 ? stage.dockTop : fallbackDockTop
  const safeBottom = Math.max(safeTop, Math.min(stage.height - margin, dockTop - 12))
  const desiredPanelWidth = expanded ? 720 : 560
  const availableWidth = Math.max(0, stage.width - margin * 2)
  const panelWidth = availableWidth ? Math.min(desiredPanelWidth, availableWidth) : desiredPanelWidth
  const nodeLeft = viewport.x + node.x * scale
  const nodeTop = viewport.y + node.y * scale
  const nodeWidth = node.width * scale
  const nodeBottom = nodeTop + node.height * scale
  const belowTop = nodeBottom + nodeGap
  const aboveBottom = nodeTop - toolbarClearance
  const belowAvailable = Math.max(0, safeBottom - belowTop)
  const aboveAvailable = Math.max(0, aboveBottom - safeTop)
  const comfortableHeight = Math.min(naturalPanelHeight, 180)
  const placeBelow = belowAvailable >= comfortableHeight || belowAvailable >= aboveAvailable
  const placementAvailable = placeBelow ? belowAvailable : aboveAvailable
  const visiblePanelHeight = Math.max(80, Math.min(naturalPanelHeight, placementAvailable || naturalPanelHeight))
  const preferredTop = placeBelow ? belowTop : aboveBottom - visiblePanelHeight
  const maxLeft = Math.max(margin, stage.width - panelWidth - margin)
  const maxTop = Math.max(safeTop, safeBottom - visiblePanelHeight)
  const left = Math.max(margin, Math.min(maxLeft, nodeLeft + nodeWidth / 2 - panelWidth / 2))
  const top = Math.max(safeTop, Math.min(maxTop, preferredTop))
  return {
    left: Number.isFinite(left) ? left : margin,
    top: Number.isFinite(top) ? top : safeTop,
    maxHeight: visiblePanelHeight,
    placement: placeBelow ? 'below' : 'above',
    panelWidth,
  }
}

export function computeSelectionToolbarPosition(args: {
  bounds: CanvasRect
  viewport: CanvasViewport
  stageWidth: number
  toolbarWidth?: number
  toolbarHeight?: number
}): { left: number; top: number } {
  const {
    bounds,
    viewport,
    stageWidth,
    toolbarWidth = 280,
    toolbarHeight = 36,
  } = args
  const scale = viewport.scale > 0 ? viewport.scale : 0.6
  const centerX = viewport.x + (bounds.x + bounds.width / 2) * scale
  const topEdge = viewport.y + bounds.y * scale
  const margin = 8
  const left = Math.max(
    margin + toolbarWidth / 2,
    Math.min(stageWidth - margin - toolbarWidth / 2, centerX),
  )
  const top = Math.max(margin, topEdge - toolbarHeight - 12)
  return { left, top }
}

export function revealNodeViewportShift(
  node: CanvasNode,
  viewport: CanvasViewport,
  stage: StageMetrics,
): CanvasViewport {
  const scale = Number.isFinite(viewport.scale) && viewport.scale > 0 ? viewport.scale : 0.6
  const margin = 18
  const safeLeft = margin
  const safeTop = margin
  const dockTop = stage.dockTop > 0 ? stage.dockTop : stage.height - 96
  const safeRight = Math.max(safeLeft + 1, stage.width - margin)
  const safeBottom = Math.max(safeTop + 1, Math.min(stage.height - margin, dockTop - 12))
  const nodeLeft = viewport.x + node.x * scale
  const nodeTop = viewport.y + node.y * scale
  const nodeRight = nodeLeft + node.width * scale
  const nodeBottom = nodeTop + node.height * scale
  let shiftX = 0
  let shiftY = 0
  if (nodeRight > safeRight) {
    shiftX = safeRight - nodeRight
  }
  if (nodeLeft + shiftX < safeLeft) {
    shiftX += safeLeft - (nodeLeft + shiftX)
  }
  if (nodeBottom > safeBottom) {
    shiftY = safeBottom - nodeBottom
  }
  if (nodeTop + shiftY < safeTop) {
    shiftY += safeTop - (nodeTop + shiftY)
  }
  return {
    ...viewport,
    x: viewport.x + shiftX,
    y: viewport.y + shiftY,
  }
}

export function preferredCreatePosition(
  viewport: CanvasViewport,
  stage: StageMetrics,
  size: CanvasSize,
): CanvasPoint {
  const scale = Number.isFinite(viewport.scale) && viewport.scale > 0 ? viewport.scale : 0.6
  const dockTop = stage.dockTop > 0 ? stage.dockTop : stage.height - 96
  const safeHeight = Math.max(120, dockTop - 24)
  return {
    x: (stage.width / 2 - viewport.x) / scale - size.width / 2,
    y: (safeHeight / 2 - viewport.y) / scale - size.height / 2,
  }
}
