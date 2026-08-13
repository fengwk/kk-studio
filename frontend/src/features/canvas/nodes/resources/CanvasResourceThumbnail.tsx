import type { Resource } from '@/features/canvas/domain'
import { useCanvasResourceUrl } from '@/features/canvas/useCanvasResourceUrl'

export function CanvasResourceThumbnail({ resource }: { resource: Resource }) {
  const dimensions = resourceDimensions(resource)
  const {
    targetRef,
    url,
  } = useCanvasResourceUrl({
    canvasId: resource.canvasId,
    resourceId: resource.id,
    kind: 'preview',
    lazy: true,
    enabled: resource.kind === 'IMAGE' || resource.kind === 'VIDEO',
  })
  return (
    <span className="canvas-resource-thumbnail" ref={targetRef} data-kind={resource.kind}>
      {url ? (
        <img
          src={url}
          alt=""
          width={dimensions?.width}
          height={dimensions?.height}
          draggable={false}
          loading="lazy"
          decoding="async"
        />
      ) : (
        <span aria-hidden="true">{resourceIcon(resource.kind)}</span>
      )}
    </span>
  )
}

function resourceIcon(kind: Resource['kind']): string {
  if (kind === 'IMAGE') {
    return '▧'
  }
  if (kind === 'VIDEO') {
    return '▶'
  }
  if (kind === 'AUDIO') {
    return '♪'
  }
  return 'T'
}

function resourceDimensions(resource: Resource): { width: number; height: number } | null {
  const width = finiteNumber(resource.width)
  const height = finiteNumber(resource.height)
  return width !== null && width > 0 && height !== null && height > 0
    ? { width, height }
    : null
}

function finiteNumber(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}
