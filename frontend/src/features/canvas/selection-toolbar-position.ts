import type { StageMetrics } from '@/features/canvas/types'
import type { StoredCanvasViewport } from '@/features/canvas/viewport-storage'

/**
 * 选区工具条定位。
 * 纯函数：把选中 flow nodes 的 world bounds 投影到 screen space，
 * 就近置于选区上方，空间不足时落到下方，左右始终 clamp 在 stage 内。
 */

export interface SelectionFlowNode {
  position: { x: number; y: number }
  measured?: { width?: number | null; height?: number | null } | null
  style?: { width?: number | string | null; height?: number | string | null } | null
}

export interface CanvasWorldBounds {
  x: number
  y: number
  width: number
  height: number
}

export interface SelectionToolbarPosition {
  left: number
  top: number
  placement: 'above' | 'below'
}

export const DEFAULT_SELECTION_TOOLBAR_SIZE = { width: 360, height: 40 }

export function selectionWorldBounds(nodes: SelectionFlowNode[]): CanvasWorldBounds | null {
  if (nodes.length === 0) {
    return null
  }
  let minX = Number.POSITIVE_INFINITY
  let minY = Number.POSITIVE_INFINITY
  let maxX = Number.NEGATIVE_INFINITY
  let maxY = Number.NEGATIVE_INFINITY
  for (const node of nodes) {
    const width = finiteNumber(node.measured?.width) ?? finiteNumber(node.style?.width) ?? 320
    const height = finiteNumber(node.measured?.height) ?? finiteNumber(node.style?.height) ?? 260
    minX = Math.min(minX, node.position.x)
    minY = Math.min(minY, node.position.y)
    maxX = Math.max(maxX, node.position.x + width)
    maxY = Math.max(maxY, node.position.y + height)
  }
  return {
    x: minX,
    y: minY,
    width: maxX - minX,
    height: maxY - minY,
  }
}

export function selectionToolbarPosition({
  bounds,
  viewport,
  stage,
  toolbar = DEFAULT_SELECTION_TOOLBAR_SIZE,
  gap = 12,
  padding = 12,
  preferBelow = false,
}: {
  bounds: CanvasWorldBounds
  viewport: StoredCanvasViewport
  stage: Pick<StageMetrics, 'width' | 'height'>
  toolbar?: { width: number; height: number }
  gap?: number
  padding?: number
  preferBelow?: boolean
}): SelectionToolbarPosition {
  const nodeLeft = viewport.x + bounds.x * viewport.zoom
  const nodeTop = viewport.y + bounds.y * viewport.zoom
  const nodeWidth = bounds.width * viewport.zoom
  const nodeHeight = bounds.height * viewport.zoom
  const centerX = nodeLeft + nodeWidth / 2
  const topEdge = nodeTop
  const bottomEdge = nodeTop + nodeHeight
  const maxLeft = Math.max(padding, stage.width - padding - toolbar.width)
  const maxTop = Math.max(padding, stage.height - padding - toolbar.height)
  const aboveTop = topEdge - gap - toolbar.height
  const belowTop = bottomEdge + gap
  if (!preferBelow && aboveTop >= padding) {
    return {
      left: clamp(centerX - toolbar.width / 2, padding, maxLeft),
      top: aboveTop,
      placement: 'above',
    }
  }
  if (belowTop <= maxTop) {
    return {
      left: clamp(centerX - toolbar.width / 2, padding, maxLeft),
      top: belowTop,
      placement: 'below',
    }
  }
  if (aboveTop >= padding) {
    return {
      left: clamp(centerX - toolbar.width / 2, padding, maxLeft),
      top: aboveTop,
      placement: 'above',
    }
  }
  return {
    left: clamp(centerX - toolbar.width / 2, padding, maxLeft),
    top: clamp(belowTop, padding, maxTop),
    placement: 'below',
  }
}

function finiteNumber(value: number | string | null | undefined): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}
