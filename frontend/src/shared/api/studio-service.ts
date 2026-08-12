import { ApiError, apiBaseUrl } from '@/shared/api/client'
import type { ResultEnvelope } from '@/shared/api/contracts/base'
import type {
  ApplyCanvasCommandsRequestDTO,
  CanvasChangesDTO,
  CanvasDocumentDTO,
  CanvasFunctionModelDTO,
  CanvasFunctionRunDTO,
  CanvasFunctionRunRequestDTO,
  CanvasPatchDTO,
  CanvasPresignedUrlDTO,
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
  return canvasRequest('/canvases', { signal: options?.signal })
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
  })
}

export function getCanvas(
  canvasId: UUIDString,
  options?: CanvasRequestOptions,
): Promise<CanvasSnapshotDTO> {
  return canvasRequest(`/canvases/${canvasId}`, { signal: options?.signal })
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
  })
}

/**
 * GET /canvases/{id}/changes?afterVersion=N：返回连续 patches 或
 * 必须整体替换的全量 snapshot（gap 恢复 / resync）。
 */
export function getCanvasChanges(
  canvasId: UUIDString,
  afterVersion: number,
  options?: CanvasRequestOptions,
): Promise<CanvasChangesDTO> {
  const query = new URLSearchParams({ afterVersion: String(afterVersion) })
  return canvasRequest(`/canvases/${canvasId}/changes?${query}`, {
    signal: options?.signal,
  })
}

/**
 * GET /canvases/{id}/events/stream?afterVersion=N：版本事件 SSE。
 * 'version' 事件触发 changes 拉取；'resync' 事件触发全量快照。
 * 断线重连时使用客户端最后已知版本。
 */
export function createCanvasRealtimeStream(
  canvasId: UUIDString,
  afterVersion = 0,
): EventSource {
  const query = new URLSearchParams({ afterVersion: String(afterVersion) })
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
  })
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
  return envelope.data
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
