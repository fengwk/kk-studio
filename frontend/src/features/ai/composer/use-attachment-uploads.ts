import { useCallback, useEffect, useRef, useState } from 'react'
import {
  createAttachmentPart,
  createPartId,
  removePartsByUpload,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'
import { storageService, type StorageService } from '@/shared/api/storage-service'
import type { StorageMediaKind } from '@/shared/api/contracts/storage'
import { translate } from '@/shared/i18n'

export type AttachmentUploadStatus = 'uploading' | 'ready' | 'error'

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
  error: string | null
  /** 本地图片/视频预览 URL；只在 Composer 生命周期内存在。 */
  previewUrl: string | null
  /** 提交后等待发送结果期间隐藏（发送失败恢复时重新挂载）。 */
  detached: boolean
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

export type HashFile = (file: File) => Promise<string>

/** 默认哈希实现：专用 Web Worker + Web Crypto SHA-256。 */
export function createWorkerHasher(): HashFile {
  let worker: Worker | null = null
  return (file: File) =>
    new Promise<string>((resolve, reject) => {
      const requestId = createPartId()
      worker ??= new Worker(new URL('./upload-hash.worker.ts', import.meta.url), {
        type: 'module',
      })
      const onMessage = (
        event: MessageEvent<{ requestId: string; sha256?: string; error?: string }>,
      ) => {
        if (event.data?.requestId !== requestId) {
          return
        }
        worker?.removeEventListener('message', onMessage)
        if (event.data.sha256) {
          resolve(event.data.sha256)
        } else {
          reject(new Error(event.data.error ?? 'SHA-256 failed'))
        }
      }
      worker.addEventListener('message', onMessage)
      worker.postMessage({ requestId, file })
    })
}

const defaultHashFile: HashFile = createWorkerHasher()

export function useAttachmentUploads(options?: {
  storageService?: StorageService
  hashFile?: HashFile
}) {
  const service = options?.storageService ?? storageService
  const hashFile = options?.hashFile ?? defaultHashFile
  const [uploads, setUploads] = useState<AttachmentUpload[]>([])
  const uploadsRef = useRef<AttachmentUpload[]>([])
  const filesRef = useRef(new Map<string, File>())
  const previewUrlsRef = useRef(new Map<string, string>())
  const activeRef = useRef(new Set<string>())

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
   * 释放上传句柄：标记 pipeline 失效，并对已预留的 upload 发起
   * best-effort DELETE（调用方已确认 draft 中不再引用）。
   */
  const releaseUpload = useCallback(
    (localId: string) => {
      activeRef.current.delete(localId)
      const record = uploadsRef.current.find((upload) => upload.localId === localId)
      filesRef.current.delete(localId)
      revokePreviewUrl(localId, previewUrlsRef.current)
      if (!record) {
        return
      }
      const uploadId = record.uploadId
      if (uploadId) {
        void service.deleteUpload(uploadId).catch(() => undefined)
      }
      dropUpload(localId)
    },
    [dropUpload, service],
  )

  useEffect(
    () => () => {
      activeRef.current.clear()
      filesRef.current.clear()
      for (const localId of previewUrlsRef.current.keys()) {
        revokePreviewUrl(localId, previewUrlsRef.current)
      }
    },
    [],
  )

  const runPipeline = useCallback(
    async (record: AttachmentUpload, file: File) => {
      const localId = record.localId
      const active = () => activeRef.current.has(localId)
      try {
        patchUpload(localId, { status: 'uploading', progress: 0.1, error: null })
        const sha256 = record.sha256 ?? (await hashFile(file))
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
        if (!active()) {
          // 上传途中被移除：尽力清理已预留但未完成的对象。
          void service.deleteUpload(reservation.id).catch(() => undefined)
          return
        }
        patchUpload(localId, { uploadId: reservation.id, progress: 0.4 })
        if (reservation.state === 'PENDING') {
          // PENDING = 对象尚未落库：必须直传；READY = sha256 命中，跳过直传。
          await service.uploadFile(reservation.presignedPut, file)
          if (!active()) {
            return
          }
          patchUpload(localId, { progress: 0.8 })
        }
        const completed = await service.completeUpload(reservation.id)
        if (!active()) {
          void service.deleteUpload(completed.id).catch(() => undefined)
          return
        }
        // complete 返回同一 DTO：upload 句柄不变，绝不替换为 blobId。
        patchUpload(localId, {
          uploadId: completed.id,
          status: 'ready',
          progress: 1,
        })
      } catch (error) {
        if (!active()) {
          return
        }
        patchUpload(localId, {
          status: 'error',
          error: error instanceof Error ? error.message : String(error),
        })
      }
    },
    [hashFile, patchUpload, service],
  )

  /**
   * 添加文件；返回创建的注册表条目（超限文件也会以 error 状态进入注册表）。
   *
   * 不做客户端去重：元数据相同的不同文件也必须获得各自独立的 upload 句柄
   * （服务端按 sha256 去重，返回 READY 免直传）。每个文件 = 一个新条目 + 新
   * attachment part 引用；重复粘贴同一文件会产生两个独立句柄。
   */
  const addFiles = useCallback(
    (files: File[]): AttachmentUpload[] => {
      const created: AttachmentUpload[] = []
      const fresh: AttachmentUpload[] = []
      for (const file of files) {
        const localId = createPartId()
        const validationError = validateUploadFile(file)
        const previewUrl = createPreviewUrl(file)
        if (previewUrl) {
          previewUrlsRef.current.set(localId, previewUrl)
        }
        const record: AttachmentUpload = {
          localId,
          uploadId: null,
          filename: file.name,
          mediaType: file.type,
          sizeBytes: file.size,
          sha256: null,
          status: validationError ? 'error' : 'uploading',
          progress: 0,
          error: validationError,
          previewUrl,
          detached: false,
        }
        created.push(record)
        fresh.push(record)
        filesRef.current.set(localId, file)
        activeRef.current.add(localId)
      }
      if (fresh.length > 0) {
        updateUploads((current) => [...current, ...fresh])
      }
      for (const record of fresh) {
        const file = filesRef.current.get(record.localId)
        if (file && record.status !== 'error') {
          void runPipeline(record, file)
        }
      }
      return created
    },
    [runPipeline, updateUploads],
  )

  const retryUpload = useCallback(
    (localId: string) => {
      const record = uploadsRef.current.find((upload) => upload.localId === localId)
      const file = filesRef.current.get(localId)
      if (!record || !file) {
        return
      }
      activeRef.current.add(localId)
      void runPipeline(record, file)
    },
    [runPipeline],
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

  return {
    uploads,
    addFiles,
    retryUpload,
    releaseUpload,
    markDetached,
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
  return createAttachmentPart(upload.localId, upload.filename)
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
  const hasContent = parts.some((part) => part.type === 'attachment' || part.text.trim() !== '')
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
