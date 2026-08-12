import type {
  CanvasResourceKind,
  CanvasTransformDTO,
} from '@/shared/api/contracts/studio'

export const COMPACT_MEDIA_NODE_HEADER_HEIGHT = 24
export const COMPACT_MEDIA_NODE_SWITCHER_HEIGHT = 42

const MEDIA_LONG_EDGE = 320
const MEDIA_MIN_SHORT_EDGE = 180

interface SizableCanvasResource {
  kind: CanvasResourceKind
  width: number | null
  height: number | null
}

interface SizableResourceNode {
  transform: Pick<CanvasTransformDTO, 'width' | 'height'>
  resources: SizableCanvasResource[]
}

export function isCompactMediaNode(node: Pick<SizableResourceNode, 'resources'>): boolean {
  const kind = node.resources[0]?.kind
  return kind === 'IMAGE' || kind === 'VIDEO'
}

export function resourceNodeSize(
  node: SizableResourceNode,
): Pick<CanvasTransformDTO, 'width' | 'height'> {
  const resource = node.resources[0]
  if (!resource || !isCompactMediaNode(node)) {
    return { width: node.transform.width, height: node.transform.height }
  }
  const width = positiveNumber(resource.width)
  const height = positiveNumber(resource.height)
  if (width === null || height === null) {
    return { width: node.transform.width, height: node.transform.height }
  }

  const ratio = width / height
  const mediaWidth = ratio >= 1
    ? MEDIA_LONG_EDGE
    : Math.max(MEDIA_MIN_SHORT_EDGE, Math.round(MEDIA_LONG_EDGE * ratio))
  const mediaHeight = ratio >= 1
    ? Math.max(MEDIA_MIN_SHORT_EDGE, Math.round(MEDIA_LONG_EDGE / ratio))
    : MEDIA_LONG_EDGE
  return {
    width: mediaWidth,
    height: (
      COMPACT_MEDIA_NODE_HEADER_HEIGHT
      + mediaHeight
      + (node.resources.length > 1 ? COMPACT_MEDIA_NODE_SWITCHER_HEIGHT : 0)
    ),
  }
}

function positiveNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : null
}
