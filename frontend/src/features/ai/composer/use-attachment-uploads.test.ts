import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/client'
import type { StorageUploadDTO, StorageUploadReserveRequestDTO } from '@/shared/api/contracts/storage'
import type { StorageService } from '@/shared/api/storage-service'
import { createAttachmentPart } from '@/features/ai/composer/composer-parts'
import { loadUnknownUploads } from '@/features/ai/composer/composer-draft'
import {
  createWorkerHasher,
  isAmbiguousUploadOutcome,
  useAttachmentUploads,
  type AttachmentUploadError,
} from './use-attachment-uploads'

type EventCallback = (event: unknown) => void

function createFakeStorageService(overrides?: Partial<StorageService>): StorageService {
  return {
    reserveUpload: vi.fn().mockImplementation(async (req: StorageUploadReserveRequestDTO): Promise<StorageUploadDTO> => ({
      id: 'res-1',
      blobId: 'blob-1',
      mediaKind: 'image',
      state: 'PENDING',
      presignedPut: { url: 'https://upload.example.com/put', method: 'PUT', headers: {} },
      filename: req.filename,
      mediaType: req.mediaType,
      sizeBytes: req.sizeBytes,
      sha256: req.sha256,
      expiresAt: '2026-01-01T00:00:00Z',
      createTime: '2026-01-01T00:00:00Z',
    })),
    completeUpload: vi.fn().mockImplementation(async (id: string): Promise<StorageUploadDTO> => ({
      id,
      blobId: 'blob-1',
      mediaKind: 'image',
      state: 'READY',
      presignedPut: { url: 'https://upload.example.com/put', method: 'PUT', headers: {} },
      filename: 'test.png',
      mediaType: 'image/png',
      sizeBytes: 100,
      sha256: 'hash-1',
      expiresAt: '2026-01-01T00:00:00Z',
      createTime: '2026-01-01T00:00:00Z',
    })),
    deleteUpload: vi.fn().mockResolvedValue(undefined),
    uploadFile: vi.fn().mockResolvedValue(undefined),
    getBlobDownloadUrl: vi.fn(),
    getBlobPreviewUrl: vi.fn(),
    ...overrides,
  }
}

describe('Attachment uploads lifecycle & safety', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  afterEach(() => {
    localStorage.clear()
  })

  describe('H06: Worker hashing error handling & abort', () => {
    it('rejects all pending promises and recreates worker on fatal error event', async () => {
      // 测试意图：验证 Worker 运行时 error 发生时拒绝所有等待中的 hash Promise，并清理 terminate 重新实例化
      let workerInstanceCount = 0

      class MockWorker {
        listeners: Record<string, EventCallback[]> = {}
        lastPostedRequestId: string | null = null
        constructor() {
          workerInstanceCount++
          mockWorkers.push(this)
        }
        addEventListener(event: string, fn: EventCallback) {
          this.listeners[event] = this.listeners[event] || []
          this.listeners[event].push(fn)
        }
        removeEventListener(event: string, fn: EventCallback) {
          if (this.listeners[event]) {
            this.listeners[event] = this.listeners[event].filter((f) => f !== fn)
          }
        }
        postMessage(data: unknown) {
          if (data && typeof data === 'object' && 'requestId' in data) {
            this.lastPostedRequestId = String((data as { requestId: string }).requestId)
          }
        }
        terminate = vi.fn()
      }

      const mockWorkers: MockWorker[] = []
      vi.stubGlobal('Worker', MockWorker)

      const hasher = createWorkerHasher()
      const dummyFile = new File(['abc'], 'test.txt', { type: 'text/plain' })
      const hashPromise = hasher(dummyFile)

      expect(workerInstanceCount).toBe(1)
      const currentWorker = mockWorkers[0]

      // 触发 Worker error 事件
      act(() => {
        const errorEvent = { message: 'Worker crashed' }
        currentWorker.listeners['error']?.forEach((fn) => fn(errorEvent))
      })

      await expect(hashPromise).rejects.toThrow('Worker crashed')
      expect(currentWorker.terminate).toHaveBeenCalledTimes(1)

      // 下一次散列调用应重建全新 Worker 实例，且能成功响应
      const secondPromise = hasher(dummyFile)
      expect(workerInstanceCount).toBe(2)
      const secondWorker = mockWorkers[1]
      act(() => {
        const reqId = secondWorker.lastPostedRequestId ?? 'any'
        secondWorker.listeners['message']?.forEach((fn) =>
          fn({ data: { requestId: reqId, sha256: 'hash-ok' } }),
        )
      })
      await expect(secondPromise).resolves.toBe('hash-ok')
    })

    it('settles immediately when postMessage throws', async () => {
      // 测试意图：验证 postMessage 序列化或环境抛错时，Promise 立即 reject 而不永久挂起
      class MockPostMessageErrorWorker {
        listeners: Record<string, EventCallback[]> = {}
        addEventListener(event: string, fn: EventCallback) {
          this.listeners[event] = this.listeners[event] || []
          this.listeners[event].push(fn)
        }
        removeEventListener() {}
        postMessage() {
          throw new DOMException('DataCloneError')
        }
        terminate = vi.fn()
      }
      vi.stubGlobal('Worker', MockPostMessageErrorWorker)

      const hasher = createWorkerHasher()
      const dummyFile = new File(['abc'], 'test.txt', { type: 'text/plain' })
      await expect(hasher(dummyFile)).rejects.toThrow('DataCloneError')
    })

    it('aborts hashing wait when AbortSignal triggers', async () => {
      // 测试意图：验证通过 AbortSignal 中止 hash 等待时 Promise 立即以 AbortError reject
      class MockHangingWorker {
        addEventListener() {}
        removeEventListener() {}
        postMessage() {}
        terminate = vi.fn()
      }
      vi.stubGlobal('Worker', MockHangingWorker)

      const hasher = createWorkerHasher()
      const controller = new AbortController()
      const dummyFile = new File(['abc'], 'test.txt', { type: 'text/plain' })
      const hashPromise = hasher(dummyFile, controller.signal)

      controller.abort()
      await expect(hashPromise).rejects.toMatchObject({ name: 'AbortError' })
    })
  })

  describe('H07: AbortController per upload and unmount cleanup contracts', () => {
    it('aborts active upload fetch when releaseUpload is called', async () => {
      // 测试意图：验证用户主动移除附件时通过 AbortController 真正中断在途 PUT 请求
      let capturedSignal: AbortSignal | undefined
      const service = createFakeStorageService({
        uploadFile: vi.fn().mockImplementation((_put, _file, signal) => {
          capturedSignal = signal
          return new Promise(() => {}) // 挂起
        }),
      })

      const { result } = renderHook(() =>
        useAttachmentUploads({
          storageService: service,
          hashFile: async () => 'sha-123',
        }),
      )

      act(() => {
        result.current.addFiles([new File(['test'], 'photo.png', { type: 'image/png' })])
      })

      await vi.waitFor(() => expect(capturedSignal).toBeDefined())
      expect(capturedSignal?.aborted).toBe(false)

      const localId = result.current.uploads[0].localId
      act(() => {
        result.current.releaseUpload(localId)
      })

      expect(capturedSignal?.aborted).toBe(true)
    })

    it('does NOT delete upload handle on remove if attachment is already detached (in-flight message)', async () => {
      // 测试意图：验证已发消息使用中（detached=true）的附件即使被移除，也不删除服务端已被消息引用的句柄
      const service = createFakeStorageService()
      const { result } = renderHook(() =>
        useAttachmentUploads({
          storageService: service,
          hashFile: async () => 'sha-123',
        }),
      )

      act(() => {
        result.current.addFiles([new File(['test'], 'photo.png', { type: 'image/png' })])
      })
      await vi.waitFor(() => expect(result.current.uploads[0]?.status).toBe('ready'))
      const localId = result.current.uploads[0].localId

      // 标记为已挂起进入发送管道
      act(() => {
        result.current.markDetached(new Set([localId]), true)
      })

      // 移除时由于 detached=true，绝不调用 deleteUpload
      act(() => {
        result.current.releaseUpload(localId)
      })

      expect(service.deleteUpload).not.toHaveBeenCalled()
    })

    it('preserves READY upload on unmount if draft still references it, and deletes orphaned READY upload', async () => {
      // 测试意图：验证草稿仍持有该 uploadId 时 unmount 保留至 TTL（不破坏草稿恢复契约）；完全未引用的孤立就绪附件主动释放
      const service = createFakeStorageService()
      const referencedPart = createAttachmentPart('loc-ref', 'referenced.png')
      referencedPart.uploadId = 'res-ref'

      // Case 1: 草稿持有 uploadId，unmount 不调用 deleteUpload
      const { unmount: unmountReferenced } = renderHook(() =>
        useAttachmentUploads({
          storageService: service,
          hashFile: async () => 'sha-1',
          parts: [referencedPart],
        }),
      )

      // Case 2: 孤立未引用的 READY 上传，unmount 主动释放
      const { result: hookOrphan, unmount: unmountOrphan } = renderHook(() =>
        useAttachmentUploads({
          storageService: service,
          hashFile: async () => 'sha-2',
          parts: [], // 草稿无引用
        }),
      )

      act(() => {
        hookOrphan.current.addFiles([new File(['data'], 'orphan.png', { type: 'image/png' })])
      })
      await vi.waitFor(() => expect(hookOrphan.current.uploads[0]?.status).toBe('ready'))
      const orphanUploadId = hookOrphan.current.uploads[0].uploadId!

      unmountOrphan()
      expect(service.deleteUpload).toHaveBeenCalledWith(orphanUploadId)

      vi.mocked(service.deleteUpload).mockClear()
      unmountReferenced()
      // 被引用的草稿句柄不释放
      expect(service.deleteUpload).not.toHaveBeenCalled()
    })
  })

  describe('H03: Ambiguous complete retention, reload restoration & retry', () => {
    it('classifies 5xx/408/429 and network errors as ambiguous outcome', () => {
      // 测试意图：验证 isAmbiguousUploadOutcome 对网络异常、5xx、408、429 正确定位为结果未知
      expect(isAmbiguousUploadOutcome(new ApiError('network down'))).toBe(true)
      expect(isAmbiguousUploadOutcome(new ApiError('timeout', 408))).toBe(true)
      expect(isAmbiguousUploadOutcome(new ApiError('rate limit', 429))).toBe(true)
      expect(isAmbiguousUploadOutcome(new ApiError('internal server error', 500))).toBe(true)
      expect(isAmbiguousUploadOutcome(new ApiError('bad gateway', 502))).toBe(true)
      expect(isAmbiguousUploadOutcome(new TypeError('Failed to fetch'))).toBe(true)

      // 确定未接受错误
      expect(isAmbiguousUploadOutcome(new ApiError('bad request', 400))).toBe(false)
      expect(isAmbiguousUploadOutcome(new ApiError('not found', 404))).toBe(false)
      expect(isAmbiguousUploadOutcome(new ApiError('forbidden', 403))).toBe(false)
    })

    it('retains uploadId without DELETE on ambiguous complete failure, persists to scope, and allows retryComplete', async () => {
      // 测试意图：验证 complete 遇 503 时不调 deleteUpload、置为 complete_unknown、写入 scope，并支持 retryComplete 成功
      const scope = 'agent-pane:CHAT:c1:pane-1'
      let completeFail = true

      const service = createFakeStorageService({
        completeUpload: vi.fn().mockImplementation(async (id: string) => {
          if (completeFail) {
            throw new ApiError('Service Unavailable', 503)
          }
          return {
            id,
            blobId: 'b-1',
            mediaKind: 'image',
            state: 'READY',
            presignedPut: { url: 'put', method: 'PUT', headers: {} },
            filename: 'img.png',
            mediaType: 'image/png',
            sizeBytes: 100,
            sha256: 'h1',
            expiresAt: '2026',
            createTime: '2026',
          }
        }),
      })

      const errors: AttachmentUploadError[] = []
      const { result } = renderHook(() =>
        useAttachmentUploads({
          storageService: service,
          hashFile: async () => 'sha-abc',
          scope,
          onError: (e) => errors.push(e),
        }),
      )

      act(() => {
        result.current.addFiles([new File(['bytes'], 'test.png', { type: 'image/png' })])
      })

      await vi.waitFor(() => {
        expect(result.current.uploads[0]?.status).toBe('complete_unknown')
      })

      const uploadId = result.current.uploads[0].uploadId
      expect(uploadId).toBe('res-1')
      // 绝不调用 deleteUpload
      expect(service.deleteUpload).not.toHaveBeenCalled()
      expect(errors).toHaveLength(1)

      // 检查 scope 存储中是否已经持久化该未知上传元数据
      const stored = loadUnknownUploads(scope)
      expect(stored).toHaveLength(1)
      expect(stored[0].uploadId).toBe('res-1')

      // unmount 绝不删除 complete_unknown 句柄
      // 此时模拟 reload：同 scope 重新挂载
      const { result: reloadedHook } = renderHook(() =>
        useAttachmentUploads({
          storageService: service,
          hashFile: async () => 'sha-abc',
          scope,
        }),
      )

      expect(reloadedHook.current.uploads).toHaveLength(1)
      expect(reloadedHook.current.uploads[0].status).toBe('complete_unknown')
      expect(reloadedHook.current.uploads[0].uploadId).toBe('res-1')

      // 允许点击重试：retryComplete
      completeFail = false
      await act(async () => {
        await reloadedHook.current.retryComplete(reloadedHook.current.uploads[0].localId)
      })

      expect(reloadedHook.current.uploads[0].status).toBe('ready')
      expect(reloadedHook.current.uploads[0].uploadId).toBe('res-1')
      // 成功后持久化清理
      expect(loadUnknownUploads(scope)).toHaveLength(0)
    })

    it('cleans up and deletes reserved handle when completeUpload rejects with definitive 400 error', async () => {
      // 测试意图：验证 complete 明确收到 400 失败时安全 cleanup 并释放 reserved handle
      const scope = 'agent-pane:test'
      const service = createFakeStorageService({
        completeUpload: vi.fn().mockRejectedValue(new ApiError('Bad Request', 400)),
      })

      const { result } = renderHook(() =>
        useAttachmentUploads({
          storageService: service,
          hashFile: async () => 'sha-abc',
          scope,
        }),
      )

      act(() => {
        result.current.addFiles([new File(['bytes'], 'bad.png', { type: 'image/png' })])
      })

      await vi.waitFor(() => {
        expect(service.deleteUpload).toHaveBeenCalledWith('res-1')
      })
      expect(result.current.uploads).toHaveLength(0)
      expect(loadUnknownUploads(scope)).toHaveLength(0)
    })

    it('fails closed when Storage quota is exceeded before completeUpload: aborts and calls completeUpload 0 times', async () => {
      // 测试意图：验证 Storage 存储满额时（QuotaExceededError），严格阻止发送 completeUpload（0 次），清理 handle 并报错
      const scope = 'agent-pane:quota-test'
      const setItemSpy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new DOMException('QuotaExceededError', 'QuotaExceededError')
      })

      const service = createFakeStorageService()
      const errors: AttachmentUploadError[] = []
      const { result } = renderHook(() =>
        useAttachmentUploads({
          storageService: service,
          hashFile: async () => 'sha-123',
          scope,
          onError: (e) => errors.push(e),
        }),
      )

      act(() => {
        result.current.addFiles([new File(['bytes'], 'photo.png', { type: 'image/png' })])
      })

      await vi.waitFor(() => {
        expect(errors).toHaveLength(1)
      })

      // 核心断言：completeUpload 调用 0 次（Fail Closed）！
      expect(service.completeUpload).toHaveBeenCalledTimes(0)
      // 预留的 handle 已被安全 deleteUpload 清理
      expect(service.deleteUpload).toHaveBeenCalledWith('res-1')
      expect(result.current.uploads).toHaveLength(0)

      setItemSpy.mockRestore()
    })

    it('interleaved two attachments: one success does not drop or overwrite the other unknown upload', async () => {
      // 测试意图：两附件并发，A 成功返回 200，B 遇到 503 unknown；A 成功 resolve 绝不误删 B 的持久化 unknown 记录
      const scope = 'agent-pane:interleaved'
      const service = createFakeStorageService({
        reserveUpload: vi.fn().mockImplementation(async (req) => ({
          id: `res-${req.filename}`,
          blobId: 'b',
          mediaKind: 'image',
          state: 'PENDING',
          presignedPut: { url: 'put', method: 'PUT', headers: {} },
          filename: req.filename,
          mediaType: req.mediaType,
          sizeBytes: req.sizeBytes,
          sha256: req.sha256,
          expiresAt: '2026',
          createTime: '2026',
        })),
        completeUpload: vi.fn().mockImplementation(async (id: string) => {
          if (id === 'res-fileB.png') {
            throw new ApiError('Service Unavailable', 503)
          }
          return {
            id,
            blobId: 'b',
            mediaKind: 'image',
            state: 'READY',
            presignedPut: { url: 'put', method: 'PUT', headers: {} },
            filename: 'fileA.png',
            mediaType: 'image/png',
            sizeBytes: 100,
            sha256: 'hA',
            expiresAt: '2026',
            createTime: '2026',
          }
        }),
      })

      const { result } = renderHook(() =>
        useAttachmentUploads({
          storageService: service,
          hashFile: async (file) => `sha-${file.name}`,
          scope,
        }),
      )

      act(() => {
        result.current.addFiles([
          new File(['aaa'], 'fileA.png', { type: 'image/png' }),
          new File(['bbb'], 'fileB.png', { type: 'image/png' }),
        ])
      })

      await vi.waitFor(() => {
        expect(service.completeUpload).toHaveBeenCalledTimes(2)
      })

      await vi.waitFor(() => {
        const statuses = result.current.uploads.map((u) => u.status)
        expect(statuses).toContain('ready')
        expect(statuses).toContain('complete_unknown')
      })

      // 验证 Storage 中依然保留了 fileB.png 的未知元数据，fileA 的成功并未将其冲掉
      const stored = loadUnknownUploads(scope)
      expect(stored).toHaveLength(1)
      expect(stored[0].uploadId).toBe('res-fileB.png')
    })

    it('old scope completion does not leak or contaminate new scope', async () => {
      // 测试意图：旧 scope 发生上传中途组件切换至新 scope，旧 scope 的 resolve 绝不写入新 scope
      let resolveOldComplete!: (v: StorageUploadDTO) => void
      const service = createFakeStorageService({
        completeUpload: vi.fn().mockImplementation(() => {
          return new Promise((resolve) => {
            resolveOldComplete = resolve
          })
        }),
      })

      let currentScope = 'scope-alpha'
      const { result, rerender } = renderHook(() =>
        useAttachmentUploads({
          storageService: service,
          hashFile: async () => 'sha-1',
          scope: currentScope,
        }),
      )

      act(() => {
        result.current.addFiles([new File(['test'], 'alpha.png', { type: 'image/png' })])
      })

      await vi.waitFor(() => expect(service.completeUpload).toHaveBeenCalledTimes(1))

      // 此时切换至新 scope
      currentScope = 'scope-beta'
      rerender()

      // 新 scope 初始应为空
      expect(result.current.uploads).toHaveLength(0)

      // 旧 scope complete 完成
      await act(async () => {
        resolveOldComplete({
          id: 'res-alpha',
          blobId: 'b',
          mediaKind: 'image',
          state: 'READY',
          presignedPut: { url: 'put', method: 'PUT', headers: {} },
          filename: 'alpha.png',
          mediaType: 'image/png',
          sizeBytes: 100,
          sha256: 'sha-1',
          expiresAt: '2026',
          createTime: '2026',
        })
      })

      // 新 scope 依然保持纯净，无任何 alpha 的数据泄露
      expect(result.current.uploads).toHaveLength(0)
      expect(loadUnknownUploads('scope-beta')).toHaveLength(0)
    })

    it('preserves identity in storage during in-flight complete and restores on reload', async () => {
      // 测试意图：验证在 complete 发起中（网络在途、尚未 resolve 时），存储中已经存在该 uploadId；模拟窗口刷新重新挂载可恢复
      const scope = 'scope-inflight'
      let inFlightStorageSnapshot: ReturnType<typeof loadUnknownUploads> = []
      const service = createFakeStorageService({
        completeUpload: vi.fn().mockImplementation(async () => {
          // 在 complete 请求飞行途中检查 Storage
          inFlightStorageSnapshot = loadUnknownUploads(scope)
          // 模拟在途时发生崩溃/刷新，挂起不返回
          return new Promise(() => {})
        }),
      })

      renderHook(() =>
        useAttachmentUploads({
          storageService: service,
          hashFile: async () => 'sha-inflight',
          scope,
        }),
      ).result.current.addFiles([new File(['bytes'], 'inflight.png', { type: 'image/png' })])

      await vi.waitFor(() => expect(inFlightStorageSnapshot.length).toBe(1))
      expect(inFlightStorageSnapshot[0].uploadId).toBe('res-1')

      // 模拟页面 reload 挂载：同 scope 重新挂载
      const { result: reloaded } = renderHook(() =>
        useAttachmentUploads({
          storageService: service,
          hashFile: async () => 'sha-inflight',
          scope,
        }),
      )

      expect(reloaded.current.uploads).toHaveLength(1)
      expect(reloaded.current.uploads[0].uploadId).toBe('res-1')
      expect(reloaded.current.uploads[0].status).toBe('complete_unknown')
    })
  })
})
