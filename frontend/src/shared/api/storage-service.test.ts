import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from '@/shared/api/client'
import { createStorageService } from '@/shared/api/storage-service'
import type {
  StorageUploadPendingDTO,
  StorageUploadReadyDTO,
} from '@/shared/api/generated/contracts'

vi.mock('@/shared/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/client')>()
  return { ...actual, request: vi.fn() }
})

const apiRequest = vi.mocked(request)

/**
 * 共享存储客户端契约测试：API 面（request）为类型化路由，直传面（uploadFile）
 * 负责浏览器安全的签名 URL 直传。覆盖 PENDING=需直传 / READY=sha256 命中免直传、
 * 路由参数、header 过滤与错误映射。
 */
function pendingUpload(id: string): StorageUploadPendingDTO {
  return {
    type: 'PENDING',
    id,
    presignedPut: {
      method: 'PUT',
      url: `https://s3.test/${id}`,
      headers: {
        'x-amz-meta-name': 'a.png',
        'content-type': 'image/png',
        host: 's3.test',
        'content-length': '128',
      },
      expiresAt: 1786492800000,
      mediaType: null,
      sizeBytes: null,
    },
    expiresAt: 1786492800000,
  }
}

function readyUpload(id: string): StorageUploadReadyDTO {
  return {
    type: 'READY',
    id,
    blobId: 'blob-1',
    expiresAt: 1786492800000,
  }
}

describe('storage-service', () => {
  beforeEach(() => {
    apiRequest.mockReset()
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('reserves uploads with the full request body and passes the DTO through', async () => {
    const service = createStorageService()
    const body = {
      filename: 'photo.png',
      mediaType: 'image/png',
      sizeBytes: 64,
      sha256: 'a'.repeat(64),
    }
    apiRequest.mockResolvedValue(pendingUpload('up-1'))

    await expect(service.reserveUpload(body)).resolves.toEqual(pendingUpload('up-1'))
    expect(apiRequest).toHaveBeenCalledWith('POST /api/platform/storage/uploads', { body })
    // wire 请求不含 mediaKind（严格 Jackson 契约只接受四个字段）。
    expect(body).not.toHaveProperty('mediaKind')
  })

  it('routes complete to the upload path and returns the same DTO shape', async () => {
    const service = createStorageService()
    apiRequest.mockResolvedValue(readyUpload('u/1'))

    await expect(service.completeUpload('u/1')).resolves.toEqual(readyUpload('u/1'))
    expect(apiRequest).toHaveBeenCalledWith('POST /api/platform/storage/uploads/{uploadId}/complete', {
      path: { uploadId: 'u/1' },
    })
  })

  it('deletes by the upload handle, never by blob', async () => {
    const service = createStorageService()
    apiRequest.mockResolvedValue(undefined)

    await service.deleteUpload('up-1')
    expect(apiRequest).toHaveBeenCalledWith('DELETE /api/platform/storage/uploads/{uploadId}', {
      path: { uploadId: 'up-1' },
    })
  })

  it('resolves original and preview blob URLs via POST on the blob path', async () => {
    const service = createStorageService()
    const presigned = {
      method: 'GET' as const,
      url: 'https://s3.test/orig',
      headers: {},
      expiresAt: 1786492800000,
      mediaType: 'image/png',
      sizeBytes: '64',
    }
    apiRequest.mockResolvedValue(presigned)

    await expect(service.getBlobDownloadUrl('blob-1')).resolves.toEqual(presigned)
    expect(apiRequest).toHaveBeenCalledWith(
      'POST /api/platform/storage/blobs/{blobId}/download-url',
      { path: { blobId: 'blob-1' } },
    )

    await expect(service.getBlobPreviewUrl('blob-1')).resolves.toEqual(presigned)
    expect(apiRequest).toHaveBeenCalledWith(
      'POST /api/platform/storage/blobs/{blobId}/preview-url',
      { path: { blobId: 'blob-1' } },
    )
  })

  it('PUTs object bytes with signed headers minus browser-forbidden ones', async () => {
    const service = createStorageService()
    const file = new File([new Uint8Array(4)], 'a.png', { type: 'image/png' })
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 200 }))

    await service.uploadFile(pendingUpload('up-1').presignedPut, file)

    expect(fetch).toHaveBeenCalledWith(
      'https://s3.test/up-1',
      expect.objectContaining({
        method: 'PUT',
        body: file,
        headers: {
          'x-amz-meta-name': 'a.png',
          'content-type': 'image/png',
        },
      }),
    )
    const headers = vi.mocked(fetch).mock.calls[0]?.[1]?.headers as Record<string, string>
    expect(headers).not.toHaveProperty('host')
    expect(headers).not.toHaveProperty('content-length')
  })

  it('maps non-2xx direct uploads to ApiError with the HTTP status', async () => {
    const service = createStorageService()
    vi.mocked(fetch).mockResolvedValue(new Response('nope', { status: 403 }))

    await expect(
      service.uploadFile(pendingUpload('up-1').presignedPut, new File([], 'a.txt')),
    ).rejects.toMatchObject({
      name: 'ApiError',
      status: 403,
    })
  })

  it('maps network failures to ApiError without status and rethrows aborts unchanged', async () => {
    const service = createStorageService()
    vi.mocked(fetch).mockRejectedValueOnce(new TypeError('Failed to fetch'))

    await expect(
      service.uploadFile(pendingUpload('up-1').presignedPut, new File([], 'a.txt')),
    ).rejects.toMatchObject({
      name: 'ApiError',
      message: 'Failed to fetch',
    })

    const abort = new DOMException('aborted', 'AbortError')
    vi.mocked(fetch).mockRejectedValueOnce(abort)
    await expect(
      service.uploadFile(pendingUpload('up-1').presignedPut, new File([], 'a.txt')),
    ).rejects.toBe(abort)
  })

  it('maps non-Error fetch rejections to the generic direct-upload message', async () => {
    const service = createStorageService()
    vi.mocked(fetch).mockRejectedValueOnce('network down')

    await expect(
      service.uploadFile(pendingUpload('up-1').presignedPut, new File([], 'a.txt')),
    ).rejects.toMatchObject({
      name: 'ApiError',
      message: 'Direct upload failed',
    })
  })

  it('forwards AbortSignal to fetch and rejects with AbortError when aborted', async () => {
    // 测试意图：验证 uploadFile 将 AbortSignal 传递给底层 fetch，并支持请求中断
    const service = createStorageService()
    const controller = new AbortController()

    vi.mocked(fetch).mockImplementationOnce((_url, init) => {
      const signal = init?.signal
      return new Promise<Response>((_resolve, reject) => {
        signal?.addEventListener('abort', () => {
          reject(new DOMException('aborted', 'AbortError'))
        })
      })
    })

    const uploadPromise = service.uploadFile(
      pendingUpload('up-1').presignedPut,
      new File(['data'], 'a.txt'),
      controller.signal,
    )
    controller.abort()

    await expect(uploadPromise).rejects.toMatchObject({
      name: 'AbortError',
    })
  })
})
