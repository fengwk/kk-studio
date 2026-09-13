import { apiClient, type HttpClient } from '@/shared/api/client'
import { storageService, type StorageService } from '@/shared/api/storage-service'
import { decodeCloudFileSnapshot } from './codecs'
import type {
  CloudFileSnapshotDTO,
  CreateDirectoryRequest,
  DeleteNodeParams,
  MountBlobRequest,
  MoveNodeRequest,
  PatchTextRequest,
  SaveTextRequest,
} from './types'

export async function computeFileSha256(file: Blob): Promise<string> {
  const buffer = await file.arrayBuffer()
  const digest = await crypto.subtle.digest('SHA-256', buffer)
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}

export interface CloudFilesApiOptions {
  client?: HttpClient
  storage?: StorageService
}

export function createCloudFilesApi(options: CloudFilesApiOptions = {}) {
  const client = options.client ?? apiClient
  const storage = options.storage ?? storageService

  return {
    getFileSnapshot: async (path: string, offset = 1, limit = 200): Promise<CloudFileSnapshotDTO> => {
      const raw = await client.get<unknown>('/cloud/files', {
        params: { path, offset, limit },
      })
      return decodeCloudFileSnapshot(raw)
    },

    saveText: async (request: SaveTextRequest): Promise<void> => {
      await client.put('/cloud/text', request)
    },

    patchText: async (request: PatchTextRequest): Promise<void> => {
      await client.patch('/cloud/text', request)
    },

    createDirectory: async (request: CreateDirectoryRequest): Promise<void> => {
      await client.post('/cloud/directories', request)
    },

    moveNode: async (request: MoveNodeRequest): Promise<void> => {
      await client.post('/cloud/nodes/move', request)
    },

    deleteNode: async (params: DeleteNodeParams): Promise<void> => {
      await client.delete('/cloud/nodes', {
        params: {
          path: params.path,
          expectedVersion: params.expectedVersion,
        },
      })
    },

    mountBlob: async (request: MountBlobRequest): Promise<void> => {
      await client.post('/cloud/blobs', request)
    },

    getBlobPreviewUrl: async (blobId: string) => {
      return storage.getBlobPreviewUrl(blobId)
    },

    getBlobDownloadUrl: async (blobId: string) => {
      return storage.getBlobDownloadUrl(blobId)
    },

    uploadBlobFile: async (
      file: File,
      destinationPath: string,
    ): Promise<void> => {
      let uploadId: string | null = null
      try {
        const sha256 = await computeFileSha256(file)
        const reserved = await storage.reserveUpload({
          filename: file.name,
          mediaType: file.type || 'application/octet-stream',
          sizeBytes: file.size,
          sha256,
        })
        uploadId = reserved.id

        if (reserved.state === 'PENDING' && reserved.presignedPut) {
          await storage.uploadFile(reserved.presignedPut, file)
        }

        const completed = await storage.completeUpload(reserved.id)
        await client.post('/cloud/blobs', {
          path: destinationPath,
          uploadId: completed.id,
          expectedAbsent: true,
        } satisfies MountBlobRequest)
      } catch (error) {
        if (uploadId) {
          try {
            await storage.deleteUpload(uploadId)
          } catch {
            // Ignore cleanup failure so primary error is not masked
          }
        }
        throw error
      }
    },
  }
}

export type CloudFilesApi = ReturnType<typeof createCloudFilesApi>

export const cloudFilesApi = createCloudFilesApi()
