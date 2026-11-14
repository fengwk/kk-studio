import { useCallback, useEffect, useRef, useState } from 'react'
import {
  revokePreviewUrl,
  unknownUploadsAsRecords,
  uploadOccurrence,
  type AttachmentUpload,
  type AttachmentUploadError,
} from '@/features/thread/composer/attachment-uploads'
import type { ComposerPart } from '@/features/thread/composer/composer-parts'
import type { StorageService } from '@/shared/api/storage-service'
import { removeStoredUnknownUpload } from '@/features/thread/composer/composer-draft'

export interface UploadRegistryOptions {
  service: StorageService
  scope?: string
  parts?: ComposerPart[]
  onError?: (error: AttachmentUploadError) => void
}

/**
 * 上传注册表的唯一持有者：条目状态、每个条目的 File/预览 URL/在途请求，以及
 * scope 切换与卸载时的清理。上传管线只通过这里暴露的读写入口改动注册表。
 */
export function useUploadRegistry({ service, scope, parts, onError }: UploadRegistryOptions) {
  const scopeRef = useRef(scope)
  useEffect(() => {
    scopeRef.current = scope
  }, [scope])
  const partsRef = useRef(parts)
  useEffect(() => {
    partsRef.current = parts
  }, [parts])
  const onErrorRef = useRef(onError)
  useEffect(() => {
    onErrorRef.current = onError
  }, [onError])

  const [uploads, setUploads] = useState<AttachmentUpload[]>(() => unknownUploadsAsRecords(scope))
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
      const fresh = unknownUploadsAsRecords(scope)
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

  return {
    uploads,
    uploadsRef,
    filesRef,
    previewUrlsRef,
    activeRef,
    abortControllersRef,
    isMountedRef,
    generationRef,
    scopeRef,
    onErrorRef,
    patchUpload,
    dropUpload,
    updateUploads,
    releaseUpload,
  }
}

export type UploadRegistry = ReturnType<typeof useUploadRegistry>
