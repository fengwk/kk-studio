import { ApiError, apiClient, type HttpClient } from '@/shared/api/client'
import type {
  StoragePresignedPutDTO,
  StoragePresignedUrlDTO,
  StorageUploadDTO,
  StorageUploadReserveRequestDTO,
} from '@/shared/api/contracts/storage'

/** 直传签名响应中禁止浏览器发送的请求头（浏览器会拒绝或改写它们）。 */
const FORBIDDEN_UPLOAD_HEADERS = new Set([
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
 * - POST /storage/uploads（预留，PENDING=需直传 / READY=sha256 命中免直传）
 * - 直传 PUT（仅 PENDING 预留；跨域签名 URL，使用 fetch）
 * - POST /storage/uploads/{id}/complete（返回同一 StorageUploadDTO，句柄不变）
 * - DELETE /storage/uploads/{id}（释放句柄；绝不按 blobId 删除）
 * - POST /storage/blobs/{blobId}/download-url|preview-url（渲染期解析）
 *
 * 该契约绝不暴露 bucket/key，客户端也不能提交对象 key。
 */
export function createStorageService(client: HttpClient = apiClient) {
  const getBlobDownloadUrl = async (blobId: string): Promise<StoragePresignedUrlDTO> => {
    const raw = await client.post<unknown>(
      `/storage/blobs/${encodeURIComponent(blobId)}/download-url`,
    )
    return decodeBlobUrl(raw)
  }

  return {
    reserveUpload: (request: StorageUploadReserveRequestDTO): Promise<StorageUploadDTO> =>
      client.post('/storage/uploads', request),
    completeUpload: (uploadId: string): Promise<StorageUploadDTO> =>
      client.post(`/storage/uploads/${encodeURIComponent(uploadId)}/complete`),
    deleteUpload: (uploadId: string): Promise<void> =>
      client.delete(`/storage/uploads/${encodeURIComponent(uploadId)}`),
    /** 下载 URL 端点（原件）：sizeBytes 是 Java long 的 decimal string|null，严格归一化为 number|null。 */
    getBlobDownloadUrl,
    getBlobPreviewUrl: async (blobId: string): Promise<StoragePresignedUrlDTO> => {
      const raw = await client.post<unknown>(
        `/storage/blobs/${encodeURIComponent(blobId)}/preview-url`,
      )
      return decodeBlobUrl(raw)
    },
    /** 直传对象字节到签名 URL；仅发送签名响应提供的浏览器安全请求头。 */
    uploadFile: async (presignedPut: StoragePresignedPutDTO, file: Blob): Promise<void> => {
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

/**
 * 严格解码 blob 预签名 URL 响应：url 必须是非空字符串；
 * sizeBytes 是 Java long，wire 为 decimal string|number|null，
 * 统一安全转为 number|null（非负且 Number.isSafeInteger），非法即 fail closed。
 */
function decodeBlobUrl(value: unknown): StoragePresignedUrlDTO {
  if (!value || typeof value !== 'object') {
    throw new ApiError('Storage blob URL response is invalid')
  }
  const candidate = value as Partial<StoragePresignedUrlDTO> & Record<string, unknown>
  if (typeof candidate.url !== 'string' || !candidate.url.trim()) {
    throw new ApiError('Storage blob URL response is missing a non-empty url')
  }
  return {
    url: candidate.url,
    expiresAt: candidate.expiresAt as StoragePresignedUrlDTO['expiresAt'],
    mediaType: typeof candidate.mediaType === 'string' ? candidate.mediaType : null,
    sizeBytes: decodeBlobSizeBytes(candidate.sizeBytes),
  }
}

function decodeBlobSizeBytes(value: unknown): number | null {
  if (value === null || value === undefined) {
    return null
  }
  let parsed = Number.NaN
  if (typeof value === 'number') {
    parsed = value
  } else if (typeof value === 'string' && /^(0|[1-9][0-9]*)$/.test(value)) {
    parsed = Number(value)
  }
  if (!Number.isSafeInteger(parsed) || parsed < 0) {
    throw new ApiError('Storage blob URL response sizeBytes must be a non-negative safe integer or null')
  }
  return parsed
}
