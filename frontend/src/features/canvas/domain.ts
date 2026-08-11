import type {
  CanvasFunctionRunStatus,
  CanvasResourceDTO,
  CanvasResourceKind,
  CanvasSnapshotDTO,
  CanvasTransformDTO,
  DecimalString,
} from '@/shared/api/contracts/studio'
import { resourceNodeSize } from '@/features/canvas/resource-node-size'

export interface CanvasDocument {
  id: DecimalString
  title: string
  graphRevision: DecimalString
  createdAt: string
  updatedAt: string
}

export interface Resource {
  id: DecimalString
  canvasId: DecimalString
  kind: CanvasResourceKind
  mediaType: string
  name: string
  size: DecimalString
  text: string | null
  metadata: Record<string, unknown>
  createdAt: string
}

export interface Function {
  modelKey: string
  configJson: string
}

export interface Run {
  nodeId: DecimalString
  requestId: string
  status: CanvasFunctionRunStatus
  stage: string
  error: string | null
  updatedAt: string
}

export interface ResourceNode {
  id: DecimalString
  canvasId: DecimalString
  name: string
  transform: CanvasTransformDTO
  groupId: DecimalString | null
  resources: Resource[]
  function: Function | null
  run: Run | null
}

export interface Group {
  id: DecimalString
  canvasId: DecimalString
  title: string
  transform: CanvasTransformDTO
}

export interface Link {
  canvasId: DecimalString
  sourceNodeId: DecimalString
  targetNodeId: DecimalString
}

export interface CanvasSnapshot {
  document: CanvasDocument
  resourceNodes: ResourceNode[]
  groups: Group[]
  links: Link[]
}

export function projectCanvasSnapshot(snapshot: CanvasSnapshotDTO): CanvasSnapshot {
  return {
    document: { ...snapshot.document },
    resourceNodes: snapshot.nodes.map((node) => {
      const projected: ResourceNode = {
        ...node,
        transform: { ...node.transform },
        resources: node.resources.map(projectCanvasResource),
        function: node.function ? { ...node.function } : null,
        run: node.run ? { ...node.run } : null,
      }
      return {
        ...projected,
        transform: { ...projected.transform, ...resourceNodeSize(projected) },
      }
    }),
    groups: snapshot.groups.map((group) => ({
      ...group,
      transform: { ...group.transform },
    })),
    links: snapshot.links.map((link) => ({ ...link })),
  }
}

export function projectCanvasResource(resource: CanvasResourceDTO): Resource {
  return {
    id: resource.id,
    canvasId: resource.canvasId,
    kind: resource.kind,
    mediaType: resource.mediaType,
    name: resource.name,
    size: resource.size,
    text: resource.textContent,
    metadata: parseMetadata(resource.metadataJson),
    createdAt: resource.createdAt,
  }
}

function parseMetadata(metadataJson: string): Record<string, unknown> {
  try {
    const value: unknown = JSON.parse(metadataJson)
    return value && typeof value === 'object' && !Array.isArray(value)
      ? value as Record<string, unknown>
      : {}
  } catch {
    return {}
  }
}
