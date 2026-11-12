import { screen, waitFor } from '@testing-library/react'
import { expect, vi } from 'vitest'
import type { ComposerPart, StorageService } from '@/features/thread/composer'

type StorageUploadDTO = Awaited<ReturnType<StorageService['reserveUpload']>>

export function fileOf(name: string, type: string, size = 128): File {
  return new File([new Uint8Array(size)], name, { type })
}

/** 模拟预留、直传和完成的存储契约，失败次数可供测试控制。 */
export function fakeStorage(overrides: {
  reserve?: (request: Parameters<StorageService['reserveUpload']>[0]) => Promise<StorageUploadDTO> | StorageUploadDTO
  failReserveTimes?: number
  failUploadTimes?: number
  failCompleteTimes?: number
} = {}) {
  let counter = 0
  let reserveFailures = overrides.failReserveTimes ?? 0
  let uploadFailures = overrides.failUploadTimes ?? 0
  let completeFailures = overrides.failCompleteTimes ?? 0
  const reserveUpload = vi.fn(
    async (request: Parameters<StorageService['reserveUpload']>[0]): Promise<StorageUploadDTO> => {
      if (reserveFailures > 0) {
        reserveFailures -= 1
        throw new Error('reserve unavailable')
      }
      counter += 1
      const id = `up-${counter}`
      return overrides.reserve
        ? await overrides.reserve(request)
        : {
            type: 'PENDING' as const,
            id,
            presignedPut: {
              method: 'PUT' as const,
              url: `https://s3.test/${id}`,
              headers: {},
              expiresAt: 0,
              mediaType: null,
              sizeBytes: null,
            },
            expiresAt: 0,
            format: null,
            originalWidth: null,
            originalHeight: null,
            storedWidth: null,
            storedHeight: null,
          }
    },
  )
  const completeUpload = vi.fn(async (uploadId: string): Promise<StorageUploadDTO> => {
    if (completeFailures > 0) {
      completeFailures -= 1
      throw new Error('complete failed')
    }
    return {
      type: 'READY',
      id: uploadId,
      blobId: `blob-${uploadId}`,
      expiresAt: 0,
      format: null,
      originalWidth: null,
      originalHeight: null,
      storedWidth: null,
      storedHeight: null,
    }
  })
  const deleteUpload = vi.fn(async () => undefined)
  const getBlobDownloadUrl = vi.fn(async () => ({ url: 'https://s3.test/orig', mediaType: null, sizeBytes: null }))
  const getBlobPreviewUrl = vi.fn(async () => ({ url: 'https://s3.test/prev', mediaType: null, sizeBytes: null }))
  const uploadFile = vi.fn(async () => {
    if (uploadFailures > 0) {
      uploadFailures -= 1
      throw new Error('upload failed')
    }
    return undefined
  })
  const service = {
    reserveUpload,
    completeUpload,
    deleteUpload,
    getBlobDownloadUrl,
    getBlobPreviewUrl,
    uploadFile,
  } as unknown as StorageService
  return { service, reserveUpload, completeUpload, deleteUpload, uploadFile }
}

export function partsSnapshot(): Array<Record<string, unknown>> {
  const raw = JSON.parse(screen.getByTestId('parts').textContent ?? '[]') as ComposerPart[]
  return raw.map((part) => {
    if (part.type === 'text') {
      return { type: 'text', text: part.text }
    }
    if (part.type === 'attachment') {
      return { type: 'attachment', uploadId: part.uploadId, filename: part.filename }
    }
    return { type: 'resource', blobId: part.blobId, name: part.name }
  })
}

export async function waitForIdleUploads() {
  await waitFor(() => expect(screen.queryByText(/上传中…/)).not.toBeInTheDocument())
}
