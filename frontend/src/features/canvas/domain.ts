import type { CanvasRevision } from '@/shared/api/contracts/base'
import { isCanonicalUuid } from '@/shared/lib/uuid'
import { resourceNodeSize } from '@/features/canvas/resource-node-size'
import type {
  CanvasFunctionRunStatus,
  CanvasReferenceDTO,
  CanvasResourceDTO,
  CanvasResourceKind,
  CanvasSnapshotDTO,
  CanvasTransformDTO,
} from '@/shared/api/generated/contracts'

export interface CanvasDocument {
  id: string
  title: string
  revision: CanvasRevision
  createdAt: number
  updatedAt: number
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
  createdAt: number
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
  updatedAt: number
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

/** 服务端已校验 args 为 JSON object；本地仅做对象形状收窄，缺失或非对象时按空对象处理。 */
export function readJsonObject(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {}
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

export function deriveReferencesFromNodes(nodes: Array<{
  id: string
  canvasId: string
  function?: { name: string; args: unknown } | null
}>): Reference[] {
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
        args: readJsonObject(node.function.args),
      } : null,
      run: node.run,
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
  const base: Omit<
    Resource,
    'blobId' | 'textContent' | 'mediaType' | 'sizeBytes' | 'width' | 'height' | 'durationMs'
  > = {
    id: resource.id,
    canvasId: resource.canvasId,
    ownerNodeId: resource.ownerNodeId,
    resourceIndex: resource.resourceIndex,
    name: resource.name,
    kind: resource.kind,
    createdAt: resource.createdAt,
  }
  if (resource.type === 'TEXT') {
    return {
      ...base,
      blobId: null,
      textContent: resource.textContent,
      mediaType: null,
      sizeBytes: null,
      width: null,
      height: null,
      durationMs: null,
    }
  }
  return {
    ...base,
    blobId: resource.blobId,
    textContent: null,
    mediaType: resource.mediaType,
    sizeBytes: decimalLong(resource.sizeBytes),
    width: resource.width,
    height: resource.height,
    durationMs: decimalLong(resource.durationMs),
  }
}

/** Java Long 以十进制字符串传输；视图模型只接受安全整数范围内的值，越界时显式报错而不是静默丢失精度。 */
function decimalLong(value: string | null): number | null {
  if (value === null) {
    return null
  }
  const parsed = Number(value)
  if (!Number.isSafeInteger(parsed)) {
    throw new Error(`canvas long value is outside the safe integer range: ${value}`)
  }
  return parsed
}
