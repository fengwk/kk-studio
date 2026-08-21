import { ApiError } from '@/shared/api/client'
import type { CanvasVersion } from '@/shared/api/contracts/base'
import { isCanvasVersion } from '@/shared/lib/canvas-version'
import type {
  CanvasChangesDTO,
  CanvasDocumentDTO,
  CanvasGroupPatchDTO,
  CanvasLinkPatchDTO,
  CanvasNodePatchDTO,
  CanvasPatchDTO,
  CanvasResourceNodeDTO,
  CanvasSnapshotDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'

/**
 * Canvas wire codec：纯 primitive/DTO 解码器。
 *
 * 严格 wire 解码：只接受真实 convention4j 输出（long 为 decimal string、
 * required-null 显式输出），非法/超 safe integer 一律 fail closed（ApiError），
 * 绝不静默降级为 0/null。本模块无传输语义，只负责把 envelope.data
 * 解码为类型化 DTO；HTTP/信封/错误映射由 studio-service 负责。
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

function requireStringArray(value: unknown, path: string): unknown[] {
  if (!Array.isArray(value)) {
    throw invalidPayload(`${path} must be an array`)
  }
  return value
}

function requireCanvasVersion(value: unknown, path: string): CanvasVersion {
  if (!isCanvasVersion(value)) {
    throw invalidPayload(`${path} must be a canonical non-negative decimal string`)
  }
  return value
}

function decodeNullableString(value: unknown, path: string): string | null {
  if (value === null || value === undefined) {
    return null
  }
  return requireString(value, path)
}

function decodeNullableInt(value: unknown, path: string): number | null {
  if (value === null || value === undefined) {
    return null
  }
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 0) {
    throw invalidPayload(`${path} must be a non-negative integer or null`)
  }
  return value
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
  if (value === null || value === undefined) {
    return null
  }
  if (!isCanvasVersion(value)) {
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
    version: requireCanvasVersion(candidate.version, 'document.version'),
    createdAt: requireString(candidate.createdAt, 'document.createdAt'),
    updatedAt: requireString(candidate.updatedAt, 'document.updatedAt'),
  }
}

export function decodeCanvasDocumentList(value: unknown): CanvasDocumentDTO[] {
  return requireStringArray(value, 'document list').map(decodeCanvasDocument)
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
    kind: requireString(candidate.kind, 'resource.kind') as CanvasResourceNodeDTO['resources'][number]['kind'],
    mediaType: decodeNullableString(candidate.mediaType, 'resource.mediaType'),
    sizeBytes: decodeLong(candidate.sizeBytes, 'resource.sizeBytes'),
    width: decodeNullableInt(candidate.width, 'resource.width'),
    height: decodeNullableInt(candidate.height, 'resource.height'),
    durationMs: decodeLong(candidate.durationMs, 'resource.durationMs'),
    createdAt: requireString(candidate.createdAt, 'resource.createdAt'),
  }
}

function decodeNullableUuid(value: unknown, path: string): UUIDString | null {
  if (value === null || value === undefined) {
    return null
  }
  return requireUuid(value, path)
}

function decodeCanvasResourceNode(value: unknown): CanvasResourceNodeDTO {
  const candidate = requireRecord(value, 'node')
  return {
    id: requireUuid(candidate.id, 'node.id'),
    canvasId: requireUuid(candidate.canvasId, 'node.canvasId'),
    name: requireString(candidate.name, 'node.name'),
    transform: decodeTransform(candidate.transform, 'node.transform'),
    groupId: decodeNullableUuid(candidate.groupId, 'node.groupId'),
    resources: requireStringArray(candidate.resources, 'node.resources').map(decodeCanvasResource),
    function: candidate.function === null || candidate.function === undefined
      ? null
      : decodeFunction(candidate.function),
    run: candidate.run === null || candidate.run === undefined
      ? null
      : decodeFunctionRun(candidate.run),
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
    modelKey: requireString(candidate.modelKey, 'node.function.modelKey'),
    configJson: requireString(candidate.configJson, 'node.function.configJson'),
  }
}

function decodeFunctionRun(value: unknown): NonNullable<CanvasResourceNodeDTO['run']> {
  const candidate = requireRecord(value, 'node.run')
  return {
    nodeId: requireUuid(candidate.nodeId, 'node.run.nodeId'),
    requestId: requireUuid(candidate.requestId, 'node.run.requestId'),
    status: requireString(candidate.status, 'node.run.status') as NonNullable<
      CanvasResourceNodeDTO['run']
    >['status'],
    stage: requireString(candidate.stage, 'node.run.stage'),
    error: decodeNullableString(candidate.error, 'node.run.error'),
    updatedAt: requireString(candidate.updatedAt, 'node.run.updatedAt'),
  }
}

export function decodeCanvasSnapshot(value: unknown): CanvasSnapshotDTO {
  const candidate = requireRecord(value, 'snapshot')
  return {
    document: decodeCanvasDocument(candidate.document),
    nodes: requireStringArray(candidate.nodes, 'snapshot.nodes').map(decodeCanvasResourceNode),
    groups: requireStringArray(candidate.groups, 'snapshot.groups').map(decodeCanvasGroup),
    links: requireStringArray(candidate.links, 'snapshot.links').map(decodeCanvasLink),
  }
}

function decodeCanvasGroup(value: unknown): CanvasSnapshotDTO['groups'][number] {
  const candidate = requireRecord(value, 'group')
  return {
    id: requireUuid(candidate.id, 'group.id'),
    canvasId: requireUuid(candidate.canvasId, 'group.canvasId'),
    title: requireString(candidate.title, 'group.title'),
    transform: decodeTransform(candidate.transform, 'group.transform'),
  }
}

function decodeCanvasLink(value: unknown): CanvasSnapshotDTO['links'][number] {
  const candidate = requireRecord(value, 'link')
  return {
    canvasId: requireUuid(candidate.canvasId, 'link.canvasId'),
    sourceNodeId: requireUuid(candidate.sourceNodeId, 'link.sourceNodeId'),
    targetNodeId: requireUuid(candidate.targetNodeId, 'link.targetNodeId'),
  }
}

function decodeCanvasGroupPatch(value: unknown): CanvasGroupPatchDTO {
  const candidate = requireRecord(value, 'group patch')
  const op = requireString(candidate.op, 'group patch.op')
  if (op === 'REMOVE') {
    return { op, groupId: requireUuid(candidate.groupId, 'group patch.groupId') }
  }
  return { op: 'UPSERT', group: decodeCanvasGroup(candidate.group) }
}

function decodeCanvasNodePatch(value: unknown): CanvasNodePatchDTO {
  const candidate = requireRecord(value, 'node patch')
  const op = requireString(candidate.op, 'node patch.op')
  if (op === 'REMOVE') {
    return { op, nodeId: requireUuid(candidate.nodeId, 'node patch.nodeId') }
  }
  return { op: 'UPSERT', node: decodeCanvasResourceNode(candidate.node) }
}

function decodeCanvasLinkPatch(value: unknown): CanvasLinkPatchDTO {
  const candidate = requireRecord(value, 'link patch')
  const op = requireString(candidate.op, 'link patch.op')
  if (op === 'REMOVE') {
    return {
      op,
      sourceNodeId: requireUuid(candidate.sourceNodeId, 'link patch.sourceNodeId'),
      targetNodeId: requireUuid(candidate.targetNodeId, 'link patch.targetNodeId'),
    }
  }
  return { op: 'UPSERT', link: decodeCanvasLink(candidate.link) }
}

export function decodeCanvasPatch(value: unknown): CanvasPatchDTO {
  const candidate = requireRecord(value, 'patch')
  return {
    baseVersion: requireCanvasVersion(candidate.baseVersion, 'patch.baseVersion'),
    version: requireCanvasVersion(candidate.version, 'patch.version'),
    groups: requireStringArray(candidate.groups, 'patch.groups').map(decodeCanvasGroupPatch),
    nodes: requireStringArray(candidate.nodes, 'patch.nodes').map(decodeCanvasNodePatch),
    links: requireStringArray(candidate.links, 'patch.links').map(decodeCanvasLinkPatch),
  }
}

export function decodeCanvasChanges(value: unknown): CanvasChangesDTO {
  const candidate = requireRecord(value, 'changes')
  return {
    patches: requireStringArray(candidate.patches, 'changes.patches').map(decodeCanvasPatch),
    snapshot: candidate.snapshot === null || candidate.snapshot === undefined
      ? null
      : decodeCanvasSnapshot(candidate.snapshot),
  }
}
