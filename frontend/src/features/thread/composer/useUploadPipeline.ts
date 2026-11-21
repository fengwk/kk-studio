import { useCallback, useRef, type MutableRefObject } from 'react'
import {
  createPreviewUrl,
  isAmbiguousUploadOutcome,
  revokePreviewUrl,
  type AttachmentUpload,
  type AttachmentUploadError,
} from '@/features/thread/composer/attachment-uploads'
import {
  persistPendingUnknownUpload,
  removeStoredUnknownUpload,
} from '@/features/thread/composer/composer-draft'
import { scaleImageFile } from '@/shared/lib/image-scaler'
import type { ImageInputTier } from '@/features/thread/composer/composer-parts'
import type { UploadRegistry } from '@/features/thread/composer/useUploadRegistry'
import { request } from '@/shared/api/client'
import type { StorageUploadDTO } from '@/shared/api/generated/contracts'
import type { StorageService } from '@/shared/api/storage-service'
import { mediaKindOf, type HashFile } from '@/shared/lib/upload-file'
import { isImageTier } from '@/features/thread/runtime/agent-pane/pane-target'

export interface UploadPipelineOptions {
  registry: UploadRegistry
  service: StorageService
  hashFile: HashFile
  keepRetryableFailures: boolean
}


/** 默认图片档位只读一次平台设置；读取失败时清空缓存，下一次上传重新读取。 */
function loadDefaultImageTier(
  cache: MutableRefObject<Promise<ImageInputTier> | null>,
): Promise<ImageInputTier> {
  if (!cache.current) {
    const pending = request('GET /api/platform/settings', {}).then((settings) => {
      if (!isImageTier(settings.storageMedia.imageTier)) {
        throw new Error(`Unsupported default image tier: ${settings.storageMedia.imageTier}`)
      }
      return settings.storageMedia.imageTier
    })
    cache.current = pending
    void pending.catch(() => {
      if (cache.current === pending) {
        cache.current = null
      }
    })
  }
  return cache.current
}

/** 缩放图片，并返回直传用的文件与补齐缩放信息后的上传条目。 */
async function scaleImageForUpload(
  file: File,
  record: AttachmentUpload,
  imageTier: ImageInputTier,
): Promise<{ file: File; record: AttachmentUpload }> {
  const scaled = await scaleImageFile(file, imageTier)
  return {
    file: scaled.file,
    record: {
      ...record,
      mediaType: scaled.file.type,
      sizeBytes: scaled.file.size,
      imageTier,
      format: scaled.format,
      originalWidth: scaled.originalWidth,
      originalHeight: scaled.originalHeight,
      storedWidth: scaled.storedWidth,
      storedHeight: scaled.storedHeight,
      sourceFileAvailable: true,
    },
  }
}


interface CompleteReservationOptions {
  service: StorageService
  localId: string
  uploadId: string
  /** 已补齐缩放信息的条目：identity 记录必须与直传的文件一致。 */
  record: AttachmentUpload
  sha256: string
  scope?: string
  isStillActive: () => boolean
  patchUpload: (localId: string, patch: Partial<AttachmentUpload>) => void
  onErrorRef: MutableRefObject<((error: AttachmentUploadError) => void) | undefined>
  keepRetryableFailures: boolean
}

/**
 * 把预留的 upload 移交 ready。
 *
 * complete 前先在 scope 中原子保存 identity，防范在途网络未知或刷新窗口丢句柄；
 * Fail-Closed：若存储满额（QuotaExceededError）或写入失败，抛出错误，绝不继续执行 complete。
 * complete 结果未知时保留同一 uploadId 供幂等重试，绝不 DELETE 已可能 READY 的句柄。
 */
async function completeReservation({
  service,
  localId,
  uploadId,
  record,
  sha256,
  scope,
  isStillActive,
  patchUpload,
  onErrorRef,
  keepRetryableFailures,
}: CompleteReservationOptions): Promise<void> {
  if (scope) {
    persistPendingUnknownUpload(scope, {
      localId,
      uploadId,
      filename: record.filename,
      mediaType: record.mediaType,
      sizeBytes: record.sizeBytes,
      sha256,
      imageTier: record.imageTier,
    })
  }

  let completed: StorageUploadDTO
  try {
    completed = await service.completeUpload(uploadId)
  } catch (completeError) {
    if (!isStillActive()) {
      return
    }
    if (isAmbiguousUploadOutcome(completeError)) {
      // 未知结果：保留同 uploadId 精确重试，绝对不能 DELETE 已可能 READY 的句柄
      patchUpload(localId, {
        uploadId,
        status: 'complete_unknown',
        progress: 0.8,
      })
      if (!keepRetryableFailures) {
        onErrorRef.current?.({
          filename: record.filename,
          reason: completeError instanceof Error ? completeError.message : String(completeError),
          localId,
        })
      }
      return
    }
    // 确定未接受错误（非 5xx/408/429/网络异常）：清理当前 identity，进入外层 cleanup
    if (scope && isStillActive()) {
      removeStoredUnknownUpload(scope, localId)
    }
    throw completeError
  }

  if (!isStillActive()) {
    void service.deleteUpload(completed.id).catch(() => undefined)
    if (scope) {
      removeStoredUnknownUpload(scope, localId)
    }
    return
  }
  // complete 成功：仅从 scope 移除当前已确认的条目
  if (scope) {
    removeStoredUnknownUpload(scope, localId)
  }
  // complete 返回同一 DTO：upload 句柄不变，绝不替换为 blobId。
  patchUpload(localId, {
    uploadId: completed.id,
    status: 'ready',
    progress: 1,
  })
}

/**
 * 一次上传的完整管线：缩放图片 -> 散列 -> 预留 -> 直传 -> 预写 identity -> complete。
 *
 * 每个异步步骤之后都重新核对“这条上传是否仍然有效”；失效就不再写回状态。
 * complete 的结果是否未知决定句柄能否丢弃：未知时保留同一 uploadId 供幂等重试。
 */
export function useUploadPipeline({ registry, service, hashFile, keepRetryableFailures }: UploadPipelineOptions) {
  const {
    uploadsRef,
    previewUrlsRef,
    activeRef,
    abortControllersRef,
    filesRef,
    isMountedRef,
    generationRef,
    scopeRef,
    onErrorRef,
    patchUpload,
    dropUpload,
  } = registry
  /** 默认图片档位只读一次平台设置；读取失败时清空，下一次上传重新读取。 */
  const defaultImageTierRef = useRef<Promise<ImageInputTier> | null>(null)

  const runPipeline = useCallback(
    async (record: AttachmentUpload, file: File) => {
      const localId = record.localId
      const capturedScope = scopeRef.current
      const capturedGeneration = generationRef.current
      const active = () => (
        isMountedRef.current
        && generationRef.current === capturedGeneration
        && activeRef.current.has(localId)
        && abortControllersRef.current.get(localId) === controller
        && scopeRef.current === capturedScope
      )
      const controller = abortControllersRef.current.get(localId)
      let reservedUploadId: string | null = null
      try {
        patchUpload(localId, { status: 'uploading', progress: 0.1 })
        let uploadFile = file
        let uploadRecord = record
        if (mediaKindOf(record.mediaType) === 'image') {
          const imageTier = record.imageTier || (await loadDefaultImageTier(defaultImageTierRef))
          const scaled = await scaleImageForUpload(file, record, imageTier)
          if (!active()) {
            return
          }
          uploadFile = scaled.file
          uploadRecord = scaled.record
          revokePreviewUrl(localId, previewUrlsRef.current)
          const previewUrl = createPreviewUrl(uploadFile)
          if (previewUrl) {
            previewUrlsRef.current.set(localId, previewUrl)
          }
          patchUpload(localId, { ...uploadRecord, previewUrl, progress: 0.1 })
        }
        const sha256 = uploadRecord.sha256 ?? (await hashFile(uploadFile, controller?.signal))
        if (!active()) {
          return
        }
        patchUpload(localId, { sha256, progress: 0.25 })
        const reservation = await service.reserveUpload({
          filename: uploadRecord.filename,
          mediaType: uploadRecord.mediaType,
          sizeBytes: String(uploadRecord.sizeBytes),
          sha256,
          format: uploadRecord.format ?? null,
          originalWidth: uploadRecord.originalWidth ?? null,
          originalHeight: uploadRecord.originalHeight ?? null,
          storedWidth: uploadRecord.storedWidth ?? null,
          storedHeight: uploadRecord.storedHeight ?? null,
        })
        reservedUploadId = reservation.id
        if (!active()) {
          void service.deleteUpload(reservation.id).catch(() => undefined)
          return
        }
        patchUpload(localId, { uploadId: reservation.id, progress: 0.4 })
        if (reservation.type === 'PENDING') {
          // PENDING = 对象尚未落库：必须直传；READY = sha256 命中，跳过直传。
          await service.uploadFile(reservation.presignedPut, uploadFile, controller?.signal)
          if (!active()) {
            void service.deleteUpload(reservation.id).catch(() => undefined)
            return
          }
          patchUpload(localId, { progress: 0.8 })
        }

        await completeReservation({
          service,
          localId,
          uploadId: reservation.id,
          record: uploadRecord,
          sha256,
          scope: capturedScope,
          isStillActive: active,
          patchUpload,
          onErrorRef,
          keepRetryableFailures,
        })
      } catch (error) {
        if (reservedUploadId && active()) {
          void service.deleteUpload(reservedUploadId).catch(() => undefined)
        }
        if (capturedScope && active()) {
          removeStoredUnknownUpload(capturedScope, localId)
        }
        if (!active()) {
          return
        }
        if (keepRetryableFailures) {
          patchUpload(localId, { uploadId: null, sha256: null, status: 'failed', progress: 0 })
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
    [
      abortControllersRef,
      activeRef,
      dropUpload,
      filesRef,
      generationRef,
      hashFile,
      isMountedRef,
      keepRetryableFailures,
      onErrorRef,
      patchUpload,
      previewUrlsRef,
      scopeRef,
      service,
    ],
  )

  const retryUpload = useCallback(
    async (target: string | AttachmentUpload) => {
      const localId = typeof target === 'string' ? target : target.localId
      const capturedScope = scopeRef.current
      const capturedGeneration = generationRef.current
      const record = uploadsRef.current.find((u) => u.localId === localId)
      if (record?.status === 'failed') {
        const file = filesRef.current.get(localId)
        if (file) {
          abortControllersRef.current.set(localId, new AbortController())
          await runPipeline(record, file)
        }
        return
      }
      if (!record || !record.uploadId || record.status !== 'complete_unknown') {
        return
      }
      const capturedUploadId = record.uploadId
      const capturedFilename = record.filename
      const isStillActive = () => (
        isMountedRef.current
        && generationRef.current === capturedGeneration
        && scopeRef.current === capturedScope
        && uploadsRef.current.some((u) => u.localId === localId && u.uploadId === capturedUploadId)
      )

      patchUpload(localId, { status: 'uploading', progress: 0.9 })
      try {
        const completed = await service.completeUpload(capturedUploadId)
        // 只有该 upload 仍由当前界面持有并成功移交 ready state 时清 pending；
        // 如果用户切 scope、unmount 或已移除，旧 scope 保留原 unknown identity 供下次同 uploadId 幂等恢复。
        // 绝不删除该 resource，勿把完成但未呈现看作可抛弃。
        if (!isStillActive()) {
          return
        }
        patchUpload(localId, {
          uploadId: completed.id,
          status: 'ready',
          progress: 1,
        })
        if (capturedScope) {
          removeStoredUnknownUpload(capturedScope, localId)
        }
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
          if (keepRetryableFailures && filesRef.current.has(localId)) {
            patchUpload(localId, { uploadId: null, sha256: null, status: 'failed', progress: 0 })
            return
          }
          dropUpload(localId)
        }
        if (keepRetryableFailures || !isStillActive()) {
          return
        }
        onErrorRef.current?.({
          filename: capturedFilename,
          reason: error instanceof Error ? error.message : String(error),
          localId,
        })
      }
    },
    [abortControllersRef, dropUpload, filesRef, generationRef, isMountedRef, keepRetryableFailures,
      onErrorRef, patchUpload, runPipeline, scopeRef, service, uploadsRef],
  )

  return { runPipeline, retryComplete: retryUpload }
}
