import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { HttpClient } from '@/shared/api/client'
import { createStorageService } from '@/shared/api/storage-service'
import type { StorageUploadDTO } from '@/shared/api/contracts/storage'

/**
 * 共享存储客户端契约测试：API 面（HttpClient）为透传封装，直传面（uploadFile）
 * 负责浏览器安全的签名 URL 直传。覆盖 PENDING=需直传 / READY=sha256 命中免直传、
 * 编码路径、header 过滤与错误映射。
 */
function fakeClient() {
  const post = vi.fn()
  const get = vi.fn()
  const deleteFn = vi.fn()
  return {
    client: { get, post, put: vi.fn(), delete: deleteFn } as unknown as HttpClient,
    post,
    get,
    deleteFn,
  }
}

function pendingUpload(id: string, overrides: Partial<StorageUploadDTO> = {}): StorageUploadDTO {
  return {
    id,
    state: 'PENDING',
    blobId: null,
    presignedPut: {
      method: 'PUT',
      url: `https://s3.test/${id}`,
      headers: {
        'x-amz-meta-name': 'a.png',
        'content-type': 'image/png',
        host: 's3.test',
        'content-length': '128',
      },
    },
    expiresAt: '2026-08-12T00:00:00Z',
    ...overrides,
  }
}

function readyUpload(id: string): StorageUploadDTO {
  return {
    id,
    state: 'READY',
    blobId: 'blob-1',
    expiresAt: '2026-08-12T00:00:00Z',
  }
}

describe('storage-service', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('reserves uploads with the full request body and passes the DTO through', async () => {
    const { client, post } = fakeClient()
    const service = createStorageService(client)
    const request = {
      filename: 'photo.png',
      mediaType: 'image/png',
      sizeBytes: 64,
      sha256: 'a'.repeat(64),
    }
    post.mockResolvedValue(pendingUpload('up-1'))

    await expect(service.reserveUpload(request)).resolves.toEqual(pendingUpload('up-1'))
    expect(post).toHaveBeenCalledWith('/storage/uploads', request)
    // wire 请求不含 mediaKind（严格 Jackson 契约只接受四个字段）。
    expect(request).not.toHaveProperty('mediaKind')
  })

  it('routes complete to the encoded upload path and returns the same DTO shape', async () => {
    const { client, post } = fakeClient()
    const service = createStorageService(client)
    post.mockResolvedValue(readyUpload('u/1'))

    await expect(service.completeUpload('u/1')).resolves.toEqual(readyUpload('u/1'))
    expect(post).toHaveBeenCalledWith('/storage/uploads/u%2F1/complete')
  })

  it('deletes by the upload handle, never by blob', async () => {
    const { client, deleteFn } = fakeClient()
    const service = createStorageService(client)
    deleteFn.mockResolvedValue(undefined)

    await service.deleteUpload('up-1')
    expect(deleteFn).toHaveBeenCalledWith('/storage/uploads/up-1')
  })

  it('resolves original and preview blob URLs via GET on the blob path', async () => {
    const { client, get } = fakeClient()
    const service = createStorageService(client)
    get.mockResolvedValue({
      url: 'https://s3.test/orig',
      expiresAt: '2026-08-12T00:00:00Z',
      mediaType: 'image/png',
      sizeBytes: 64,
    })

    await service.getBlobOriginalUrl('blob-1')
    expect(get).toHaveBeenCalledWith('/storage/blobs/blob-1/presigned-original')

    await service.getBlobPreviewUrl('blob-1')
    expect(get).toHaveBeenCalledWith('/storage/blobs/blob-1/presigned-preview')
  })

  it('PUTs object bytes with signed headers minus browser-forbidden ones', async () => {
    const { client } = fakeClient()
    const service = createStorageService(client)
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
    const { client } = fakeClient()
    const service = createStorageService(client)
    vi.mocked(fetch).mockResolvedValue(new Response('nope', { status: 403 }))

    await expect(service.uploadFile(pendingUpload('up-1').presignedPut, new File([], 'a.txt'))).rejects.toMatchObject({
      name: 'ApiError',
      status: 403,
    })
  })

  it('maps network failures to ApiError without status and rethrows aborts unchanged', async () => {
    const { client } = fakeClient()
    const service = createStorageService(client)
    vi.mocked(fetch).mockRejectedValueOnce(new TypeError('Failed to fetch'))

    await expect(service.uploadFile(pendingUpload('up-1').presignedPut, new File([], 'a.txt'))).rejects.toMatchObject({
      name: 'ApiError',
      message: 'Failed to fetch',
    })

    const abort = new DOMException('aborted', 'AbortError')
    vi.mocked(fetch).mockRejectedValueOnce(abort)
    await expect(service.uploadFile(pendingUpload('up-1').presignedPut, new File([], 'a.txt'))).rejects.toBe(abort)
  })

  it('maps non-Error fetch rejections to the generic direct-upload message', async () => {
    const { client } = fakeClient()
    const service = createStorageService(client)
    vi.mocked(fetch).mockRejectedValueOnce('network down')

    await expect(
      service.uploadFile(pendingUpload('up-1').presignedPut, new File([], 'a.txt')),
    ).rejects.toMatchObject({
      name: 'ApiError',
      message: 'Direct upload failed',
    })
  })
})
