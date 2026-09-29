import { useCallback, useEffect, useRef, useState } from 'react'
import {
  createAttachmentPart,
  createPartId,
  removePartsByUpload,
  type ComposerPart,
  type ImageInputTier,
} from '@/features/ai/composer/composer-parts'
import { storageService, type StorageService } from '@/shared/api/storage-service'
import type { StorageMediaKind, StorageUploadDTO } from '@/shared/api/contracts/storage'
import { ApiError } from '@/shared/api/client'
import {
  loadUnknownUploads,
  persistPendingUnknownUpload,
  removeStoredUnknownUpload,
} from '@/features/ai/composer/composer-draft'
import { translate } from '@/shared/i18n'

export type AttachmentUploadStatus = 'uploading' | 'ready' | 'complete_unknown'

export function isAmbiguousUploadOutcome(error: unknown): boolean {
  if (error instanceof ApiError) {
    if (error.status === undefined || error.status === 0) {
      return true
    }
    if (error.status === 408 || error.status === 429) {
      return true
    }
    if (error.status >= 500 && error.status <= 599) {
      return true
    }
    return false
  }
  if (error instanceof TypeError) {
    return true
  }
  return false
}

export interface AttachmentUploadError {
  filename: string
  reason: string
  localId: string
}

/** 单个上传注册表条目；localId 是客户端稳定 id，uploadId 在 reserve 后填充且 complete 后不变。 */
export interface AttachmentUpload {
  localId: string
  /** 服务端 upload 句柄：USER_MESSAGE ATTACHMENT 引用它，释放也删除它。 */
  uploadId: string | null
  filename: string
  mediaType: string
  sizeBytes: number
  sha256: string | null
  status: AttachmentUploadStatus
  progress: number
  /** 本地图片/视频预览 URL；只在 Composer 生命周期内存在。 */
  previewUrl: string | null
  /** 提交后等待发送结果期间隐藏（发送失败恢复时重新挂载）。 */
  detached: boolean
  /** 图片输入档位（仅图片有效；默认 720P，可选 1080P/ORIGINAL；其它媒体为 undefined）。 */
  imageTier?: ImageInputTier
}

export interface UploadLimits {
  image: number
  video: number
  audio: number
  file: number
}

/**
 * 上传大小限制（字节）。
 * - 图片 30 MiB / 视频 100 MiB / 音频 15 MiB / 通用文件 30 MiB。
 */
export const UPLOAD_LIMITS: UploadLimits = {
  image: 30 * 1024 * 1024,
  video: 100 * 1024 * 1024,
  audio: 15 * 1024 * 1024,
  file: 30 * 1024 * 1024,
}

export function mediaKindOf(mediaType: string): StorageMediaKind {
  if (mediaType.startsWith('image/')) {
    return 'image'
  }
  if (mediaType.startsWith('video/')) {
    return 'video'
  }
  if (mediaType.startsWith('audio/')) {
    return 'audio'
  }
  return 'file'
}

export function formatFileSize(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/** 校验文件大小；超限返回可展示的错误信息。 */
export function validateUploadFile(file: File): string | null {
  const limit = UPLOAD_LIMITS[mediaKindOf(file.type)]
  if (file.size > limit) {
    return translate('ai.runtime.composer.fileTooLarge', {
      name: file.name,
      limit: formatFileSize(limit),
    })
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

/** 默认哈希实现：专用 Web Worker + Web Crypto SHA-256，支持异常 settle 与 AbortSignal。 */
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
      if (pending.signal && pending.onAbort) {
        pending.signal.removeEventListener('abort', pending.onAbort)
      }
      pending.reject(error)
    }
  }

  const onMessage = (
    event: MessageEvent<{ requestId: string; sha256?: string; error?: string }>,
  ) => {
    const requestId = event.data?.requestId
    if (!requestId) {
      return
    }
    const pending = pendingRequests.get(requestId)
    if (!pending) {
      return
    }
    pendingRequests.delete(requestId)
    if (pending.signal && pending.onAbort) {
      pending.signal.removeEventListener('abort', pending.onAbort)
    }
    if (event.data.sha256) {
      pending.resolve(event.data.sha256)
    } else {
      pending.reject(new Error(event.data.error ?? 'SHA-256 failed'))
    }
  }

  const onError = (event: ErrorEvent) => {
    cleanupWorker(new Error(event.message || 'Hash worker runtime error'))
  }

  const onMessageError = () => {
    cleanupWorker(new Error('Hash worker message deserialization error'))
  }

  const ensureWorker = () => {
    if (!worker) {
      worker = new Worker(new URL('./upload-hash.worker.ts', import.meta.url), {
        type: 'module',
      })
      worker.addEventListener('message', onMessage)
      worker.addEventListener('error', onError)
      worker.addEventListener('messageerror', onMessageError)
    }
    return worker
  }

  return (file: File, signal?: AbortSignal) =>
    new Promise<string>((resolve, reject) => {
      if (signal?.aborted) {
        const error = new DOMException('The operation was aborted', 'AbortError')
        reject(error)
        return
      }

      const requestId = createPartId()
      let onAbort: (() => void) | undefined

      if (signal) {
        onAbort = () => {
          pendingRequests.delete(requestId)
          signal.removeEventListener('abort', onAbort!)
          const error = new DOMException('The operation was aborted', 'AbortError')
          reject(error)
        }
        signal.addEventListener('abort', onAbort, { once: true })
      }

      pendingRequests.set(requestId, {
        resolve,
        reject,
        onAbort,
        signal,
      })

      try {
        const w = ensureWorker()
        w.postMessage({ requestId, file })
      } catch (error) {
        pendingRequests.delete(requestId)
        if (signal && onAbort) {
          signal.removeEventListener('abort', onAbort)
        }
        reject(error instanceof Error ? error : new Error(String(error)))
      }
    })
}

const defaultHashFile: HashFile = createWorkerHasher()

export function useAttachmentUploads(options?: {
  storageService?: StorageService
  hashFile?: HashFile
  onError?: (error: AttachmentUploadError) => void
  scope?: string
  parts?: ComposerPart[]
}) {
  const service = options?.storageService ?? storageService
  const hashFile = options?.hashFile ?? defaultHashFile
  const scope = options?.scope
  const scopeRef = useRef(scope)
  useEffect(() => {
    scopeRef.current = scope
  }, [scope])
  const partsRef = useRef(options?.parts)
  useEffect(() => {
    partsRef.current = options?.parts
  }, [options?.parts])
  const onErrorRef = useRef(options?.onError)
  useEffect(() => {
    onErrorRef.current = options?.onError
  }, [options?.onError])

  const [uploads, setUploads] = useState<AttachmentUpload[]>(() => {
    if (!scope) {
      return []
    }
    return loadUnknownUploads(scope).map((item) => ({
      localId: item.localId,
      uploadId: item.uploadId,
      filename: item.filename,
      mediaType: item.mediaType,
      sizeBytes: item.sizeBytes,
      sha256: item.sha256,
      status: 'complete_unknown' as const,
      progress: 0.8,
      previewUrl: null,
      detached: false,
      ...(item.imageTier ? { imageTier: item.imageTier } : {}),
    }))
  })
  const uploadsRef = useRef<AttachmentUpload[]>(uploads)
  const filesRef = useRef(new Map<string, File>())
  const previewUrlsRef = useRef(new Map<string, string>())
  const activeRef = useRef(new Set<string>())
  const abortControllersRef = useRef(new Map<string, AbortController>())
  const isMountedRef = useRef(true)
  const generationRef = useRef(0)

  useEffect(() => {
    isMountedRef.current = true
    return () => {
      isMountedRef.current = false
      generationRef.current += 1
    }
  }, [])

  // 严格仅在 scope 改变时隔离清理旧在途并加载新 scope 的未知上传，绝不在 parts 改变时重复 restore
  const prevScopeRef = useRef(scope)
  useEffect(() => {
    if (prevScopeRef.current !== scope) {
      prevScopeRef.current = scope
      generationRef.current += 1
      for (const controller of abortControllersRef.current.values()) {
        controller.abort()
      }
      abortControllersRef.current.clear()
      activeRef.current.clear()
      filesRef.current.clear()
      for (const localId of previewUrlsRef.current.keys()) {
        revokePreviewUrl(localId, previewUrlsRef.current)
      }
      const unknowns = scope ? loadUnknownUploads(scope) : []
      const fresh: AttachmentUpload[] = unknowns.map((item) => ({
        localId: item.localId,
        uploadId: item.uploadId,
        filename: item.filename,
        mediaType: item.mediaType,
        sizeBytes: item.sizeBytes,
        sha256: item.sha256,
        status: 'complete_unknown' as const,
        progress: 0.8,
        previewUrl: null,
        detached: false,
        ...(item.imageTier ? { imageTier: item.imageTier } : {}),
      }))
      setUploads(fresh)
      uploadsRef.current = fresh
    }
  }, [scope])

  const updateUploads = useCallback(
    (updater: (current: AttachmentUpload[]) => AttachmentUpload[]) => {
      setUploads((current) => {
        const next = updater(current)
        uploadsRef.current = next
        return next
      })
    },
    [],
  )

  const patchUpload = useCallback(
    (localId: string, patch: Partial<AttachmentUpload>) => {
      updateUploads((current) =>
        current.map((upload) => (upload.localId === localId ? { ...upload, ...patch } : upload)),
      )
    },
    [updateUploads],
  )

  const dropUpload = useCallback(
    (localId: string) => {
      updateUploads((current) => current.filter((upload) => upload.localId !== localId))
    },
    [updateUploads],
  )

  /**
   * 释放上传句柄：终止在途请求，标记 pipeline 失效。
   * 确认发送后（detached === true）的 attachment 绝不删除服务端已被消息引用的句柄。
   */
  const releaseUpload = useCallback(
    (localId: string) => {
      activeRef.current.delete(localId)
      const controller = abortControllersRef.current.get(localId)
      if (controller) {
        controller.abort()
        abortControllersRef.current.delete(localId)
      }
      const record = uploadsRef.current.find((upload) => upload.localId === localId)
      filesRef.current.delete(localId)
      revokePreviewUrl(localId, previewUrlsRef.current)
      if (!record) {
        return
      }
      // 已随消息进入提交/发送管道的句柄已被引用，绝不调用 deleteUpload
      const uploadId = record.uploadId
      if (uploadId && !record.detached) {
        void service.deleteUpload(uploadId).catch(() => undefined)
      }
      if (scope) {
        removeStoredUnknownUpload(scope, localId)
      }
      dropUpload(localId)
    },
    [dropUpload, scope, service],
  )

  /**
   * 组件卸载：停止所有在途 hash/PUT 请求；
   * 对已确定 READY 且未消费（未发送、!detached）的 upload 句柄主动释放；
   * 绝对不能因 unknown 立即 destroy 恢复句柄，且已发送（detached）的不误删。
   */
  useEffect(
    () => () => {
      for (const controller of abortControllersRef.current.values()) {
        controller.abort()
      }
      abortControllersRef.current.clear()
      activeRef.current.clear()
      filesRef.current.clear()
      for (const localId of previewUrlsRef.current.keys()) {
        revokePreviewUrl(localId, previewUrlsRef.current)
      }
      for (const upload of uploadsRef.current) {
        if (upload.uploadId && upload.status === 'ready' && !upload.detached) {
          // 若草稿仍持有该 uploadId，保留至服务端 TTL 作为补偿，不破坏草稿恢复契约
          const isReferencedInDraft = partsRef.current && uploadOccurrence(upload, partsRef.current) > 0
          if (!isReferencedInDraft) {
            void service.deleteUpload(upload.uploadId).catch(() => undefined)
          }
        }
      }
    },
    [service],
  )

  const runPipeline = useCallback(
    async (record: AttachmentUpload, file: File) => {
      const localId = record.localId
      const capturedScope = scopeRef.current
      const capturedGeneration = generationRef.current
      const active = () => (
        isMountedRef.current
        && generationRef.current === capturedGeneration
        && activeRef.current.has(localId)
        && scopeRef.current === capturedScope
      )
      const controller = abortControllersRef.current.get(localId)
      let reservedUploadId: string | null = null
      try {
        patchUpload(localId, { status: 'uploading', progress: 0.1 })
        const sha256 = record.sha256 ?? (await hashFile(file, controller?.signal))
        if (!active()) {
          return
        }
        patchUpload(localId, { sha256, progress: 0.25 })
        const reservation = await service.reserveUpload({
          filename: record.filename,
          mediaType: record.mediaType,
          sizeBytes: record.sizeBytes,
          sha256,
        })
        reservedUploadId = reservation.id
        if (!active()) {
          void service.deleteUpload(reservation.id).catch(() => undefined)
          return
        }
        patchUpload(localId, { uploadId: reservation.id, progress: 0.4 })
        if (reservation.state === 'PENDING') {
          // PENDING = 对象尚未落库：必须直传；READY = sha256 命中，跳过直传。
          await service.uploadFile(reservation.presignedPut, file, controller?.signal)
          if (!active()) {
            void service.deleteUpload(reservation.id).catch(() => undefined)
            return
          }
          patchUpload(localId, { progress: 0.8 })
        }

        // 发送 complete 前预先在 capturedScope 中原子保存 identity，防范在途网络未知或刷新窗口丢句柄
        // Fail-Closed 机制：若存储满额（QuotaExceededError）或写入失败，抛出错误，
        // 绝不继续执行 completeUpload！
        if (capturedScope) {
          persistPendingUnknownUpload(capturedScope, {
            localId,
            uploadId: reservation.id,
            filename: record.filename,
            mediaType: record.mediaType,
            sizeBytes: record.sizeBytes,
            sha256: sha256 ?? record.sha256,
            imageTier: record.imageTier,
          })
        }

        let completed: StorageUploadDTO
        try {
          completed = await service.completeUpload(reservation.id)
        } catch (completeError) {
          if (!active()) {
            return
          }
          if (isAmbiguousUploadOutcome(completeError)) {
            // 未知结果：保留同 uploadId 精确重试，绝对不能 DELETE 已可能 READY 的句柄
            patchUpload(localId, {
              uploadId: reservation.id,
              status: 'complete_unknown',
              progress: 0.8,
            })
            onErrorRef.current?.({
              filename: record.filename,
              reason: completeError instanceof Error ? completeError.message : String(completeError),
              localId,
            })
            return
          }
          // 确定未接受错误（非 5xx/408/429/网络异常）：清理当前 identity，进入外层 cleanup
          if (capturedScope) {
            removeStoredUnknownUpload(capturedScope, localId)
          }
          throw completeError
        }

        if (!active()) {
          void service.deleteUpload(completed.id).catch(() => undefined)
          if (capturedScope) {
            removeStoredUnknownUpload(capturedScope, localId)
          }
          return
        }
        // complete 成功：仅从 capturedScope 移除当前已确认的条目
        if (capturedScope) {
          removeStoredUnknownUpload(capturedScope, localId)
        }
        // complete 返回同一 DTO：upload 句柄不变，绝不替换为 blobId。
        patchUpload(localId, {
          uploadId: completed.id,
          status: 'ready',
          progress: 1,
        })
      } catch (error) {
        if (reservedUploadId && active()) {
          void service.deleteUpload(reservedUploadId).catch(() => undefined)
        }
        if (capturedScope) {
          removeStoredUnknownUpload(capturedScope, localId)
        }
        if (!active()) {
          return
        }
        const message = error instanceof Error ? error.message : String(error)
        revokePreviewUrl(localId, previewUrlsRef.current)
        filesRef.current.delete(localId)
        activeRef.current.delete(localId)
        dropUpload(localId)
        onErrorRef.current?.({
          filename: record.filename,
          reason: message,
          localId,
        })
      }
    },
    [dropUpload, hashFile, patchUpload, service],
  )

  /** 重试未知结果条目的 completeUpload 操作。支持传入 localId 或 upload 对象。 */
  const retryComplete = useCallback(
    async (target: string | AttachmentUpload) => {
      const localId = typeof target === 'string' ? target : target.localId
      const capturedScope = scopeRef.current
      const capturedGeneration = generationRef.current
      const record = uploadsRef.current.find((u) => u.localId === localId)
      if (!record || !record.uploadId || record.status !== 'complete_unknown') {
        return
      }
      const capturedUploadId = record.uploadId
      const capturedFilename = record.filename
      const isStillActive = () => (
        isMountedRef.current
        && generationRef.current === capturedGeneration
        && scopeRef.current === capturedScope
      )

      patchUpload(localId, { status: 'uploading', progress: 0.9 })
      try {
        const completed = await service.completeUpload(capturedUploadId)
        // 原 scope 持久清理按原 identity 执行，防范旧条目残留
        if (capturedScope) {
          removeStoredUnknownUpload(capturedScope, localId)
        }
        // 作用域切换或组件卸载后，旧响应绝不污染新 UI
        if (!isStillActive()) {
          return
        }
        patchUpload(localId, {
          uploadId: completed.id,
          status: 'ready',
          progress: 1,
        })
      } catch (error) {
        if (isAmbiguousUploadOutcome(error)) {
          if (!isStillActive()) {
            return
          }
          patchUpload(localId, { status: 'complete_unknown', progress: 0.8 })
        } else {
          // 确定未接受错误：释放原句柄，清理原 scope
          void service.deleteUpload(capturedUploadId).catch(() => undefined)
          if (capturedScope) {
            removeStoredUnknownUpload(capturedScope, localId)
          }
          if (!isStillActive()) {
            return
          }
          dropUpload(localId)
        }
        if (!isStillActive()) {
          return
        }
        onErrorRef.current?.({
          filename: capturedFilename,
          reason: error instanceof Error ? error.message : String(error),
          localId,
        })
      }
    },
    [dropUpload, patchUpload, service],
  )

  /**
   * 添加文件；返回创建的有效注册表条目（校验失败文件直接触发 onError，不产生条目）。
   * 每个文件创建专用的 AbortController 贯穿散列与直传。
   */
  const addFiles = useCallback(
    (files: File[]): AttachmentUpload[] => {
      const created: AttachmentUpload[] = []
      const fresh: AttachmentUpload[] = []
      for (const file of files) {
        const localId = createPartId()
        const validationError = validateUploadFile(file)
        if (validationError) {
          onErrorRef.current?.({
            filename: file.name,
            reason: validationError,
            localId,
          })
          continue
        }
        const previewUrl = createPreviewUrl(file)
        if (previewUrl) {
          previewUrlsRef.current.set(localId, previewUrl)
        }
        const isImage = mediaKindOf(file.type) === 'image'
        const imageTier: ImageInputTier | undefined = isImage ? '720P' : undefined
        const record: AttachmentUpload = {
          localId,
          uploadId: null,
          filename: file.name,
          mediaType: file.type,
          sizeBytes: file.size,
          sha256: null,
          status: 'uploading',
          progress: 0,
          previewUrl,
          detached: false,
          ...(imageTier ? { imageTier } : {}),
        }
        created.push(record)
        fresh.push(record)
        filesRef.current.set(localId, file)
        activeRef.current.add(localId)
        abortControllersRef.current.set(localId, new AbortController())
      }
      if (fresh.length > 0) {
        updateUploads((current) => [...current, ...fresh])
      }
      for (const record of fresh) {
        const file = filesRef.current.get(record.localId)
        if (file) {
          void runPipeline(record, file)
        }
      }
      return created
    },
    [runPipeline, updateUploads],
  )

  /** 挂起/恢复条目的 detached 标记（发送结果未定时隐藏，恢复时重新显示）。 */
  const markDetached = useCallback(
    (localIds: ReadonlySet<string>, detached: boolean) => {
      if (localIds.size === 0) {
        return
      }
      updateUploads((current) =>
        current.map((upload) => (localIds.has(upload.localId) ? { ...upload, detached } : upload)),
      )
    },
    [updateUploads],
  )

  /** 更新指定条目的图片输入档位。 */
  const updateImageTier = useCallback(
    (localId: string, imageTier: ImageInputTier) => {
      patchUpload(localId, { imageTier })
    },
    [patchUpload],
  )

  return {
    uploads,
    addFiles,
    releaseUpload,
    retryComplete,
    markDetached,
    updateImageTier,
  }
}

function createPreviewUrl(file: File): string | null {
  const kind = mediaKindOf(file.type)
  if (
    (kind !== 'image' && kind !== 'video')
    || typeof URL === 'undefined'
    || typeof URL.createObjectURL !== 'function'
  ) {
    return null
  }
  try {
    return URL.createObjectURL(file)
  } catch {
    return null
  }
}

function revokePreviewUrl(localId: string, urls: Map<string, string>): void {
  const url = urls.get(localId)
  if (!url) {
    return
  }
  urls.delete(localId)
  if (typeof URL !== 'undefined' && typeof URL.revokeObjectURL === 'function') {
    try {
      URL.revokeObjectURL(url)
    } catch {
      // 预览释放失败不影响上传句柄清理。
    }
  }
}

/** 记录在 parts 中的出现次数（localId 或服务端 upload 句柄均可匹配）。 */
export function uploadOccurrence(upload: AttachmentUpload, parts: ComposerPart[]): number {
  return parts.reduce(
    (count, part) =>
      part.type === 'attachment'
      && (part.uploadId === upload.localId || part.uploadId === upload.uploadId)
        ? count + 1
        : count,
    0,
  )
}

/** 创建引用该上传的 attachment part（上传完成前以 localId 占位）。 */
export function partForUpload(upload: AttachmentUpload): ComposerPart {
  return createAttachmentPart(upload.localId, upload.filename, upload.imageTier)
}

/** 移除引用指定上传的全部 attachment parts（上传注册表 X）。 */
export function removePartsForUpload(
  upload: AttachmentUpload,
  parts: ComposerPart[],
): ComposerPart[] {
  return removePartsByUpload(parts, new Set([upload.localId, upload.uploadId ?? '']))
}

/** 该消息是否可发送：有内容，且所有 attachment parts 对应的上传均已 ready。 */
export function canSubmitParts(parts: ComposerPart[], uploads: AttachmentUpload[]): boolean {
  const hasContent = parts.some((part) => part.type !== 'text' || part.text.trim() !== '')
  if (!hasContent) {
    return false
  }
  for (const part of parts) {
    if (part.type !== 'attachment') {
      continue
    }
    const record = uploads.find(
      (upload) => upload.localId === part.uploadId || upload.uploadId === part.uploadId,
    )
    if (record?.status !== 'ready') {
      return false
    }
  }
  return true
}
