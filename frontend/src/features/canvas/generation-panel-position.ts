import type { CanvasTransformDTO } from '@/shared/api/contracts/studio'
import type { StageMetrics } from '@/features/canvas/types'
import type { StoredCanvasViewport } from '@/features/canvas/viewport-storage'

export interface GenerationPanelPosition {
  left: number
  top: number
  placement: 'right' | 'below' | 'above' | 'left'
}

/**
 * Function 面板定位。
 * 桌面优先选择能完整容纳的 right，其次 below / above / left；
 * 全部无法完整容纳时选择可用空间最大的一侧并 clamp，始终避让 Agent Dock。
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
  const nodeWidth = node.width * viewport.zoom
  const nodeHeight = node.height * viewport.zoom
  const nodeRight = nodeLeft + nodeWidth
  const nodeBottom = nodeTop + nodeHeight
  const usableBottom = Math.max(
    padding + panel.height,
    Math.min(stage.height - padding, stage.dockTop - gap),
  )
  const minTop = padding
  const maxTop = Math.max(minTop, usableBottom - panel.height)
  const minLeft = padding
  const maxLeft = Math.max(minLeft, stage.width - padding - panel.width)

  const fitsRight = (
    nodeRight + gap + panel.width <= stage.width - padding
    && nodeTop >= minTop
    && nodeTop + panel.height <= usableBottom
  )
  const fitsBelow = nodeBottom + gap + panel.height <= usableBottom
  const fitsAbove = nodeTop - gap - panel.height >= minTop
  const fitsLeft = nodeLeft - gap - panel.width >= minLeft

  let placement: GenerationPanelPosition['placement']
  if (fitsRight) {
    placement = 'right'
  } else if (fitsBelow) {
    placement = 'below'
  } else if (fitsAbove) {
    placement = 'above'
  } else {
    const area = {
      right: Math.max(0, stage.width - padding - nodeRight - gap),
      below: Math.max(0, usableBottom - nodeBottom - gap),
      above: Math.max(0, nodeTop - gap - padding),
      left: Math.max(0, nodeLeft - gap - padding),
    }
    const comfortableHeight = Math.min(panel.height, 100)
    if (Math.max(area.above, area.below) >= comfortableHeight) {
      placement = area.above >= area.below ? 'above' : 'below'
    } else if (fitsLeft) {
      placement = 'left'
    } else {
      placement = (['right', 'below', 'above', 'left'] as const).reduce(
        (best, candidate) => area[candidate] > area[best] ? candidate : best,
        'right' as GenerationPanelPosition['placement'],
      )
    }
  }

  let left: number
  let top: number
  if (placement === 'right') {
    left = nodeRight + gap
    top = nodeTop
  } else if (placement === 'left') {
    left = nodeLeft - gap - panel.width
    top = nodeTop
  } else if (placement === 'below') {
    left = nodeLeft + nodeWidth / 2 - panel.width / 2
    top = nodeBottom + gap
  } else {
    left = nodeLeft + nodeWidth / 2 - panel.width / 2
    top = nodeTop - gap - panel.height
  }
  return {
    left: clamp(left, minLeft, maxLeft),
    top: clamp(top, minTop, maxTop),
    placement,
  }
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}
