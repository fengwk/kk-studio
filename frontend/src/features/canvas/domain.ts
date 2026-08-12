import type {
  CanvasFunctionRunStatus,
  CanvasResourceDTO,
  CanvasResourceKind,
  CanvasSnapshotDTO,
  CanvasTransformDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'
import { resourceNodeSize } from '@/features/canvas/resource-node-size'

export interface CanvasDocument {
  id: UUIDString
  title: string
  version: number
  /** 绑定到本画布的 Harness Thread（canonical UUID）；null 表示尚未创建。 */
  threadId: UUIDString | null
  createdAt: string
  updatedAt: string
}

export interface Resource {
  id: UUIDString
  canvasId: UUIDString
  ownerNodeId: UUIDString
  resourceIndex: number
  /** TEXT 资源内容在 textContent 中，无对象存储 blob；其余资源引用共享存储的持久 blob。 */
  blobId: string | null
  name: string
  textContent: string | null
  kind: CanvasResourceKind
  mediaType: string | null
  sizeBytes: number | null
  width: number | null
  height: number | null
  durationMs: number | null
  createdAt: string
}

export interface Function {
  modelKey: string
  configJson: string
}

export interface Run {
  nodeId: UUIDString
  requestId: UUIDString
  status: CanvasFunctionRunStatus
  stage: string
  error: string | null
  updatedAt: string
}

export interface ResourceNode {
  id: UUIDString
  canvasId: UUIDString
  name: string
  transform: CanvasTransformDTO
  groupId: UUIDString | null
  resources: Resource[]
  function: Function | null
  run: Run | null
}

export interface Group {
  id: UUIDString
  canvasId: UUIDString
  title: string
  transform: CanvasTransformDTO
}

export interface Link {
  canvasId: UUIDString
  sourceNodeId: UUIDString
  targetNodeId: UUIDString
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
    ownerNodeId: resource.ownerNodeId,
    resourceIndex: resource.resourceIndex,
    blobId: resource.blobId,
    name: resource.name,
    textContent: resource.textContent,
    kind: resource.kind,
    mediaType: resource.mediaType,
    sizeBytes: resource.sizeBytes,
    width: resource.width,
    height: resource.height,
    durationMs: resource.durationMs,
    createdAt: resource.createdAt,
  }
}
