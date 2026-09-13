import { describe, expect, it, vi } from 'vitest'
import type { HttpClient } from '@/shared/api/client'
import type { StorageService } from '@/shared/api/storage-service'
import { computeFileSha256, createCloudFilesApi } from './cloud-files-api'

const VALID_UUID = '12345678-1234-1234-1234-123456789abc'

describe('cloud-files-api', () => {
  it('computes sha256 for a blob', async () => {
    const blob = new Blob(['hello world'], { type: 'text/plain' })
    const hash = await computeFileSha256(blob)
    // sha256 of "hello world" is b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9
    expect(hash).toBe('b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9')
  })

  it('fetches snapshot and decodes properly', async () => {
    const fakeClient: HttpClient = {
      get: vi.fn().mockResolvedValue({
        node: {
          id: VALID_UUID,
          path: '/',
          name: '',
          kind: 'DIRECTORY',
          version: '0',
          blobId: null,
          mediaType: null,
          sizeBytes: null,
          sha256: null,
          createdAt: null,
          updatedAt: null,
        },
        children: [],
      }),
      post: vi.fn(),
      put: vi.fn(),
      delete: vi.fn(),
    }

    const api = createCloudFilesApi({ client: fakeClient })
    const snapshot = await api.getFileSnapshot('/', 1, 100)

    expect(fakeClient.get).toHaveBeenCalledWith('/cloud/files', {
      params: { path: '/', offset: 1, limit: 100 },
    })
    expect(snapshot.node.path).toBe('/')
    expect(snapshot.children).toEqual([])
  })

  it('runs the full upload flow: reserve -> direct PUT -> complete -> mount blob', async () => {
    const fakeClient: HttpClient = {
      get: vi.fn(),
      post: vi.fn().mockResolvedValue({}),
      put: vi.fn(),
      delete: vi.fn(),
    }

    const fakeStorage: StorageService = {
      reserveUpload: vi.fn().mockResolvedValue({
        id: 'upload-123',
        state: 'PENDING',
        blobId: null,
        presignedPut: {
          method: 'PUT',
          url: 'https://storage.example.com/put',
          headers: { 'content-type': 'image/png' },
        },
        expiresAt: null,
      }),
      completeUpload: vi.fn().mockResolvedValue({
        id: 'upload-123',
        state: 'READY',
        blobId: 'blob-abc',
        presignedPut: null,
        expiresAt: null,
      }),
      deleteUpload: vi.fn(),
      uploadFile: vi.fn().mockResolvedValue(undefined),
      getBlobDownloadUrl: vi.fn(),
      getBlobPreviewUrl: vi.fn(),
    }

    const api = createCloudFilesApi({ client: fakeClient, storage: fakeStorage })
    const file = new File(['fake-image-bytes'], 'photo.png', { type: 'image/png' })

    await api.uploadBlobFile(file, '/uploads/photo.png')

    // 1. Reserve called
    expect(fakeStorage.reserveUpload).toHaveBeenCalledWith(
      expect.objectContaining({
        filename: 'photo.png',
        mediaType: 'image/png',
      }),
    )

    // 2. Direct PUT called
    expect(fakeStorage.uploadFile).toHaveBeenCalledWith(
      expect.objectContaining({ url: 'https://storage.example.com/put' }),
      file,
    )

    // 3. Complete upload called
    expect(fakeStorage.completeUpload).toHaveBeenCalledWith('upload-123')

    // 4. Mount blob called with uploadId and expectedAbsent: true
    expect(fakeClient.post).toHaveBeenCalledWith('/cloud/blobs', {
      path: '/uploads/photo.png',
      uploadId: 'upload-123',
      expectedAbsent: true,
    })
  })

  it('skips direct PUT if upload reservation is already READY (dedup)', async () => {
    const fakeClient: HttpClient = {
      get: vi.fn(),
      post: vi.fn().mockResolvedValue({}),
      put: vi.fn(),
      delete: vi.fn(),
    }

    const fakeStorage: StorageService = {
      reserveUpload: vi.fn().mockResolvedValue({
        id: 'upload-456',
        state: 'READY',
        blobId: 'blob-already-exists',
        presignedPut: null,
        expiresAt: null,
      }),
      completeUpload: vi.fn().mockResolvedValue({
        id: 'upload-456',
        state: 'READY',
        blobId: 'blob-already-exists',
        presignedPut: null,
        expiresAt: null,
      }),
      deleteUpload: vi.fn(),
      uploadFile: vi.fn(),
      getBlobDownloadUrl: vi.fn(),
      getBlobPreviewUrl: vi.fn(),
    }

    const api = createCloudFilesApi({ client: fakeClient, storage: fakeStorage })
    const file = new File(['content'], 'file.txt', { type: 'text/plain' })

    await api.uploadBlobFile(file, '/file.txt')

    expect(fakeStorage.uploadFile).not.toHaveBeenCalled()
    expect(fakeStorage.completeUpload).toHaveBeenCalledWith('upload-456')
    expect(fakeClient.post).toHaveBeenCalledWith('/cloud/blobs', {
      path: '/file.txt',
      uploadId: 'upload-456',
      expectedAbsent: true,
    })
  })

  it('delegates patchText directly to client.patch without falling back to PUT', async () => {
    const fakeClient: HttpClient = {
      get: vi.fn(),
      post: vi.fn(),
      put: vi.fn(),
      patch: vi.fn().mockResolvedValue(undefined),
      delete: vi.fn(),
    }

    const api = createCloudFilesApi({ client: fakeClient })
    const patchPayload = {
      path: '/notes.md',
      oldString: 'Hello',
      newString: 'World',
      replaceAll: false,
      expectedRevision: '1',
    }

    await api.patchText(patchPayload)

    expect(fakeClient.patch).toHaveBeenCalledWith('/cloud/text', patchPayload)
    expect(fakeClient.put).not.toHaveBeenCalled()
  })

  it('cleans up upload handle via deleteUpload when mountBlob fails without masking error', async () => {
    const mountError = new Error('Mount blob database failure')
    const fakeClient: HttpClient = {
      get: vi.fn(),
      post: vi.fn().mockRejectedValue(mountError),
      put: vi.fn(),
      delete: vi.fn(),
    }

    const fakeStorage: StorageService = {
      reserveUpload: vi.fn().mockResolvedValue({
        id: 'upload-cleanup-id',
        state: 'READY',
        blobId: 'blob-1',
        presignedPut: null,
        expiresAt: null,
      }),
      completeUpload: vi.fn().mockResolvedValue({
        id: 'upload-cleanup-id',
        state: 'READY',
        blobId: 'blob-1',
        presignedPut: null,
        expiresAt: null,
      }),
      deleteUpload: vi.fn().mockResolvedValue(undefined),
      uploadFile: vi.fn(),
      getBlobDownloadUrl: vi.fn(),
      getBlobPreviewUrl: vi.fn(),
    }

    const api = createCloudFilesApi({ client: fakeClient, storage: fakeStorage })
    const file = new File(['content'], 'file.txt', { type: 'text/plain' })

    await expect(api.uploadBlobFile(file, '/file.txt')).rejects.toThrow('Mount blob database failure')

    expect(fakeStorage.deleteUpload).toHaveBeenCalledWith('upload-cleanup-id')
  })
})
