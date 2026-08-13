import type {
  CanvasTransformDTO,
} from '@/shared/api/contracts/studio'
import { resourceGridLayout } from '@/features/canvas/resource-grid-layout'

export const CANVAS_NODE_HEADER_HEIGHT = 20
export const CANVAS_NODE_HEADER_GAP = 6
export const CANVAS_RESOURCE_GRID_GAP = 4
export const CANVAS_MULTI_RESOURCE_TILE_WIDTH = 220
export const CANVAS_MULTI_RESOURCE_TILE_HEIGHT = 160
export const CANVAS_SINGLE_RESOURCE_NODE_SIZE = {
  width: 320,
  height: CANVAS_NODE_HEADER_HEIGHT + CANVAS_NODE_HEADER_GAP + 220,
} as const

interface SizableCanvasResource {
  kind: string
  width?: number | null
  height?: number | null
}

interface SizableResourceNode {
  transform: Pick<CanvasTransformDTO, 'width' | 'height'>
  resources: SizableCanvasResource[]
}

export function resourceNodeSize(
  node: SizableResourceNode,
): Pick<CanvasTransformDTO, 'width' | 'height'> {
  if (node.resources.length <= 1) {
    const body = singleResourceBodySize(node.resources[0])
    return {
      width: body.width,
      height: CANVAS_NODE_HEADER_HEIGHT + CANVAS_NODE_HEADER_GAP + body.height,
    }
  }
  const layout = resourceGridLayout(node.resources.length)
  return {
    width: (
      layout.cols * CANVAS_MULTI_RESOURCE_TILE_WIDTH
      + (layout.cols - 1) * CANVAS_RESOURCE_GRID_GAP
    ),
    height: (
      CANVAS_NODE_HEADER_HEIGHT
      + CANVAS_NODE_HEADER_GAP
      + layout.rows * CANVAS_MULTI_RESOURCE_TILE_HEIGHT
      + (layout.rows - 1) * CANVAS_RESOURCE_GRID_GAP
    ),
  }
}

function singleResourceBodySize(
  resource: SizableCanvasResource | undefined,
): Pick<CanvasTransformDTO, 'width' | 'height'> {
  if (resource?.kind === 'AUDIO') {
    return { width: 320, height: 112 }
  }
  if (resource?.kind === 'IMAGE' || resource?.kind === 'VIDEO') {
    const width = positiveNumber(resource.width)
    const height = positiveNumber(resource.height)
    if (width !== null && height !== null) {
      const ratio = width / height
      return ratio >= 1
        ? { width: 320, height: Math.max(180, Math.round(320 / ratio)) }
        : { width: Math.max(180, Math.round(320 * ratio)), height: 320 }
    }
  }
  return { width: 320, height: 220 }
}

function positiveNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : null
}
