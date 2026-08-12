import { createContext } from 'react'

/** durable RESOURCE 的渲染期解析结果（各自可为 null，表示该端点解析失败）。 */
export interface ResourceBlobUrls {
  /** presigned-original：下载/原图。 */
  original: string | null
  /** presigned-preview：image/video 内联预览。 */
  preview: string | null
  /** storage_blob 的权威媒体类型；不从文件名或 URL 猜测。 */
  mediaType: string | null
  /** storage_blob 的权威原件字节数。 */
  sizeBytes: number | null
}

/**
 * durable RESOURCE blob URL 的渲染期解析回调；由面板外部的适配层（如 ChatPanel）
 * 注入 shared storage service，panel 本身保持 API 无关。返回 null 表示整体失败。
 */
export const ResourceBlobUrlContext = createContext<
  ((blobId: string) => Promise<ResourceBlobUrls | null>) | null
>(null)
