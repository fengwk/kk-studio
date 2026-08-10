import type { CanvasTransformDTO } from '@/shared/api/contracts/studio'
import type { StageMetrics } from '@/features/canvas/types'
import type { StoredCanvasViewport } from '@/features/canvas/viewport-storage'

export interface GenerationPanelPosition {
  left: number
  top: number
  placement: 'above' | 'below'
}

export function generationPanelPosition({
  node,
  viewport,
  stage,
  panel,
  gap = 12,
  padding = 12,
}: {
  node: CanvasTransformDTO
  viewport: StoredCanvasViewport
  stage: StageMetrics
  panel: { width: number; height: number }
  gap?: number
  padding?: number
}): GenerationPanelPosition {
  const nodeLeft = viewport.x + node.x * viewport.zoom
  const nodeTop = viewport.y + node.y * viewport.zoom
  const nodeWidth = node.width * viewport.zoom
  const nodeHeight = node.height * viewport.zoom
  const usableBottom = Math.max(
    padding + panel.height,
    Math.min(stage.height - padding, stage.dockTop - gap),
  )
  const belowTop = nodeTop + nodeHeight + gap
  const placement = belowTop + panel.height <= usableBottom ? 'below' : 'above'
  const desiredTop = placement === 'below'
    ? belowTop
    : nodeTop - gap - panel.height
  const maxTop = Math.max(padding, usableBottom - panel.height)
  const maxLeft = Math.max(padding, stage.width - padding - panel.width)
  return {
    left: clamp(nodeLeft + nodeWidth / 2 - panel.width / 2, padding, maxLeft),
    top: clamp(desiredTop, padding, maxTop),
    placement,
  }
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}
