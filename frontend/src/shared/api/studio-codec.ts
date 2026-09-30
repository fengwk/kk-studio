import { ApiError } from '@/shared/api/client'
import type { CanvasRevision } from '@/shared/api/contracts/base'
import { isCanvasRevision } from '@/shared/lib/canvas-version'
import type {
  CanvasConflictDTO,
  CanvasDocumentDTO,
  CanvasFunctionDefinitionDTO,
  CanvasFunctionOutputDTO,
  CanvasFunctionReferencePolicyDTO,
  CanvasGroupPatchDTO,
  CanvasNodePatchDTO,
  CanvasPatchDTO,
  CanvasReferenceDTO,
  CanvasResourceNodeDTO,
  CanvasSnapshotDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'

/**
 * Canvas wire codec：纯 primitive/DTO 解码器。
 *
 * 严格 wire 解码：只接受真实 convention4j 输出（long 为 decimal string、
 * required-null 显式输出），nullable 字段只接受显式 null 或合法值，
 * 缺失/undefined 一律拒绝；枚举只接受精确值；非法/超 safe integer
 * 一律 fail closed（ApiError），绝不静默降级为 0/null。本模块无传输语义，
 * 只负责把 envelope.data 解码为类型化 DTO；HTTP/信封/错误映射由
 * studio-service 负责。
 */

function invalidPayload(detail: string): ApiError {
  return new ApiError(`Canvas API returned an invalid payload: ${detail}`)
}

function requireRecord(value: unknown, path: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw invalidPayload(`${path} must be an object`)
  }
  return value as Record<string, unknown>
}

function requireString(value: unknown, path: string): string {
  if (typeof value !== 'string') {
    throw invalidPayload(`${path} must be a string`)
  }
  return value
}

const UUID_SHAPE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function requireUuid(value: unknown, path: string): UUIDString {
  const text = requireString(value, path)
  if (!UUID_SHAPE.test(text)) {
    throw invalidPayload(`${path} must be a canonical UUID string`)
  }
  return text as UUIDString
}

function requireArray(value: unknown, path: string): unknown[] {
  if (!Array.isArray(value)) {
    throw invalidPayload(`${path} must be an array`)
  }
  return value
}

function requireCanvasRevision(value: unknown, path: string): CanvasRevision {
  if (!isCanvasRevision(value)) {
    throw invalidPayload(`${path} must be a canonical non-negative decimal string`)
  }
  return value
}

const RESOURCE_KINDS = ['IMAGE', 'VIDEO', 'AUDIO', 'TEXT'] as const

function requireResourceKind(value: unknown, path: string): CanvasResourceNodeDTO['resources'][number]['kind'] {
  if (typeof value !== 'string' || !(RESOURCE_KINDS as readonly string[]).includes(value)) {
    throw invalidPayload(`${path} must be one of IMAGE, VIDEO, AUDIO, TEXT`)
  }
  return value as CanvasResourceNodeDTO['resources'][number]['kind']
}

const RUN_STATUSES = ['READY', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'UNKNOWN'] as const

function requireRunStatus(value: unknown, path: string): NonNullable<CanvasResourceNodeDTO['run']>['status'] {
  if (typeof value !== 'string' || !(RUN_STATUSES as readonly string[]).includes(value)) {
    throw invalidPayload(`${path} must be one of READY, RUNNING, SUCCEEDED, FAILED, CANCELLED, UNKNOWN`)
  }
  return value as NonNullable<CanvasResourceNodeDTO['run']>['status']
}

function requirePatchOp(value: unknown, path: string): 'REMOVE' | 'UPSERT' {
  if (value !== 'REMOVE' && value !== 'UPSERT') {
    throw invalidPayload(`${path} must be one of REMOVE, UPSERT`)
  }
  return value
}

function decodeNullable(value: unknown, path: string): boolean {
  if (value === undefined) {
    throw invalidPayload(`${path} must be explicitly null`)
  }
  return value !== null
}

function decodeNullableString(value: unknown, path: string): string | null {
  return decodeNullable(value, path) ? requireString(value, path) : null
}

function decodeNullableInt(value: unknown, path: string): number | null {
  return decodeNullable(value, path) ? requireInt(value, path) : null
}

function requireFiniteNumber(value: unknown, path: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw invalidPayload(`${path} must be a finite number`)
  }
  return value
}

function requirePositiveFiniteNumber(value: unknown, path: string): number {
  const number = requireFiniteNumber(value, path)
  if (number <= 0) {
    throw invalidPayload(`${path} must be greater than zero`)
  }
  return number
}

function requireInt(value: unknown, path: string): number {
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 0) {
    throw invalidPayload(`${path} must be a non-negative integer`)
  }
  return value
}

/** Java long wire（decimal string|null）安全解码为 number|null，超 safe integer fail closed。 */
function decodeLong(value: unknown, path: string): number | null {
  if (!decodeNullable(value, path)) {
    return null
  }
  if (!isCanvasRevision(value)) {
    throw invalidPayload(`${path} must be a canonical non-negative decimal string or null`)
  }
  const parsed = Number(value)
  if (!Number.isSafeInteger(parsed)) {
    throw invalidPayload(`${path} exceeds Number.MAX_SAFE_INTEGER`)
  }
  return parsed
}

export function decodeCanvasDocument(value: unknown): CanvasDocumentDTO {
  const candidate = requireRecord(value, 'document')
  return {
    id: requireUuid(candidate.id, 'document.id'),
    title: requireString(candidate.title, 'document.title'),
    revision: requireCanvasRevision(candidate.revision, 'document.revision'),
    createdAt: requireString(candidate.createdAt, 'document.createdAt'),
    updatedAt: requireString(candidate.updatedAt, 'document.updatedAt'),
  }
}

export function decodeCanvasDocumentList(value: unknown): CanvasDocumentDTO[] {
  return requireArray(value, 'document list').map(decodeCanvasDocument)
}

function decodeCanvasResource(value: unknown): CanvasResourceNodeDTO['resources'][number] {
  const candidate = requireRecord(value, 'resource')
  return {
    id: requireUuid(candidate.id, 'resource.id'),
    canvasId: requireUuid(candidate.canvasId, 'resource.canvasId'),
    ownerNodeId: requireUuid(candidate.ownerNodeId, 'resource.ownerNodeId'),
    resourceIndex: requireInt(candidate.resourceIndex, 'resource.resourceIndex'),
    blobId: decodeNullableUuid(candidate.blobId, 'resource.blobId'),
    name: requireString(candidate.name, 'resource.name'),
    textContent: decodeNullableString(candidate.textContent, 'resource.textContent'),
    kind: requireResourceKind(candidate.kind, 'resource.kind'),
    mediaType: decodeNullableString(candidate.mediaType, 'resource.mediaType'),
    sizeBytes: decodeLong(candidate.sizeBytes, 'resource.sizeBytes'),
    width: decodeNullableInt(candidate.width, 'resource.width'),
    height: decodeNullableInt(candidate.height, 'resource.height'),
    durationMs: decodeLong(candidate.durationMs, 'resource.durationMs'),
    createdAt: requireString(candidate.createdAt, 'resource.createdAt'),
  }
}

function decodeNullableUuid(value: unknown, path: string): UUIDString | null {
  return decodeNullable(value, path) ? requireUuid(value, path) : null
}

export function decodeCanvasResourceNode(value: unknown): CanvasResourceNodeDTO {
  const candidate = requireRecord(value, 'node')
  return {
    id: requireUuid(candidate.id, 'node.id'),
    canvasId: requireUuid(candidate.canvasId, 'node.canvasId'),
    name: requireString(candidate.name, 'node.name'),
    transform: decodeTransform(candidate.transform, 'node.transform'),
    groupId: decodeNullableUuid(candidate.groupId, 'node.groupId'),
    resources: requireArray(candidate.resources, 'node.resources').map(decodeCanvasResource),
    function: decodeNullable(candidate.function, 'node.function')
      ? decodeFunction(candidate.function)
      : null,
    run: decodeNullable(candidate.run, 'node.run') ? decodeFunctionRun(candidate.run) : null,
  }
}

function decodeTransform(value: unknown, path: string): CanvasResourceNodeDTO['transform'] {
  const candidate = requireRecord(value, path)
  return {
    x: requireFiniteNumber(candidate.x, `${path}.x`),
    y: requireFiniteNumber(candidate.y, `${path}.y`),
    width: requirePositiveFiniteNumber(candidate.width, `${path}.width`),
    height: requirePositiveFiniteNumber(candidate.height, `${path}.height`),
  }
}

function decodeFunction(value: unknown): NonNullable<CanvasResourceNodeDTO['function']> {
  const candidate = requireRecord(value, 'node.function')
  return {
    name: requireString(candidate.name, 'node.function.name'),
    args: requireRecord(candidate.args, 'node.function.args'),
  }
}

export function decodeFunctionRun(value: unknown): NonNullable<CanvasResourceNodeDTO['run']> {
  const candidate = requireRecord(value, 'node.run')
  return {
    nodeId: requireUuid(candidate.nodeId, 'node.run.nodeId'),
    requestId: requireUuid(candidate.requestId, 'node.run.requestId'),
    status: requireRunStatus(candidate.status, 'node.run.status'),
    stage: requireString(candidate.stage, 'node.run.stage'),
    error: decodeNullableString(candidate.error, 'node.run.error'),
    updatedAt: requireString(candidate.updatedAt, 'node.run.updatedAt'),
  }
}

export function decodeCanvasReference(value: unknown): CanvasReferenceDTO {
  const candidate = requireRecord(value, 'reference')
  return {
    canvasId: requireUuid(candidate.canvasId, 'reference.canvasId'),
    sourceNodeId: requireUuid(candidate.sourceNodeId, 'reference.sourceNodeId'),
    targetNodeId: requireUuid(candidate.targetNodeId, 'reference.targetNodeId'),
    index: requireInt(candidate.index, 'reference.index'),
  }
}

export function decodeCanvasSnapshot(value: unknown): CanvasSnapshotDTO {
  const candidate = requireRecord(value, 'snapshot')
  return {
    document: decodeCanvasDocument(candidate.document),
    nodes: requireArray(candidate.nodes, 'snapshot.nodes').map(decodeCanvasResourceNode),
    groups: requireArray(candidate.groups, 'snapshot.groups').map(decodeCanvasGroup),
    references: candidate.references ? requireArray(candidate.references, 'snapshot.references').map(decodeCanvasReference) : [],
  }
}

export function decodeCanvasGroup(value: unknown): CanvasSnapshotDTO['groups'][number] {
  const candidate = requireRecord(value, 'group')
  return {
    id: requireUuid(candidate.id, 'group.id'),
    canvasId: requireUuid(candidate.canvasId, 'group.canvasId'),
    title: requireString(candidate.title, 'group.title'),
    transform: decodeTransform(candidate.transform, 'group.transform'),
  }
}

function decodeCanvasGroupPatch(value: unknown): CanvasGroupPatchDTO {
  const candidate = requireRecord(value, 'group patch')
  const op = requirePatchOp(candidate.op, 'group patch.op')
  if (op === 'REMOVE') {
    return { op, groupId: requireUuid(candidate.groupId, 'group patch.groupId') }
  }
  return { op: 'UPSERT', group: decodeCanvasGroup(candidate.group) }
}

function decodeCanvasNodePatch(value: unknown): CanvasNodePatchDTO {
  const candidate = requireRecord(value, 'node patch')
  const op = requirePatchOp(candidate.op, 'node patch.op')
  if (op === 'REMOVE') {
    return { op, nodeId: requireUuid(candidate.nodeId, 'node patch.nodeId') }
  }
  return { op: 'UPSERT', node: decodeCanvasResourceNode(candidate.node) }
}

export function decodeCanvasPatch(value: unknown): CanvasPatchDTO {
  const candidate = requireRecord(value, 'patch')
  return {
    revision: requireCanvasRevision(candidate.revision, 'patch.revision'),
    groups: requireArray(candidate.groups, 'patch.groups').map(decodeCanvasGroupPatch),
    nodes: requireArray(candidate.nodes, 'patch.nodes').map(decodeCanvasNodePatch),
  }
}

export function decodeCanvasConflict(value: unknown): CanvasConflictDTO {
  const candidate = requireRecord(value, 'conflict')
  const kind = requireString(candidate.kind, 'conflict.kind')
  switch (kind) {
    case 'TARGET_MISSING':
    case 'TARGET_PRESENT':
      return {
        kind,
        targetId: requireString(candidate.targetId, 'conflict.targetId'),
        target: requireString(candidate.target, 'conflict.target') as 'NODE' | 'GROUP',
      }
    case 'STALE_NODE':
      return {
        kind,
        nodeId: requireString(candidate.nodeId, 'conflict.nodeId'),
        group: requireString(candidate.group, 'conflict.group'),
        current: decodeCanvasResourceNode(candidate.current),
      }
    case 'STALE_GROUP':
      return {
        kind,
        groupId: requireString(candidate.groupId, 'conflict.groupId'),
        current: decodeCanvasGroup(candidate.current),
      }
    case 'NODE_RUNNING':
      return {
        kind,
        nodeId: requireString(candidate.nodeId, 'conflict.nodeId'),
        run: decodeFunctionRun(candidate.run),
      }
    case 'NODE_REFERENCED':
      return {
        kind,
        nodeId: requireString(candidate.nodeId, 'conflict.nodeId'),
        referencingNodeIds: requireArray(candidate.referencingNodeIds, 'conflict.referencingNodeIds').map((item, idx) =>
          requireString(item, `conflict.referencingNodeIds[${idx}]`),
        ),
      }
    default:
      throw invalidPayload(`unknown conflict kind: ${kind}`)
  }
}

function decodeReferencePolicy(value: unknown): CanvasFunctionReferencePolicyDTO {
  const candidate = requireRecord(value, 'referencePolicy')
  return {
    allowedKinds: requireArray(candidate.allowedKinds, 'referencePolicy.allowedKinds').map((k, i) =>
      requireResourceKind(k, `referencePolicy.allowedKinds[${i}]`),
    ),
    maxReferences: decodeNullableInt(candidate.maxReferences, 'referencePolicy.maxReferences'),
    maxByKind: (candidate.maxByKind ?? {}) as Partial<Record<CanvasResourceNodeDTO['resources'][number]['kind'], number>>,
  }
}

function decodeFunctionOutput(value: unknown, path: string): CanvasFunctionOutputDTO {
  const candidate = requireRecord(value, path)
  return {
    kind: requireString(candidate.kind, `${path}.kind`),
    name: decodeNullableString(candidate.name, `${path}.name`),
  }
}

export function decodeCanvasFunctionDefinition(value: unknown): CanvasFunctionDefinitionDTO {
  const candidate = requireRecord(value, 'function definition')
  return {
    name: requireString(candidate.name, 'function.name'),
    description: decodeNullableString(candidate.description, 'function.description'),
    argsSchema: requireRecord(candidate.argsSchema ?? {}, 'function.argsSchema'),
    outputs: requireArray(candidate.outputs, 'function.outputs').map((out, idx) =>
      decodeFunctionOutput(out, `function.outputs[${idx}]`),
    ),
    referencePolicy: candidate.referencePolicy ? decodeReferencePolicy(candidate.referencePolicy) : null,
    available: typeof candidate.available === 'boolean' ? candidate.available : true,
    unavailableReason: decodeNullableString(candidate.unavailableReason, 'function.unavailableReason'),
  }
}
