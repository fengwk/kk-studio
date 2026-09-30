import type {
  CanvasFunctionRunStatus,
  CanvasReferenceDTO,
  CanvasResourceDTO,
  CanvasResourceKind,
  CanvasSnapshotDTO,
  CanvasTransformDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'
import type { CanvasRevision } from '@/shared/api/contracts/base'
import { isCanonicalUuid } from '@/shared/lib/uuid'
import { resourceNodeSize } from '@/features/canvas/resource-node-size'

export interface CanvasDocument {
  id: UUIDString
  title: string
  revision: CanvasRevision
  createdAt: string
  updatedAt: string
}

export interface Resource {
  id: UUIDString
  canvasId: UUIDString
  ownerNodeId: UUIDString
  resourceIndex: number
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
  name: string
  args: Record<string, unknown>
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

export interface Reference {
  canvasId: UUIDString
  sourceNodeId: UUIDString
  targetNodeId: UUIDString
  index: number
}

export interface CanvasSnapshot {
  document: CanvasDocument
  resourceNodes: ResourceNode[]
  groups: Group[]
  references: Reference[]
}

export function scanArgsForReferences(obj: unknown, onRef: (nodeId: string, index: number) => void): void {
  if (!obj || typeof obj !== 'object') {
    return
  }
  if (Array.isArray(obj)) {
    for (const item of obj) {
      scanArgsForReferences(item, onRef)
    }
    return
  }
  const rec = obj as Record<string, unknown>
  if (rec.type === 'resource' && typeof rec.nodeId === 'string' && isCanonicalUuid(rec.nodeId)) {
    const index = typeof rec.index === 'number' && Number.isInteger(rec.index) && rec.index >= 0 ? rec.index : 0
    onRef(rec.nodeId, index)
    return
  }
  for (const val of Object.values(rec)) {
    scanArgsForReferences(val, onRef)
  }
}

export function deriveReferencesFromNodes(nodes: Array<{ id: UUIDString; canvasId: UUIDString; function?: Function | null }>): Reference[] {
  const refs: Reference[] = []
  for (const node of nodes) {
    if (!node.function?.args) {
      continue
    }
    scanArgsForReferences(node.function.args, (sourceNodeId, index) => {
      refs.push({
        canvasId: node.canvasId,
        sourceNodeId: sourceNodeId as UUIDString,
        targetNodeId: node.id,
        index,
      })
    })
  }
  return refs
}

export function projectCanvasSnapshot(snapshot: CanvasSnapshotDTO): CanvasSnapshot {
  const resourceNodes: ResourceNode[] = snapshot.nodes.map((node) => {
    const projected: ResourceNode = {
      ...node,
      transform: { ...node.transform },
      resources: node.resources.map(projectCanvasResource),
      function: node.function ? {
        name: node.function.name,
        args: node.function.args ?? {},
      } : null,
      run: node.run ? { ...node.run } : null,
    }
    return {
      ...projected,
      transform: { ...projected.transform, ...resourceNodeSize(projected) },
    }
  })

  const rawReferences = snapshot.references && snapshot.references.length > 0
    ? snapshot.references
    : deriveReferencesFromNodes(snapshot.nodes)

  const references: Reference[] = rawReferences.map((ref: CanvasReferenceDTO) => ({
    canvasId: ref.canvasId,
    sourceNodeId: ref.sourceNodeId,
    targetNodeId: ref.targetNodeId,
    index: ref.index,
  }))

  return {
    document: {
      ...snapshot.document,
      revision: snapshot.document.revision,
    },
    resourceNodes,
    groups: snapshot.groups.map((group) => ({
      ...group,
      transform: { ...group.transform },
    })),
    references,
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
