import { ApiError, apiBaseUrl } from '@/shared/api/client'
import type { ResultEnvelope } from '@/shared/api/contracts/base'
import type {
  ApplyCanvasCommandsRequestDTO,
  CanvasDocumentDTO,
  CanvasFunctionModelDTO,
  CanvasFunctionRunDTO,
  CanvasFunctionRunRequestDTO,
  CanvasPresignedUrlDTO,
  CanvasResourceDTO,
  CanvasSnapshotDTO,
  CanvasUploadReservationDTO,
  CreateCanvasRequestDTO,
  CreateCanvasUploadRequestDTO,
  DecimalString,
} from '@/shared/api/contracts/studio'
import { getLocale, translate } from '@/shared/i18n'

export interface CanvasRequestOptions {
  signal?: AbortSignal
}

const FORBIDDEN_REQUEST_HEADERS = new Set([
  'accept-charset',
  'accept-encoding',
  'access-control-request-headers',
  'access-control-request-method',
  'connection',
  'content-length',
  'cookie',
  'cookie2',
  'date',
  'dnt',
  'expect',
  'host',
  'keep-alive',
  'origin',
  'permissions-policy',
  'referer',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
  'user-agent',
  'via',
  'x-http-method',
  'x-http-method-override',
  'x-method-override',
])

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
  canvasId: DecimalString,
  options?: CanvasRequestOptions,
): Promise<CanvasSnapshotDTO> {
  return canvasRequest(`/canvases/${canvasId}`, { signal: options?.signal })
}

export function applyCanvasCommands(
  canvasId: DecimalString,
  request: ApplyCanvasCommandsRequestDTO,
  options?: CanvasRequestOptions,
): Promise<CanvasSnapshotDTO> {
  return canvasRequest(`/canvases/${canvasId}/commands`, {
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

export function reserveCanvasUpload(
  canvasId: DecimalString,
  request: CreateCanvasUploadRequestDTO,
  options?: CanvasRequestOptions,
): Promise<CanvasUploadReservationDTO> {
  return canvasRequest(`/canvases/${canvasId}/uploads`, {
    method: 'POST',
    body: request,
    signal: options?.signal,
  })
}

export async function uploadCanvasFile(
  reservation: CanvasUploadReservationDTO,
  file: Blob,
  options?: CanvasRequestOptions,
): Promise<void> {
  let response: Response
  try {
    response = await fetch(reservation.url, {
      method: reservation.method,
      headers: browserSafePresignedHeaders(reservation.headers),
      body: file,
      signal: options?.signal,
    })
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') {
      throw error
    }
    throw new ApiError(
      error instanceof Error ? error.message : 'Direct upload failed',
    )
  }
  if (!response.ok) {
    throw new ApiError(`Direct upload failed with HTTP ${response.status}`, response.status)
  }
}

export function completeCanvasUpload(
  canvasId: DecimalString,
  uploadId: DecimalString,
  options?: CanvasRequestOptions,
): Promise<CanvasResourceDTO> {
  return canvasRequest(`/canvases/${canvasId}/uploads/${uploadId}/complete`, {
    method: 'POST',
    signal: options?.signal,
  })
}

export function getCanvasResourceOriginalUrl(
  canvasId: DecimalString,
  resourceId: DecimalString,
  options?: CanvasRequestOptions,
): Promise<CanvasPresignedUrlDTO> {
  return canvasRequest(`/canvases/${canvasId}/resources/${resourceId}/download-url`, {
    method: 'POST',
    signal: options?.signal,
  })
}

export function getCanvasResourcePreviewUrl(
  canvasId: DecimalString,
  resourceId: DecimalString,
  options?: CanvasRequestOptions,
): Promise<CanvasPresignedUrlDTO> {
  return canvasRequest(`/canvases/${canvasId}/resources/${resourceId}/preview-url`, {
    method: 'POST',
    signal: options?.signal,
  })
}

export function startCanvasFunctionRun(
  canvasId: DecimalString,
  nodeId: DecimalString,
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
  canvasId: DecimalString,
  nodeId: DecimalString,
  options?: CanvasRequestOptions,
): Promise<CanvasFunctionRunDTO> {
  return canvasRequest(`/canvases/${canvasId}/nodes/${nodeId}/run`, {
    signal: options?.signal,
  })
}

export function cancelCanvasFunctionRun(
  canvasId: DecimalString,
  nodeId: DecimalString,
  request: CanvasFunctionRunRequestDTO,
  options?: CanvasRequestOptions,
): Promise<CanvasFunctionRunDTO> {
  return canvasRequest(`/canvases/${canvasId}/nodes/${nodeId}/run/cancel`, {
    method: 'POST',
    body: request,
    signal: options?.signal,
  })
}

export function browserSafePresignedHeaders(
  headers: Record<string, string>,
): Record<string, string> {
  const safe: Record<string, string> = {}
  for (const [name, value] of Object.entries(headers)) {
    const normalized = name.trim().toLowerCase()
    if (
      !normalized
      || normalized.startsWith('proxy-')
      || normalized.startsWith('sec-')
      || FORBIDDEN_REQUEST_HEADERS.has(normalized)
    ) {
      continue
    }
    safe[name] = value
  }
  return safe
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
