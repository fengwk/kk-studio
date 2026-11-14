import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import type { StoragePresignedUrlDTO } from '@/shared/api/generated/contracts'
import { storageService } from '@/shared/api/storage-service'
import {
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

/** 预留一个 PENDING 直传并等到 XHR 真正建立，返回上传 promise、该 XHR 与两个存储客户端调用。 */
async function startPendingUpload(
  file: File,
  onProgress: (progress: UploadProgress) => void,
  signal?: AbortSignal,
) {
  const reserve = vi.spyOn(storageService, 'reserveUpload').mockResolvedValue({
    id: 'upload-pending',
    type: 'PENDING',
    presignedPut: presigned,
    expiresAt: 1786320000000,
    format: null,
    originalWidth: null,
    originalHeight: null,
    storedWidth: null,
    storedHeight: null,
  })
  const complete = vi.spyOn(storageService, 'completeUpload').mockResolvedValue({
    id: 'upload-pending',
    type: 'READY',
    blobId: 'blob-pending',
    expiresAt: 1786320000000,
    format: null,
    originalWidth: null,
    originalHeight: null,
    storedWidth: null,
    storedHeight: null,
  })
  const promise = uploadSkillArchive({ file, sha256: 'a'.repeat(64), onProgress, signal })
  await vi.waitFor(() => expect(FakeXhr.instances).toHaveLength(1))
  return { promise, xhr: FakeXhr.instances[0]!, reserve, complete }
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
    const { promise, xhr } = await startPendingUpload(archiveFile(), (value) =>
      progress.push(value))

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

    await expect(promise).resolves.toBe('upload-pending')
    expect(progress).toEqual([
      { loaded: 4, total: 10 },
      { loaded: 10, total: 10 },
    ])
  })

  it('falls back to the file size when the upload event is not length computable', async () => {
    const progress: UploadProgress[] = []
    const { promise, xhr } = await startPendingUpload(archiveFile('skills.tar.gz', 10), (value) =>
      progress.push(value))

    xhr.upload.onprogress?.({
      loaded: 3,
      lengthComputable: false,
      total: 0,
    } as ProgressEvent)
    xhr.emitStatus(204)

    await promise
    expect(progress).toEqual([{ loaded: 3, total: 10 }])
  })

  it('fails with the HTTP status when the direct upload is rejected', async () => {
    const { promise, xhr } = await startPendingUpload(archiveFile(), () => undefined)
    xhr.emitStatus(403)

    await expect(promise).rejects.toBeInstanceOf(ApiError)
    await expect(promise).rejects.toMatchObject({ status: 403 })
  })

  it('aborts the direct upload through the signal', async () => {
    const controller = new AbortController()
    const { promise, xhr } = await startPendingUpload(
      archiveFile(),
      () => undefined,
      controller.signal,
    )

    controller.abort()

    expect(xhr.aborted).toBe(true)
    await expect(promise).rejects.toMatchObject({ name: 'AbortError' })
  })

  it('reserves with the archive media type, uploads when pending and completes the upload', async () => {
    const { promise, xhr, reserve, complete } = await startPendingUpload(
      archiveFile('skills.tar.gz', 10),
      () => undefined,
    )
    xhr.emitStatus(200)

    await expect(promise).resolves.toBe('upload-pending')
    expect(reserve).toHaveBeenCalledWith({
      filename: 'skills.tar.gz',
      mediaType: 'application/gzip',
      sizeBytes: '10',
      sha256: 'a'.repeat(64),
    })
    expect(complete).toHaveBeenCalledWith('upload-pending')
  })

  // zip 扩展名不区分大小写，媒体类型只看扩展名。
  it.each([
    ['skills.zip', 'application/zip'],
    ['SKILLS.ZIP', 'application/zip'],
    ['skills.tar.gz', 'application/gzip'],
  ])('reserves %s as %s', async (filename, mediaType) => {
    const reserve = vi.spyOn(storageService, 'reserveUpload').mockResolvedValue({
      id: 'upload-ready',
      type: 'READY',
      blobId: 'blob-ready',
      expiresAt: 1786320000000,
      format: null,
      originalWidth: null,
      originalHeight: null,
      storedWidth: null,
      storedHeight: null,
    })
    vi.spyOn(storageService, 'completeUpload').mockResolvedValue({
      id: 'upload-ready',
      type: 'READY',
      blobId: 'blob-ready',
      expiresAt: 1786320000000,
      format: null,
      originalWidth: null,
      originalHeight: null,
      storedWidth: null,
      storedHeight: null,
    })

    const promise = uploadSkillArchive({
      file: archiveFile(filename, 10),
      sha256: 'c'.repeat(64),
      onProgress: () => undefined,
    })

    await expect(promise).resolves.toBe('upload-ready')
    expect(FakeXhr.instances).toHaveLength(0)
    expect(reserve).toHaveBeenCalledWith({
      filename,
      mediaType,
      sizeBytes: '10',
      sha256: 'c'.repeat(64),
    })
  })

  it('skips the direct upload when the server already holds identical content', async () => {
    const reserve = vi.spyOn(storageService, 'reserveUpload').mockResolvedValue({
      id: 'upload-2',
      type: 'READY',
      blobId: 'blob-2',
      expiresAt: 1786320000000,
      format: null,
      originalWidth: null,
      originalHeight: null,
      storedWidth: null,
      storedHeight: null,
    })
    const complete = vi.spyOn(storageService, 'completeUpload').mockResolvedValue({
      id: 'upload-2',
      type: 'READY',
      blobId: 'blob-2',
      expiresAt: 1786320000000,
      format: null,
      originalWidth: null,
      originalHeight: null,
      storedWidth: null,
      storedHeight: null,
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
    expect(reserve).toHaveBeenCalledWith({
      filename: 'skills.zip',
      mediaType: 'application/zip',
      sizeBytes: '10',
      sha256: 'b'.repeat(64),
    })
    expect(complete).toHaveBeenCalledWith('upload-2')
  })
})
