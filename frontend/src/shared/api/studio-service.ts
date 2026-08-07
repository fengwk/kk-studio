import { apiClient } from '@/shared/api/client'
import type { CanvasDocumentDTO } from '@/shared/api/contracts/studio'

/**
 * 全局单实例产品的 Studio HTTP 契约。
 *
 * Canvas 的列表/创建是持久的。编辑器在本地维护其当前投影。
 */

export function listCanvases(): Promise<CanvasDocumentDTO[]> {
  return apiClient.get<CanvasDocumentDTO[]>('/canvases')
}

export function createCanvas(title: string): Promise<CanvasDocumentDTO> {
  return apiClient.post<CanvasDocumentDTO>('/canvases', { title })
}
