import {
  CANVAS_FUNCTION_RUN_STATUSES,
  CANVAS_RESOURCE_KINDS,
  type CanvasFunctionRunStatus,
  type CanvasResourceKind,
} from '@/shared/api/contracts/studio'
import type { CanvasRevision } from '@/shared/api/contracts/base'
import { isCanonicalUuid } from '@/shared/lib/uuid'
import { resourceNodeSize } from '@/features/canvas/resource-node-size'
import type {
  CanvasReferenceDTO,
  CanvasResourceDTO,
  CanvasSnapshotDTO,
  CanvasTransformDTO,
} from '@/shared/api/generated/contracts'

export interface CanvasDocument {
  id: string
  title: string
  revision: CanvasRevision
  createdAt: string
  updatedAt: string
}

export interface Resource {
  id: string
  canvasId: string
  ownerNodeId: string
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
  nodeId: string
  requestId: string
  status: CanvasFunctionRunStatus
  stage: string
  error: string | null
  updatedAt: string
}

export interface ResourceNode {
  id: string
  canvasId: string
  name: string
  transform: CanvasTransformDTO
  groupId: string | null
  resources: Resource[]
  function: Function | null
  run: Run | null
}

export interface Group {
  id: string
  canvasId: string
  title: string
  transform: CanvasTransformDTO
}

export interface Reference {
  canvasId: string
  sourceNodeId: string
  targetNodeId: string
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

export function deriveReferencesFromNodes(nodes: Array<{ id: string; canvasId: string; function?: Function | null }>): Reference[] {
  const refs: Reference[] = []
  for (const node of nodes) {
    if (!node.function?.args) {
      continue
    }
    scanArgsForReferences(node.function.args, (sourceNodeId, index) => {
      refs.push({
        canvasId: node.canvasId,
        sourceNodeId: sourceNodeId,
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
      run: node.run ? { ...node.run, status: runStatus(node.run.status) } : null,
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
    kind: resourceKind(resource.kind),
    mediaType: resource.mediaType,
    sizeBytes: decimalLong(resource.sizeBytes),
    width: resource.width,
    height: resource.height,
    durationMs: decimalLong(resource.durationMs),
    createdAt: resource.createdAt,
  }
}

/** wire 只承诺字符串；领域模型只接受已知的资源类型。 */
function resourceKind(value: string): CanvasResourceKind {
  if (!(CANVAS_RESOURCE_KINDS as readonly string[]).includes(value)) {
    throw new Error(`unknown canvas resource kind: ${value}`)
  }
  return value as CanvasResourceKind
}

function runStatus(value: string): CanvasFunctionRunStatus {
  if (!(CANVAS_FUNCTION_RUN_STATUSES as readonly string[]).includes(value)) {
    throw new Error(`unknown canvas function run status: ${value}`)
  }
  return value as CanvasFunctionRunStatus
}

/** Java Long 以十进制字符串传输；解码器已保证它在安全整数范围内。 */
function decimalLong(value: string | null): number | null {
  return value === null ? null : Number(value)
}
