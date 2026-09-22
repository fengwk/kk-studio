import { useCallback, useEffect, useRef } from 'react'
import { canvasFileDescriptor } from '@/features/canvas/canvas-file'
import { isCanonicalUuid } from '@/shared/lib/uuid'
import {
  createWorkerHasher,
  validateUploadFile,
  type HashFile,
} from '@/features/ai/composer'
import type {
  CanvasCommandDTO,
  CanvasTransformDTO,
  UUIDString,
} from '@/shared/api/contracts/studio'
import { storageService, type StorageService } from '@/shared/api/storage-service'

const canvasUploadHasher = createWorkerHasher()

export interface CanvasUploadPipelineOptions {
  canvasId: UUIDString | null
  storageService?: StorageService
  hashFile?: HashFile
  executeCommands: (commands: CanvasCommandDTO[]) => Promise<unknown>
  reserveNodeAlias: (preferred: string) => string
  releaseNodeAlias: (alias: string) => void
  nextTransform: () => CanvasTransformDTO
  onUploadProgress: (localId: string, progress: number | null) => void
  setToast: (toast: string) => void
}

export interface CanvasUploadPipelineActions {
  uploadFiles: (files: FileList | File[]) => Promise<void>
  /** 离开/切换画布时作废在途批次并清空全部 progress；下一次 uploadFiles 自动使用新批次。 */
  resetUploads: () => void
}

/**
 * Canvas 资源上传管线：Web Worker SHA-256 → reserve → PENDING 直传/READY 跳过 →
 * complete → CREATE_RESOURCE_NODE。单文件失败不阻断后续文件；切换画布时整批作废
 * （每个 await 后校验批次 epoch），alias 一律在 finally 释放，progress 在所有退出路径清理。
 */
export function useCanvasUploadPipeline(options: CanvasUploadPipelineOptions): CanvasUploadPipelineActions {
  const {
    canvasId,
    executeCommands,
    reserveNodeAlias,
    releaseNodeAlias,
    nextTransform,
    onUploadProgress,
    setToast,
  } = options
  const service = options.storageService ?? storageService
  const hashFile = options.hashFile ?? canvasUploadHasher
  const batchEpochRef = useRef(0)
  const localIdSequenceRef = useRef(0)
  const progressKeysRef = useRef(new Set<string>())

  useEffect(() => () => {
    batchEpochRef.current += 1
    progressKeysRef.current.clear()
  }, [])

  const setUploadProgress = useCallback((localId: string, progress: number | null) => {
    if (progress === null) {
      progressKeysRef.current.delete(localId)
    } else {
      progressKeysRef.current.add(localId)
    }
    onUploadProgress(localId, progress)
  }, [onUploadProgress])

  const uploadFile = useCallback(async (file: File, active: () => boolean) => {
    if (!active()) {
      return
    }
    const descriptor = canvasFileDescriptor(file)
    if (!descriptor) {
      setToast(`不支持的文件类型：${file.name}`)
      return
    }
    const sizeError = validateUploadFile(file)
    if (sizeError) {
      setToast(sizeError)
      return
    }
    // localId 带递增 suffix：同元数据（同名/同 size/同 lastModified）文件不会共用
    // 同一个 progress key；filename 仍为 key 首段，UI 按 split(':')[0] 展示不受影响。
    const localId = `${file.name}:${file.lastModified}:${file.size}:${localIdSequenceRef.current++}`
    try {
      setUploadProgress(localId, 0.1)
      const sha256 = await hashFile(file)
      if (!active()) {
        return
      }
      setUploadProgress(localId, 0.3)
      const reservation = await service.reserveUpload({
        filename: file.name,
        mediaType: descriptor.mediaType,
        sizeBytes: file.size,
        sha256,
      })
      if (!active()) {
        // 画布资源上传的句柄只由服务端过期回收：reserve/直传后作废不主动删除。
        return
      }
      setUploadProgress(localId, 0.5)
      if (reservation.state === 'PENDING') {
        // PENDING = 对象尚未落库：必须直传；READY = sha256 命中，跳过直传。
        await service.uploadFile(reservation.presignedPut, file)
        if (!active()) {
          return
        }
        setUploadProgress(localId, 0.8)
      }
      const completed = await service.completeUpload(reservation.id)
      if (!active()) {
        return
      }
      // 共享存储的 upload 句柄是服务端生成的 canonical UUID：命令引用前校验。
      const uploadId = completed.id
      if (!isCanonicalUuid(uploadId)) {
        throw new Error('存储服务返回了无效的上传句柄')
      }
      // 最终命令失败不删除已完成的句柄：它可能已被服务端资源引用，
      // 交由存储过期回收；绝不删除已消费（complete）的句柄。
      const alias = reserveNodeAlias(file.name)
      try {
        await executeCommands([{
          type: 'CREATE_RESOURCE_NODE',
          nodeId: crypto.randomUUID(),
          name: alias,
          uploadIds: [uploadId],
          transform: nextTransform(),
        }])
        if (!active()) {
          return
        }
        setToast(`已上传 ${file.name}`)
      } finally {
        releaseNodeAlias(alias)
      }
    } catch (error) {
      if (!active()) {
        return
      }
      setToast(error instanceof Error ? error.message : `上传 ${file.name} 失败`)
    } finally {
      // reset/unmount 已统一清过 progress，避免重复发 null 事件。
      if (active()) {
        setUploadProgress(localId, null)
      }
    }
  }, [
    executeCommands,
    hashFile,
    nextTransform,
    releaseNodeAlias,
    reserveNodeAlias,
    service,
    setToast,
    setUploadProgress,
  ])

  const uploadFiles = useCallback(async (files: FileList | File[]) => {
    if (!canvasId) {
      return
    }
    const epoch = batchEpochRef.current
    const active = () => batchEpochRef.current === epoch
    for (const file of Array.from(files)) {
      await uploadFile(file, active)
    }
  }, [canvasId, uploadFile])

  const resetUploads = useCallback(() => {
    batchEpochRef.current += 1
    for (const localId of progressKeysRef.current) {
      onUploadProgress(localId, null)
    }
    progressKeysRef.current.clear()
  }, [onUploadProgress])

  return { uploadFiles, resetUploads }
}
