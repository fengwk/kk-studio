import { apiClient } from '@/shared/api/client'
import type { CanvasDocumentDTO } from '@/shared/api/contracts/studio'

/**
 * Studio HTTP contract for the global single-instance product.
 *
 * Canvas list/create are durable. The editor keeps its current interaction projection locally.
 */

export function listCanvases(): Promise<CanvasDocumentDTO[]> {
  return apiClient.get<CanvasDocumentDTO[]>('/canvases')
}

export function createCanvas(title: string): Promise<CanvasDocumentDTO> {
  return apiClient.post<CanvasDocumentDTO>('/canvases', { title })
}
