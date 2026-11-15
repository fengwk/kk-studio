import { useCallback } from 'react'
import {
  createPreviewUrl,
  revokePreviewUrl,
  type AttachmentUpload,
  type AttachmentUploadError,
} from '@/features/thread/composer/attachment-uploads'
import {
  createPartId,
  type ComposerPart,
  type ImageInputTier,
} from '@/features/thread/composer/composer-parts'
import { removeStoredUnknownUpload } from '@/features/thread/composer/composer-draft'
import { useUploadPipeline } from '@/features/thread/composer/useUploadPipeline'
import { useUploadRegistry } from '@/features/thread/composer/useUploadRegistry'
import { storageService, type StorageService } from '@/shared/api/storage-service'
import { createWorkerHasher, mediaKindOf, validateUploadFile, type HashFile } from '@/shared/lib/upload-file'

export type {
  AttachmentUpload,
  AttachmentUploadError,
} from '@/features/thread/composer/attachment-uploads'
export {
  canSubmitParts,
  isAmbiguousUploadOutcome,
  partForUpload,
  removePartsForUpload,
  uploadOccurrence,
} from '@/features/thread/composer/attachment-uploads'

const defaultHashFile: HashFile = createWorkerHasher()

/**
 * Composer 的附件上传注册表：把条目状态（{@link useUploadRegistry}）与上传管线
 * （{@link useUploadPipeline}）编排成本组件需要的少量动作。
 */
export function useAttachmentUploads(options?: {
  storageService?: StorageService
  hashFile?: HashFile
  onError?: (error: AttachmentUploadError) => void
  scope?: string
  parts?: ComposerPart[]
}) {
  const service = options?.storageService ?? storageService
  const hashFile = options?.hashFile ?? defaultHashFile
  const registry = useUploadRegistry({
    service,
    scope: options?.scope,
    parts: options?.parts,
    onError: options?.onError,
  })
  const { runPipeline, retryComplete } = useUploadPipeline({ registry, service, hashFile })
  const {
    uploads,
    uploadsRef,
    filesRef,
    previewUrlsRef,
    activeRef,
    abortControllersRef,
    scopeRef,
    patchUpload,
    releaseUpload,
    updateUploads,
  } = registry

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
          registry.onErrorRef.current?.({
            filename: file.name,
            reason: validationError,
            localId,
          })
          continue
        }
        const previewUrl = mediaKindOf(file.type) === 'video' ? createPreviewUrl(file) : null
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
          status: 'uploading',
          progress: 0,
          previewUrl,
          detached: false,
          sourceFileAvailable: mediaKindOf(file.type) === 'image',
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
    [
      abortControllersRef,
      activeRef,
      filesRef,
      previewUrlsRef,
      registry.onErrorRef,
      runPipeline,
      updateUploads,
    ],
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

  /** 更新档位时总是从保留的源文件重新缩放并上传，旧句柄走既有 discard 路径。 */
  const updateImageTier = useCallback(
    (localId: string, imageTier: ImageInputTier) => {
      const record = uploadsRef.current.find((upload) => upload.localId === localId)
      const source = filesRef.current.get(localId)
      if (!record || record.imageTier === imageTier) {
        return
      }
      if (!source) {
        return
      }
      activeRef.current.add(localId)
      abortControllersRef.current.get(localId)?.abort()
      const controller = new AbortController()
      abortControllersRef.current.set(localId, controller)
      if (record.uploadId && !record.detached) {
        void service.deleteUpload(record.uploadId).catch(() => undefined)
      }
      if (scopeRef.current) {
        removeStoredUnknownUpload(scopeRef.current, localId)
      }
      revokePreviewUrl(localId, previewUrlsRef.current)
      const next: AttachmentUpload = {
        ...record,
        uploadId: null,
        mediaType: source.type,
        sizeBytes: source.size,
        sha256: null,
        status: 'uploading',
        progress: 0,
        previewUrl: null,
        imageTier,
        format: undefined,
        originalWidth: undefined,
        originalHeight: undefined,
        storedWidth: undefined,
        storedHeight: undefined,
      }
      patchUpload(localId, next)
      void runPipeline(next, source)
    },
    [
      abortControllersRef,
      activeRef,
      filesRef,
      patchUpload,
      previewUrlsRef,
      runPipeline,
      scopeRef,
      service,
      uploadsRef,
    ],
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
