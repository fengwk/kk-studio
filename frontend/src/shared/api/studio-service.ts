import { ApiError, apiBaseUrl } from '@/shared/api/client'
import type { ResultEnvelope } from '@/shared/api/contracts/base'
import type { CanvasVersion } from '@/shared/api/contracts/base'
import { isCanvasVersion } from '@/shared/lib/canvas-version'
import type {
  ApplyCanvasCommandsRequestDTO,
  CanvasChangesDTO,
  CanvasDocumentDTO,
  CanvasFunctionModelDTO,
  CanvasFunctionRunDTO,
  CanvasFunctionRunRequestDTO,
  CanvasGroupPatchDTO,
  CanvasLinkPatchDTO,
  CanvasNodePatchDTO,
  CanvasPatchDTO,
  CanvasPresignedUrlDTO,
  CanvasResourceNodeDTO,
  CanvasSnapshotDTO,
  CanvasThreadFirstSendRequestDTO,
  CanvasThreadFirstSendResponseDTO,
  CreateCanvasRequestDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'
import { getLocale, translate } from '@/shared/i18n'

export interface CanvasRequestOptions {
  signal?: AbortSignal
}

export function listCanvases(options?: CanvasRequestOptions): Promise<CanvasDocumentDTO[]> {
  return canvasRequest('/canvases', { signal: options?.signal }, decodeCanvasDocumentList)
}

export function createCanvas(
  title?: string,
  options?: CanvasRequestOptions,
): Promise<CanvasDocumentDTO> {
  const body: CreateCanvasRequestDTO = title === undefined ? {} : { title }
  return canvasRequest('/canvases', {
    method: 'POST',
    body,
    signal: options?.signal,
  }, decodeCanvasDocument)
}

export function getCanvas(
  canvasId: UUIDString,
  options?: CanvasRequestOptions,
): Promise<CanvasSnapshotDTO> {
  return canvasRequest(`/canvases/${canvasId}`, { signal: options?.signal }, decodeCanvasSnapshot)
}

/**
 * POST /canvases/{id}/commands：应用命令批并返回 graph patch。
 * 响应通过本地 reducer 直接应用；重复/过期 patch 会被忽略，
 * 存在 gap 时通过 getCanvasChanges 恢复。
 */
export function postCanvasCommands(
  canvasId: UUIDString,
  request: ApplyCanvasCommandsRequestDTO,
  options?: CanvasRequestOptions,
): Promise<CanvasPatchDTO> {
  return canvasRequest(`/canvases/${canvasId}/commands`, {
    method: 'POST',
    body: request,
    signal: options?.signal,
  }, decodeCanvasPatch)
}

/**
 * GET /canvases/{id}/changes?afterVersion=N：返回连续 patches 或
 * 必须整体替换的全量 snapshot（gap 恢复 / resync）。
 */
export function getCanvasChanges(
  canvasId: UUIDString,
  afterVersion: CanvasVersion,
  options?: CanvasRequestOptions,
): Promise<CanvasChangesDTO> {
  const query = new URLSearchParams({ afterVersion })
  return canvasRequest(`/canvases/${canvasId}/changes?${query}`, {
    signal: options?.signal,
  }, decodeCanvasChanges)
}

/**
 * GET /canvases/{id}/events/stream?afterVersion=N：版本事件 SSE。
 * 'version' 事件触发 changes 拉取；'resync' 事件触发全量快照。
 * 断线重连时使用客户端最后已知版本。
 */
export function createCanvasRealtimeStream(
  canvasId: UUIDString,
  afterVersion: CanvasVersion = '0',
): EventSource {
  const query = new URLSearchParams({ afterVersion })
  return new EventSource(
    `${apiBaseUrl}/canvases/${encodeURIComponent(canvasId)}/events/stream?${query}`,
  )
}

/**
 * POST /canvases/{id}/thread/messages：Canvas 空 Thread 的原子首次发送。
 * 创建绑定到本画布的 Thread 并发送有序 USER_MESSAGE contents；
 * 返回绑定后的 threadId 与携带 threadId 的最新 document。
 */
export function sendCanvasThreadFirstSend(
  canvasId: UUIDString,
  request: CanvasThreadFirstSendRequestDTO,
  options?: CanvasRequestOptions,
): Promise<CanvasThreadFirstSendResponseDTO> {
  return canvasRequest(`/canvases/${canvasId}/thread/messages`, {
    method: 'POST',
    body: request,
    signal: options?.signal,
  }, decodeCanvasThreadFirstSendResponse)
}

export function listCanvasFunctionModels(
  options?: CanvasRequestOptions,
): Promise<CanvasFunctionModelDTO[]> {
  return canvasRequest('/canvas-function-models', { signal: options?.signal })
}

export function getCanvasResourceOriginalUrl(
  canvasId: UUIDString,
  resourceId: UUIDString,
  options?: CanvasRequestOptions,
): Promise<CanvasPresignedUrlDTO> {
  return canvasRequest(`/canvases/${canvasId}/resources/${resourceId}/download-url`, {
    method: 'POST',
    signal: options?.signal,
  })
}

export function getCanvasResourcePreviewUrl(
  canvasId: UUIDString,
  resourceId: UUIDString,
  options?: CanvasRequestOptions,
): Promise<CanvasPresignedUrlDTO> {
  return canvasRequest(`/canvases/${canvasId}/resources/${resourceId}/preview-url`, {
    method: 'POST',
    signal: options?.signal,
  })
}

export function startCanvasFunctionRun(
  canvasId: UUIDString,
  nodeId: UUIDString,
  request: CanvasFunctionRunRequestDTO,
  options?: CanvasRequestOptions,
): Promise<CanvasFunctionRunDTO> {
  return canvasRequest(`/canvases/${canvasId}/nodes/${nodeId}/runs`, {
    method: 'POST',
    body: request,
    signal: options?.signal,
  })
}

export function getCanvasFunctionRun(
  canvasId: UUIDString,
  nodeId: UUIDString,
  options?: CanvasRequestOptions,
): Promise<CanvasFunctionRunDTO> {
  return canvasRequest(`/canvases/${canvasId}/nodes/${nodeId}/run`, {
    signal: options?.signal,
  })
}

export function cancelCanvasFunctionRun(
  canvasId: UUIDString,
  nodeId: UUIDString,
  request: CanvasFunctionRunRequestDTO,
  options?: CanvasRequestOptions,
): Promise<CanvasFunctionRunDTO> {
  return canvasRequest(`/canvases/${canvasId}/nodes/${nodeId}/run/cancel`, {
    method: 'POST',
    body: request,
    signal: options?.signal,
  })
}

async function canvasRequest<T>(
  path: string,
  options: {
    method?: 'GET' | 'POST'
    body?: unknown
    signal?: AbortSignal
  } = {},
  decode?: (data: unknown) => T,
): Promise<T> {
  let response: Response
  try {
    response = await fetch(`${apiBaseUrl}${path}`, {
      method: options.method ?? 'GET',
      headers: {
        Accept: 'application/json',
        'Accept-Language': getLocale(),
        ...(options.body === undefined ? {} : { 'Content-Type': 'application/json' }),
      },
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    })
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') {
      throw error
    }
    throw new ApiError(error instanceof Error ? error.message : translate('shared.requestFailed'))
  }

  let envelope: unknown
  try {
    envelope = await response.json()
  } catch {
    throw new ApiError(
      `Canvas API returned invalid JSON (HTTP ${response.status})`,
      response.status,
    )
  }
  if (!isResultEnvelope<T>(envelope)) {
    throw new ApiError(
      `Canvas API returned an invalid Result envelope (HTTP ${response.status})`,
      response.status,
    )
  }
  if (!response.ok || envelope.status < 200 || envelope.status >= 300) {
    const errorStatus = response.ok ? envelope.status : response.status
    throw new ApiError(
      envelope.message || translate('shared.requestFailed'),
      errorStatus,
      envelope.code,
      envelope.errors ?? undefined,
    )
  }
  return decode ? decode(envelope.data) : (envelope.data as T)
}

function isResultEnvelope<T>(value: unknown): value is ResultEnvelope<T> {
  if (!value || typeof value !== 'object') {
    return false
  }
  const candidate = value as Partial<ResultEnvelope<T>>
  return (
    typeof candidate.status === 'number'
    && typeof candidate.code === 'string'
    && typeof candidate.message === 'string'
    && 'data' in candidate
  )
}

/**
 * 严格 wire 解码：只接受真实 convention4j 输出（long 为 decimal string、
 * required-null 显式输出），非法/超 safe integer 一律 fail closed（ApiError），
 * 绝不静默降级为 0/null。
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

function decodeCanvasDocument(value: unknown): CanvasDocumentDTO {
  const candidate = requireRecord(value, 'document')
  const threadId = candidate.threadId
  if (threadId !== null && threadId !== undefined && !UUID_SHAPE.test(String(threadId))) {
    throw invalidPayload('document.threadId must be a canonical UUID string or null')
  }
  return {
    id: requireUuid(candidate.id, 'document.id'),
    title: requireString(candidate.title, 'document.title'),
    version: requireCanvasVersion(candidate.version, 'document.version'),
    threadId: (threadId ?? null) as UUIDString | null,
    createdAt: requireString(candidate.createdAt, 'document.createdAt'),
    updatedAt: requireString(candidate.updatedAt, 'document.updatedAt'),
  }
}

function decodeCanvasDocumentList(value: unknown): CanvasDocumentDTO[] {
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
    x: requireInt(candidate.x, `${path}.x`),
    y: requireInt(candidate.y, `${path}.y`),
    width: requireInt(candidate.width, `${path}.width`),
    height: requireInt(candidate.height, `${path}.height`),
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

function decodeCanvasSnapshot(value: unknown): CanvasSnapshotDTO {
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

function decodeCanvasPatch(value: unknown): CanvasPatchDTO {
  const candidate = requireRecord(value, 'patch')
  return {
    baseVersion: requireCanvasVersion(candidate.baseVersion, 'patch.baseVersion'),
    version: requireCanvasVersion(candidate.version, 'patch.version'),
    groups: requireStringArray(candidate.groups, 'patch.groups').map(decodeCanvasGroupPatch),
    nodes: requireStringArray(candidate.nodes, 'patch.nodes').map(decodeCanvasNodePatch),
    links: requireStringArray(candidate.links, 'patch.links').map(decodeCanvasLinkPatch),
  }
}

function decodeCanvasChanges(value: unknown): CanvasChangesDTO {
  const candidate = requireRecord(value, 'changes')
  return {
    patches: requireStringArray(candidate.patches, 'changes.patches').map(decodeCanvasPatch),
    snapshot: candidate.snapshot === null || candidate.snapshot === undefined
      ? null
      : decodeCanvasSnapshot(candidate.snapshot),
  }
}

function decodeCanvasThreadFirstSendResponse(value: unknown): CanvasThreadFirstSendResponseDTO {
  const candidate = requireRecord(value, 'first send response')
  return {
    threadId: requireUuid(candidate.threadId, 'first send response.threadId'),
    document: decodeCanvasDocument(candidate.document),
  }
}
