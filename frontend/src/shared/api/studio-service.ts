import { apiClient } from '@/shared/api/client'

/**
 * Studio HTTP contract for the global single-instance product.
 *
 * Canvas list/create are durable. The editor keeps its current interaction projection locally.
 */

export interface CanvasDocumentDTO {
  id: string
  title: string
  revision: string
  homeViewportJson: string
}

export function listCanvases(): Promise<CanvasDocumentDTO[]> {
  return apiClient.get<CanvasDocumentDTO[]>('/canvases')
}

export function createCanvas(title: string): Promise<CanvasDocumentDTO> {
  return apiClient.post<CanvasDocumentDTO>('/canvases', { title })
}
