import { request } from '@/shared/api/client'
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
 * Canvas 产品 HTTP 客户端：委托 shared/api/client 的类型化 fetch 核心 request()。
 */

export interface CanvasRequestOptions {
  signal?: AbortSignal
}

export function listCanvases(options?: CanvasRequestOptions): Promise<CanvasDocumentDTO[]> {
  return request('GET /api/canvases', {}, { signal: options?.signal })
}

export function createCanvas(
  title?: string,
  options?: CanvasRequestOptions,
): Promise<CanvasDocumentDTO> {
  const body: CreateCanvasRequestDTO = title === undefined ? {} : { title }
  return request('POST /api/canvases', { body }, { signal: options?.signal })
}

export function getCanvas(
  canvasId: string,
  options?: CanvasRequestOptions,
): Promise<CanvasSnapshotDTO> {
  return request(
    'GET /api/canvases/{canvasId}',
    { path: { canvasId } },
    { signal: options?.signal },
  )
}

/**
 * POST /canvases/{id}/commands：应用类型化命令批并返回 graph patch。
 * 响应通过本地 reducer 直接应用；重复/过期 patch 会被忽略。
 */
export function postCanvasCommands(
  canvasId: string,
  body: ApplyCanvasCommandsRequestDTO,
  options?: CanvasRequestOptions,
): Promise<CanvasPatchDTO> {
  return request(
    'POST /api/canvases/{canvasId}/commands',
    { path: { canvasId }, body },
    { signal: options?.signal },
  )
}

export function listCanvasFunctions(
  options?: CanvasRequestOptions,
): Promise<CanvasFunctionDefinitionDTO[]> {
  return request('GET /api/canvas-functions', {}, { signal: options?.signal })
}

export function getCanvasResourceOriginalUrl(
  canvasId: string,
  resourceId: string,
  options?: CanvasRequestOptions,
): Promise<CanvasPresignedUrlDTO> {
  return request(
    'POST /api/canvases/{canvasId}/resources/{resourceId}/download-url',
    { path: { canvasId, resourceId } },
    { signal: options?.signal },
  )
}

export function getCanvasResourcePreviewUrl(
  canvasId: string,
  resourceId: string,
  options?: CanvasRequestOptions,
): Promise<CanvasPresignedUrlDTO> {
  return request(
    'POST /api/canvases/{canvasId}/resources/{resourceId}/preview-url',
    { path: { canvasId, resourceId } },
    { signal: options?.signal },
  )
}

export function startCanvasFunctionRun(
  canvasId: string,
  nodeId: string,
  body: CanvasFunctionRunRequestDTO,
  options?: CanvasRequestOptions,
): Promise<CanvasFunctionRunDTO> {
  return request(
    'POST /api/canvases/{canvasId}/nodes/{nodeId}/function-run',
    { path: { canvasId, nodeId }, body },
    { signal: options?.signal },
  )
}

export function getCanvasFunctionRun(
  canvasId: string,
  nodeId: string,
  options?: CanvasRequestOptions,
): Promise<CanvasFunctionRunDTO> {
  return request(
    'GET /api/canvases/{canvasId}/nodes/{nodeId}/function-run',
    { path: { canvasId, nodeId } },
    { signal: options?.signal },
  )
}

export function cancelCanvasFunctionRun(
  canvasId: string,
  nodeId: string,
  body: CanvasFunctionCancelRequestDTO,
  options?: CanvasRequestOptions,
): Promise<CanvasFunctionRunDTO> {
  return request(
    'POST /api/canvases/{canvasId}/nodes/{nodeId}/function-run/cancel',
    { path: { canvasId, nodeId }, body },
    { signal: options?.signal },
  )
}

export function resolveCanvasFunctionRun(
  canvasId: string,
  nodeId: string,
  body: CanvasFunctionUnknownResolutionDTO,
  options?: CanvasRequestOptions,
): Promise<CanvasFunctionRunDTO> {
  return request(
    'POST /api/canvases/{canvasId}/nodes/{nodeId}/function-run/resolve',
    { path: { canvasId, nodeId }, body },
    { signal: options?.signal },
  )
}
