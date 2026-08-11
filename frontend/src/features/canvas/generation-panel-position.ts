import type { CanvasTransformDTO } from '@/shared/api/contracts/studio'
import type { StageMetrics } from '@/features/canvas/types'
import type { StoredCanvasViewport } from '@/features/canvas/viewport-storage'

export interface GenerationPanelPosition {
  left: number
  top: number
  placement: 'below' | 'above'
}

/**
 * Function 面板定位。
 * 工作台优先位于节点下方；空间不足时才回退到上方，并始终 clamp 在可用画布内。
 */
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
  const nodeHeight = node.height * viewport.zoom
  const nodeBottom = nodeTop + nodeHeight
  const usableBottom = Math.max(
    padding + panel.height,
    Math.min(stage.height - padding, stage.dockTop - gap),
  )
  const minTop = padding
  const maxTop = Math.max(minTop, usableBottom - panel.height)
  const minLeft = padding
  const maxLeft = Math.max(minLeft, stage.width - padding - panel.width)

  const belowTop = nodeBottom + gap
  const aboveTop = nodeTop - gap - panel.height
  const fitsBelow = belowTop >= minTop && belowTop + panel.height <= usableBottom
  const fitsAbove = nodeTop - gap - panel.height >= minTop
  const belowSpace = Math.max(0, usableBottom - nodeBottom - gap)
  const aboveSpace = Math.max(0, nodeTop - gap - padding)
  const placement: GenerationPanelPosition['placement'] = fitsBelow
    ? 'below'
    : fitsAbove || aboveSpace > belowSpace
      ? 'above'
      : 'below'
  return {
    left: clamp(nodeLeft, minLeft, maxLeft),
    top: clamp(placement === 'below' ? belowTop : aboveTop, minTop, maxTop),
    placement,
  }
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}
