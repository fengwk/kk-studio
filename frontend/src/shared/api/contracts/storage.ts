/**
 * 共享存储上传的媒体类别。媒体类型（mediaType）负责精确分类；mediaKind 是
 * 客户端与存储层之间的粗粒度路由/限制键。
 */
export type StorageMediaKind = 'image' | 'video' | 'audio' | 'file'

/**
 * 渲染期 blob URL 的解码结果（download-url / preview-url）：只在资源实际预览或下载时换取。
 * 原件端点给出权威 mediaType 与 sizeBytes（wire 为 Java long 十进制字符串，解码为安全整数）；
 * preview 端点二者为 null。
 */
export interface StorageBlobUrl {
  url: string
  mediaType: string | null
  sizeBytes: number | null
}
