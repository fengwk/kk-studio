import { translate } from '@/shared/i18n'
import { formatByteCount } from '@/shared/lib/format-bytes'
import { createUuid } from '@/shared/lib/uuid'

/** 共享存储上传的媒体类别；mediaType 负责精确分类，mediaKind 只是本地路由/限制键。 */
export type StorageMediaKind = 'image' | 'video' | 'audio' | 'file'

export interface UploadLimits {
  image: number
  video: number
  audio: number
  file: number
}

/** 上传大小限制（字节）：图片 30 MiB、视频 100 MiB、音频 15 MiB、文件 30 MiB。 */
export const UPLOAD_LIMITS: UploadLimits = {
  image: 30 * 1024 * 1024,
  video: 100 * 1024 * 1024,
  audio: 15 * 1024 * 1024,
  file: 30 * 1024 * 1024,
}

export function mediaKindOf(mediaType: string): StorageMediaKind {
  if (mediaType.startsWith('image/')) return 'image'
  if (mediaType.startsWith('video/')) return 'video'
  if (mediaType.startsWith('audio/')) return 'audio'
  return 'file'
}

/** 校验文件大小；超限返回可展示的错误信息。 */
export function validateUploadFile(file: File): string | null {
  const limit = UPLOAD_LIMITS[mediaKindOf(file.type)]
  if (file.size > limit) {
    return translate('ai.runtime.composer.fileTooLarge', { name: file.name, limit: formatByteCount(limit) })
  }
  return null
}

export type HashFile = (file: File, signal?: AbortSignal) => Promise<string>

interface PendingHash {
  resolve: (sha256: string) => void
  reject: (error: Error) => void
  onAbort?: () => void
  signal?: AbortSignal
}

/** 专用 Worker + Web Crypto SHA-256，处理 Worker 错误及请求取消。 */
export function createWorkerHasher(): HashFile {
  let worker: Worker | null = null
  const pendingRequests = new Map<string, PendingHash>()

  const cleanupWorker = (error: Error) => {
    if (worker) {
      worker.removeEventListener('message', onMessage)
      worker.removeEventListener('error', onError)
      worker.removeEventListener('messageerror', onMessageError)
      try {
        worker.terminate()
      } catch {
        // terminate 容错
      }
      worker = null
    }
    const current = Array.from(pendingRequests.values())
    pendingRequests.clear()
    for (const pending of current) {
      if (pending.signal && pending.onAbort) pending.signal.removeEventListener('abort', pending.onAbort)
      pending.reject(error)
    }
  }
  const onMessage = (event: MessageEvent<{ requestId: string; sha256?: string; error?: string }>) => {
    const requestId = event.data?.requestId
    if (!requestId) return
    const pending = pendingRequests.get(requestId)
    if (!pending) return
    pendingRequests.delete(requestId)
    if (pending.signal && pending.onAbort) pending.signal.removeEventListener('abort', pending.onAbort)
    if (event.data.sha256) pending.resolve(event.data.sha256)
    else pending.reject(new Error(event.data.error ?? 'SHA-256 failed'))
  }
  const onError = (event: ErrorEvent) => cleanupWorker(new Error(event.message || 'Hash worker runtime error'))
  const onMessageError = () => cleanupWorker(new Error('Hash worker message deserialization error'))
  const ensureWorker = () => {
    if (!worker) {
      worker = new Worker(new URL('./upload-hash.worker.ts', import.meta.url), { type: 'module' })
      worker.addEventListener('message', onMessage)
      worker.addEventListener('error', onError)
      worker.addEventListener('messageerror', onMessageError)
    }
    return worker
  }

  return (file: File, signal?: AbortSignal) =>
    new Promise<string>((resolve, reject) => {
      if (signal?.aborted) {
        reject(new DOMException('The operation was aborted', 'AbortError'))
        return
      }
      const requestId = createUuid()
      let onAbort: (() => void) | undefined
      if (signal) {
        onAbort = () => {
          pendingRequests.delete(requestId)
          signal.removeEventListener('abort', onAbort!)
          reject(new DOMException('The operation was aborted', 'AbortError'))
        }
        signal.addEventListener('abort', onAbort, { once: true })
      }
      pendingRequests.set(requestId, { resolve, reject, onAbort, signal })
      try {
        ensureWorker().postMessage({ requestId, file })
      } catch (error) {
        pendingRequests.delete(requestId)
        if (signal && onAbort) signal.removeEventListener('abort', onAbort)
        reject(error instanceof Error ? error : new Error(String(error)))
      }
    })
}
