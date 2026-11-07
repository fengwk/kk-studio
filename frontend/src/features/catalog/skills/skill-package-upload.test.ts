import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import type { StoragePresignedUrlDTO } from '@/shared/api/generated/contracts'
import { storageService } from '@/shared/api/storage-service'
import {
  putFileWithProgress,
  skillArchiveMediaType,
  uploadSkillArchive,
  type UploadProgress,
} from '@/features/catalog/skills/skill-package-upload'

const presigned: StoragePresignedUrlDTO = {
  method: 'PUT',
  url: 'http://s3.example/presigned',
  expiresAt: 0,
  mediaType: 'application/zip',
  sizeBytes: null,
  headers: {
    'content-type': 'application/zip',
    'x-amz-date': '20260101T000000Z',
    'x-amz-signature': 'signature',
    'user-agent': 'forbidden-agent',
    'content-length': '10',
  },
}

/** 只在测试里驱动回调的 XHR 替身：真实浏览器实现由 Playwright 覆盖。 */
class FakeXhr {
  static instances: FakeXhr[] = []

  method = ''
  url = ''
  headers: Record<string, string> = {}
  sentBody: unknown = null
  status = 0
  aborted = false

  upload: { onprogress: ((event: ProgressEvent) => void) | null } = { onprogress: null }
  onload: (() => void) | null = null
  onerror: (() => void) | null = null
  onabort: (() => void) | null = null

  constructor() {
    FakeXhr.instances.push(this)
  }

  open(method: string, url: string) {
    this.method = method
    this.url = url
  }

  setRequestHeader(name: string, value: string) {
    this.headers[name] = value
  }

  send(body: unknown) {
    this.sentBody = body
  }

  abort() {
    this.aborted = true
    this.onabort?.()
  }

  emitProgress(loaded: number, total: number) {
    this.upload.onprogress?.({ loaded, total, lengthComputable: true } as ProgressEvent)
  }

  emitStatus(status: number) {
    this.status = status
    this.onload?.()
  }
}

function archiveFile(name = 'skills.zip', size = 10): File {
  return new File([new Uint8Array(size)], name, { type: '' })
}

describe('skill package upload', () => {
  beforeEach(() => {
    FakeXhr.instances = []
    vi.stubGlobal('XMLHttpRequest', FakeXhr)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('streams the archive with only browser-safe presigned headers and reports real progress', async () => {
    const progress: UploadProgress[] = []
    const promise = putFileWithProgress(presigned, archiveFile(), (value) => progress.push(value))

    const xhr = FakeXhr.instances[0]
    expect(xhr.method).toBe('PUT')
    expect(xhr.url).toBe('http://s3.example/presigned')
    // 浏览器会拒绝或改写 content-length 与 user-agent，直传必须跳过它们。
    expect(xhr.headers).toEqual({
      'content-type': 'application/zip',
      'x-amz-date': '20260101T000000Z',
      'x-amz-signature': 'signature',
    })

    xhr.emitProgress(4, 10)
    xhr.emitProgress(10, 10)
    xhr.emitStatus(200)

    await expect(promise).resolves.toBeUndefined()
    expect(progress).toEqual([
      { loaded: 4, total: 10 },
      { loaded: 10, total: 10 },
    ])
  })

  it('falls back to the file size when the upload event is not length computable', async () => {
    const progress: UploadProgress[] = []
    const promise = putFileWithProgress(presigned, archiveFile('skills.tar.gz', 10), (value) =>
      progress.push(value))

    FakeXhr.instances[0].upload.onprogress?.({
      loaded: 3,
      lengthComputable: false,
      total: 0,
    } as ProgressEvent)
    FakeXhr.instances[0].emitStatus(204)

    await promise
    expect(progress).toEqual([{ loaded: 3, total: 10 }])
  })

  it('fails with the HTTP status when the direct upload is rejected', async () => {
    const promise = putFileWithProgress(presigned, archiveFile(), () => undefined)
    FakeXhr.instances[0].emitStatus(403)

    await expect(promise).rejects.toBeInstanceOf(ApiError)
    await expect(promise).rejects.toMatchObject({ status: 403 })
  })

  it('aborts the direct upload through the signal', async () => {
    const controller = new AbortController()
    const promise = putFileWithProgress(presigned, archiveFile(), () => undefined, controller.signal)

    controller.abort()

    expect(FakeXhr.instances[0].aborted).toBe(true)
    await expect(promise).rejects.toMatchObject({ name: 'AbortError' })
  })

  it('reserves with the archive media type, uploads when pending and completes the upload', async () => {
    const reserve = vi.spyOn(storageService, 'reserveUpload').mockResolvedValue({
      id: 'upload-1',
      type: 'PENDING',
      presignedPut: presigned,
      expiresAt: 1786320000000,
    })
    const complete = vi.spyOn(storageService, 'completeUpload').mockResolvedValue({
      id: 'upload-1',
      type: 'READY',
      blobId: 'blob-1',
      expiresAt: 1786320000000,
    })

    const promise = uploadSkillArchive({
      file: archiveFile('skills.tar.gz', 10),
      sha256: 'a'.repeat(64),
      onProgress: () => undefined,
    })
    await vi.waitFor(() => expect(FakeXhr.instances).toHaveLength(1))
    FakeXhr.instances[0].emitStatus(200)

    await expect(promise).resolves.toBe('upload-1')
    expect(reserve).toHaveBeenCalledWith({
      filename: 'skills.tar.gz',
      mediaType: 'application/gzip',
      sizeBytes: 10,
      sha256: 'a'.repeat(64),
    })
    expect(complete).toHaveBeenCalledWith('upload-1')
  })

  it('skips the direct upload when the server already holds identical content', async () => {
    vi.spyOn(storageService, 'reserveUpload').mockResolvedValue({
      id: 'upload-2',
      type: 'READY',
      blobId: 'blob-2',
      expiresAt: 1786320000000,
    })
    const complete = vi.spyOn(storageService, 'completeUpload').mockResolvedValue({
      id: 'upload-2',
      type: 'READY',
      blobId: 'blob-2',
      expiresAt: 1786320000000,
    })
    const progress: UploadProgress[] = []

    await expect(
      uploadSkillArchive({
        file: archiveFile('skills.zip', 10),
        sha256: 'b'.repeat(64),
        onProgress: (value) => progress.push(value),
      }),
    ).resolves.toBe('upload-2')

    expect(FakeXhr.instances).toHaveLength(0)
    expect(progress).toEqual([{ loaded: 10, total: 10 }])
    expect(complete).toHaveBeenCalledWith('upload-2')
  })

  it('maps archive extensions to media types', () => {
    expect(skillArchiveMediaType('skills.zip')).toBe('application/zip')
    expect(skillArchiveMediaType('SKILLS.ZIP')).toBe('application/zip')
    expect(skillArchiveMediaType('skills.tar.gz')).toBe('application/gzip')
  })
})
