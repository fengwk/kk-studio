import { beforeEach, describe, expect, it, vi } from 'vitest'
import { request } from '@/shared/api/client'
import {
  getSessionBlobDownloadUrl,
  getSessionBlobPreviewUrl,
  resolveSessionBlobUrls,
} from '@/shared/api/resource-service'

vi.mock('@/shared/api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/shared/api/client')>()
  return { ...actual, request: vi.fn() }
})

const apiRequest = vi.mocked(request)

describe('resource-service', () => {
  beforeEach(() => {
    apiRequest.mockReset()
  })

  it('signs session-scoped download and preview URLs with AbortSignal forwarding', async () => {
    const presigned = {
      method: 'GET' as const,
      url: 'https://s3.test/orig',
      headers: {},
      expiresAt: 1786492800000,
      mediaType: 'image/png',
      sizeBytes: '64',
    }
    const controller = new AbortController()
    apiRequest.mockResolvedValue(presigned)

    await expect(
      getSessionBlobDownloadUrl('session-1', 'blob-1', { signal: controller.signal }),
    ).resolves.toEqual(presigned)
    expect(apiRequest).toHaveBeenCalledWith(
      'POST /api/harness/sessions/{sessionId}/blobs/{blobId}/download-url',
      { path: { sessionId: 'session-1', blobId: 'blob-1' } },
      { signal: controller.signal },
    )

    await expect(
      getSessionBlobPreviewUrl('session-1', 'blob-1', { signal: controller.signal }),
    ).resolves.toEqual(presigned)
    expect(apiRequest).toHaveBeenCalledWith(
      'POST /api/harness/sessions/{sessionId}/blobs/{blobId}/preview-url',
      { path: { sessionId: 'session-1', blobId: 'blob-1' } },
      { signal: controller.signal },
    )
  })

  it('resolves authoritative original and optional preview URLs for a session blob', async () => {
    apiRequest
      .mockResolvedValueOnce({
        method: 'GET',
        url: 'https://s3.test/orig',
        headers: {},
        expiresAt: 1786492800000,
        mediaType: 'image/png',
        sizeBytes: '64',
      })
      .mockRejectedValueOnce(new Error('no preview'))

    await expect(resolveSessionBlobUrls('session-1', 'blob-1')).resolves.toEqual({
      original: 'https://s3.test/orig',
      preview: null,
      mediaType: 'image/png',
      sizeBytes: '64',
    })

    apiRequest.mockRejectedValueOnce(new Error('not found')).mockResolvedValueOnce({
      method: 'GET',
      url: 'https://s3.test/prev',
      headers: {},
      expiresAt: 1786492800000,
      mediaType: null,
      sizeBytes: null,
    })

    await expect(resolveSessionBlobUrls('session-1', 'blob-1')).resolves.toBeNull()
  })
})
