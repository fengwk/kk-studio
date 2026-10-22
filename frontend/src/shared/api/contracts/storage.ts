/**
 * 共享存储上传的媒体类别。媒体类型（mediaType）负责精确分类；mediaKind 是
 * 客户端与存储层之间的粗粒度路由/限制键。
 */
export type StorageMediaKind = 'image' | 'video' | 'audio' | 'file'

/** 直传签名（PENDING 预留携带）：客户端按 method/url/headers PUT 对象字节，随后 complete。 */
export interface StoragePresignedPutDTO {
  method: 'PUT'
  url: string
  headers: Record<string, string>
}

/**
 * 上传对象：`POST /api/storage/uploads`（预留）与
 * `POST /api/storage/uploads/{id}/complete` 返回同一形态。
 *
 * - state === 'PENDING'：对象尚未落库，客户端必须按 presignedPut 直传后 complete；
 * - state === 'READY'：sha256 命中已有内容（去重），无需直传，直接 complete。
 *
 * `id` 是持久的 upload 句柄：USER_MESSAGE ATTACHMENT 引用它，释放也删除它；
 * complete 后句柄不变（blobId 仅表示落库后的持久资源，客户端通常无需使用）。
 *
 * `expiresAt` 只是响应时刻按当前上传有效期换算的快照；服务端始终以
 * 创建时间 + 当下配置的上传有效期判定过期，之后修改配置会立即改变实际上传有效期。
 */
export type StorageUploadDTO =
  | {
      id: string
      state: 'PENDING'
      blobId: string | null
      presignedPut: StoragePresignedPutDTO
      expiresAt: number
    }
  | {
      id: string
      state: 'READY'
      blobId: string
      presignedPut: null
      expiresAt: number
    }

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

