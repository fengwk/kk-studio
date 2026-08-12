import type { BackendDateTime } from '@/shared/api/contracts/base'

export interface S3PresignedRequestDTO {
  key: string
  contentType?: string
  expiresInSeconds?: number
}

export interface S3PresignedResponseDTO {
  bucket: string
  key: string
  method: string
  url: string
  headers: Record<string, string>
  expiresAt: BackendDateTime
}

/**
 * 共享存储上传的媒体类别。媒体类型（mediaType）负责精确分类；mediaKind 是
 * 客户端与存储层之间的粗粒度路由/限制键。
 */
export type StorageMediaKind = 'image' | 'video' | 'audio' | 'file'

/**
 * 上传预留请求：`POST /api/storage/uploads`。
 *
 * 服务端按 filename/mediaType/sizeBytes/sha256 预留对象并返回 upload 句柄；
 * 响应绝不暴露 bucket/key，客户端也不能提交对象 key。mediaKind 只是客户端的
 * 本地 UI 分类/限制键，不属于 wire 契约。
 */
export interface StorageUploadReserveRequestDTO {
  filename: string
  mediaType: string
  /** 规范十进制字符串或 JS number；客户端始终使用整数字节数。 */
  sizeBytes: number
  sha256: string
}

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
 */
export type StorageUploadDTO =
  | {
      id: string
      state: 'PENDING'
      blobId: string | null
      presignedPut: StoragePresignedPutDTO
      expiresAt: BackendDateTime
    }
  | {
      id: string
      state: 'READY'
      blobId: string
      presignedPut: null
      expiresAt: BackendDateTime
    }

/**
 * 渲染期 blob URL：`GET /api/storage/blobs/{blobId}/presigned-original`
 * 与 `GET /api/storage/blobs/{blobId}/presigned-preview`。只在资源实际渲染
 * （预览/下载）时才调用，避免为未展示的资源提前换取 URL。
 */
export interface StoragePresignedUrlDTO {
  url: string
  expiresAt: BackendDateTime
  /** 原件端点返回的权威 Blob 媒体类型；preview 端点可省略。 */
  mediaType?: string | null
  /**
   * 原件端点返回的权威 Blob 字节数；preview 端点可省略。
   * wire 是 Java long 的 decimal string|null，由 storage-service adapter 归一化为 number|null。
   */
  sizeBytes?: number | null
}
