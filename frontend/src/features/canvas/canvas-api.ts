import { ApiError, apiBaseUrl } from '@/shared/api/client'
import type { ResultEnvelope } from '@/shared/api/contracts/base'
import { getLocale, translate } from '@/shared/i18n'
import type {
  ApplyCanvasCommandsRequestDTO,
  CanvasDocumentDTO,
  CanvasFunctionCancelRequestDTO,
  CanvasFunctionDefinitionDTO,
  CanvasFunctionRunDTO,
  CanvasFunctionRunRequestDTO,
  CanvasFunctionUnknownResolutionDTO,
  CanvasPatchDTO,
  CanvasPresignedUrlDTO,
  CanvasSnapshotDTO,
  CreateCanvasRequestDTO,
} from '@/shared/api/generated/contracts'

/**
 * Canvas 产品 HTTP 客户端：只负责路径、方法、Body、AbortSignal 与
 * Result envelope/错误映射，wire 解码交给 shared 的 codec。
 */

export interface CanvasRequestOptions {
  signal?: AbortSignal
}

export function listCanvases(options?: CanvasRequestOptions): Promise<CanvasDocumentDTO[]> {
  return canvasRequest<CanvasDocumentDTO[]>('/canvases', { signal: options?.signal })
}

export function createCanvas(
  title?: string,
  options?: CanvasRequestOptions,
): Promise<CanvasDocumentDTO> {
  const body: CreateCanvasRequestDTO = title === undefined ? {} : { title }
  return canvasRequest<CanvasDocumentDTO>('/canvases', {
    method: 'POST',
    body,
    signal: options?.signal,
  })
}

export function getCanvas(
  canvasId: string,
  options?: CanvasRequestOptions,
): Promise<CanvasSnapshotDTO> {
  return canvasRequest<CanvasSnapshotDTO>(`/canvases/${canvasId}`, { signal: options?.signal })
}

/**
 * POST /canvases/{id}/commands：应用类型化命令批并返回 graph patch。
 * 响应通过本地 reducer 直接应用；重复/过期 patch 会被忽略。
 */
export function postCanvasCommands(
  canvasId: string,
  request: ApplyCanvasCommandsRequestDTO,
  options?: CanvasRequestOptions,
): Promise<CanvasPatchDTO> {
  return canvasRequest<CanvasPatchDTO>(`/canvases/${canvasId}/commands`, {
    method: 'POST',
    body: request,
    signal: options?.signal,
  })
}

export function listCanvasFunctions(
  options?: CanvasRequestOptions,
): Promise<CanvasFunctionDefinitionDTO[]> {
  return canvasRequest<CanvasFunctionDefinitionDTO[]>('/canvas-functions', { signal: options?.signal })
}

export function getCanvasResourceOriginalUrl(
  canvasId: string,
  resourceId: string,
  options?: CanvasRequestOptions,
): Promise<CanvasPresignedUrlDTO> {
  return canvasRequest<CanvasPresignedUrlDTO>(`/canvases/${canvasId}/resources/${resourceId}/download-url`, {
    method: 'POST',
    signal: options?.signal,
  })
}

export function getCanvasResourcePreviewUrl(
  canvasId: string,
  resourceId: string,
  options?: CanvasRequestOptions,
): Promise<CanvasPresignedUrlDTO> {
  return canvasRequest<CanvasPresignedUrlDTO>(`/canvases/${canvasId}/resources/${resourceId}/preview-url`, {
    method: 'POST',
    signal: options?.signal,
  })
}

export function startCanvasFunctionRun(
  canvasId: string,
  nodeId: string,
  request: CanvasFunctionRunRequestDTO,
  options?: CanvasRequestOptions,
): Promise<CanvasFunctionRunDTO> {
  return canvasRequest<CanvasFunctionRunDTO>(`/canvases/${canvasId}/nodes/${nodeId}/function-run`, {
    method: 'POST',
    body: request,
    signal: options?.signal,
  })
}

export function getCanvasFunctionRun(
  canvasId: string,
  nodeId: string,
  options?: CanvasRequestOptions,
): Promise<CanvasFunctionRunDTO> {
  return canvasRequest<CanvasFunctionRunDTO>(`/canvases/${canvasId}/nodes/${nodeId}/function-run`, {
    signal: options?.signal,
  })
}

export function cancelCanvasFunctionRun(
  canvasId: string,
  nodeId: string,
  request: CanvasFunctionCancelRequestDTO,
  options?: CanvasRequestOptions,
): Promise<CanvasFunctionRunDTO> {
  return canvasRequest<CanvasFunctionRunDTO>(`/canvases/${canvasId}/nodes/${nodeId}/function-run/cancel`, {
    method: 'POST',
    body: request,
    signal: options?.signal,
  })
}

export function resolveCanvasFunctionRun(
  canvasId: string,
  nodeId: string,
  request: CanvasFunctionUnknownResolutionDTO,
  options?: CanvasRequestOptions,
): Promise<CanvasFunctionRunDTO> {
  return canvasRequest<CanvasFunctionRunDTO>(`/canvases/${canvasId}/nodes/${nodeId}/function-run/resolve`, {
    method: 'POST',
    body: request,
    signal: options?.signal,
  })
}

async function canvasRequest<T>(
  path: string,
  options: {
    method?: 'GET' | 'POST' | 'DELETE'
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
  return envelope.data as T
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
