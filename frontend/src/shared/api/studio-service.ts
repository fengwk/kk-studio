import { ApiError, apiBaseUrl } from '@/shared/api/client'
import type { ResultEnvelope } from '@/shared/api/contracts/base'
import {
  decodeCanvasDocument,
  decodeCanvasDocumentList,
  decodeCanvasPatch,
  decodeCanvasSnapshot,
} from '@/shared/api/studio-codec'
import type {
  ApplyCanvasCommandsRequestDTO,
  CanvasDocumentDTO,
  CanvasFunctionModelDTO,
  CanvasFunctionRunDTO,
  CanvasFunctionRunRequestDTO,
  CanvasPatchDTO,
  CanvasPresignedUrlDTO,
  CanvasSnapshotDTO,
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
 * baseVersion 不连续时读取权威 Snapshot 恢复。
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
