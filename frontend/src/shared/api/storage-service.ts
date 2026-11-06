import { ApiError, request } from '@/shared/api/client'
import type {
  StoragePresignedUrlDTO,
  StorageUploadDTO,
  StorageUploadReserveRequestDTO,
} from '@/shared/api/generated/contracts'

/** 直传签名响应中禁止浏览器发送的请求头（浏览器会拒绝或改写它们）。 */
export const FORBIDDEN_UPLOAD_HEADERS = new Set([
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

/**
 * 共享存储上传/资源面：
 * - POST /api/platform/storage/uploads（预留，PENDING=需直传 / READY=sha256 命中免直传）
 * - 直传 PUT（仅 PENDING 预留；跨域签名 URL，使用 fetch）
 * - POST /api/platform/storage/uploads/{id}/complete（返回同一 StorageUploadDTO，句柄不变）
 * - DELETE /api/platform/storage/uploads/{id}（释放句柄；绝不按 blobId 删除）
 * - POST /api/platform/storage/blobs/{blobId}/download-url|preview-url（渲染期解析）
 *
 * 该契约绝不暴露 bucket/key，客户端也不能提交对象 key。
 */
export function createStorageService() {
  const getBlobDownloadUrl = (blobId: string): Promise<StoragePresignedUrlDTO> =>
    request('POST /api/platform/storage/blobs/{blobId}/download-url', {
      path: { blobId },
    })

  return {
    reserveUpload: (body: StorageUploadReserveRequestDTO): Promise<StorageUploadDTO> =>
      request('POST /api/platform/storage/uploads', { body }),
    completeUpload: (uploadId: string): Promise<StorageUploadDTO> =>
      request('POST /api/platform/storage/uploads/{uploadId}/complete', {
        path: { uploadId },
      }),
    deleteUpload: (uploadId: string): Promise<void> =>
      request('DELETE /api/platform/storage/uploads/{uploadId}', { path: { uploadId } }),
    /** 下载 URL 端点（原件）：给出权威 mediaType 与 sizeBytes。 */
    getBlobDownloadUrl,
    getBlobPreviewUrl: (blobId: string): Promise<StoragePresignedUrlDTO> =>
      request('POST /api/platform/storage/blobs/{blobId}/preview-url', {
        path: { blobId },
      }),
    /** 直传对象字节到签名 URL；仅发送签名响应提供的浏览器安全请求头。 */
    uploadFile: async (
      presignedPut: StoragePresignedUrlDTO,
      file: Blob,
      signal?: AbortSignal,
    ): Promise<void> => {
      const headers: Record<string, string> = {}
      for (const [name, value] of Object.entries(presignedPut.headers)) {
        if (!FORBIDDEN_UPLOAD_HEADERS.has(name.toLowerCase())) {
          headers[name] = value
        }
      }
      let response: Response
      try {
        response = await fetch(presignedPut.url, {
          method: presignedPut.method,
          headers,
          body: file,
          signal,
        })
      } catch (error) {
        // jsdom 的 DOMException 不继承 Error，跨 realm 时用 name 判定中止。
        if (
          (error instanceof Error && error.name === 'AbortError')
          || (typeof DOMException !== 'undefined'
            && error instanceof DOMException
            && error.name === 'AbortError')
        ) {
          throw error
        }
        throw new ApiError(
          error instanceof Error ? error.message : 'Direct upload failed',
        )
      }
      if (!response.ok) {
        throw new ApiError(`Direct upload failed with HTTP ${response.status}`, response.status)
      }
    },
  }
}

export type StorageService = ReturnType<typeof createStorageService>

export const storageService = createStorageService()
