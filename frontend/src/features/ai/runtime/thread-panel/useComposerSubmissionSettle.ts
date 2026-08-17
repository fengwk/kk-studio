import { useCallback, useEffect, useRef } from 'react'
import {
  partsKey,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'
import {
  uploadOccurrence,
  type AttachmentUpload,
} from '@/features/ai/composer'

export interface SubmissionSettleOptions {
  /** 当前草稿 parts（提交后可能被清空/编辑/恢复）。 */
  parts: ComposerPart[]
  /** 提交在途标记：true -> false 表示上一轮提交结果确定。 */
  pending: boolean
  /** 上传注册表；settle 与恢复决策按条目 localId/uploadId 匹配。 */
  uploads: AttachmentUpload[]
  /** 挂起/恢复条目的 detached 标记。 */
  markDetached: (localIds: ReadonlySet<string>, detached: boolean) => void
  /** 释放上传句柄（调用方确认不再引用后 best-effort DELETE）。 */
  releaseUpload: (localId: string) => void
}

export interface SubmissionSettle {
  /**
   * 记录本地草稿快照并进入提交周期；同时释放上一轮已 settle 的 detached 残留
   * （它们的消息已确定不再回滚）。
   */
  commit: (localDraft: ComposerPart[]) => void
}

/** 提交快照引用的上传条目 localId 集合（本地草稿直接按 localId 匹配）。 */
export function submittedUploadIds(
  submitted: ComposerPart[],
  uploads: AttachmentUpload[],
): Set<string> {
  return new Set(
    uploads
      .filter((upload) => uploadOccurrence(upload, submitted) > 0)
      .map((upload) => upload.localId),
  )
}

/** 未被任何 parts 引用且未 detached 的条目（pill 移除/无提交周期时调用）。 */
export function unreferencedUploads(
  uploads: AttachmentUpload[],
  parts: ComposerPart[],
): AttachmentUpload[] {
  return uploads.filter(
    (upload) => !upload.detached && uploadOccurrence(upload, parts) === 0,
  )
}

/**
 * 提交 settle 状态机：统一 submitted draft 快照、settle transition（提交后的
 * 清空/编辑隐藏上传，失败恢复重新挂载）与 pending 确定后的 detached 释放。
 */
export function useComposerSubmissionSettle({
  parts,
  pending,
  uploads,
  markDetached,
  releaseUpload,
}: SubmissionSettleOptions): SubmissionSettle {
  // 提交时的本地草稿快照（客户端 localId parts，trim 后）：发送失败恢复（相同
  // partsKey）时重新挂载上传条目；其余任何 parts 变化都视为上一轮提交已 settle
  // （条目隐藏，等待结果）。与提交 payload（server uploadId）分开保存——恢复
  // 比对只可能命中本地草稿。
  const submittedDraftRef = useRef<ComposerPart[] | null>(null)
  const settleTransitionRef = useRef(false)
  const submittingRef = useRef(false)
  const partsRef = useRef(parts)
  const uploadsRef = useRef(uploads)
  const markDetachedRef = useRef(markDetached)
  const releaseUploadRef = useRef(releaseUpload)

  // settle 决策只响应 parts/pending 转移；parts/uploads 与回调在每个渲染结束后同步，
  // 使转移发生时总能读到最新注册表，且不会因上传进度事件重入决策 effect。
  useEffect(() => {
    partsRef.current = parts
    uploadsRef.current = uploads
    markDetachedRef.current = markDetached
    releaseUploadRef.current = releaseUpload
  })

  useEffect(() => {
    const submitted = submittedDraftRef.current
    if (submitted == null) {
      // 无提交周期：释放不再被引用且未挂起的条目（pill 移除后的回收）。
      const currentUploads = uploadsRef.current
      for (const upload of unreferencedUploads(currentUploads, parts)) {
        releaseUploadRef.current(upload.localId)
      }
      return
    }
    if (partsKey(parts) === partsKey(submitted)) {
      if (settleTransitionRef.current) {
        // 发送失败的恢复：重新挂载全部上传条目。
        settleTransitionRef.current = false
        submittedDraftRef.current = null
        markDetachedRef.current(
          submittedUploadIds(submitted, uploadsRef.current),
          false,
        )
      }
      return
    }
    // 提交后的清空或编辑：条目隐藏等待结果；恢复前绝不 DELETE
    // （submittedDraftRef 保留本地草稿快照，供失败恢复比对）。
    if (!settleTransitionRef.current) {
      settleTransitionRef.current = true
      markDetachedRef.current(
        submittedUploadIds(submitted, uploadsRef.current),
        true,
      )
    }
  }, [parts])

  // pending true -> false：上一轮提交结果确定；draft 未恢复 => 发送成功，释放挂起条目。
  useEffect(() => {
    if (!submittingRef.current || pending) {
      return
    }
    submittingRef.current = false
    const submitted = submittedDraftRef.current
    if (submitted != null && partsKey(partsRef.current) !== partsKey(submitted)) {
      submittedDraftRef.current = null
      settleTransitionRef.current = false
      for (const upload of uploadsRef.current) {
        if (upload.detached) {
          releaseUploadRef.current(upload.localId)
        }
      }
    }
  }, [pending])

  const commit = useCallback((localDraft: ComposerPart[]) => {
    // 上一轮已 settle 的 detached 残留在此释放（它们的消息已确定不再回滚）。
    for (const upload of uploadsRef.current) {
      if (upload.detached) {
        releaseUploadRef.current(upload.localId)
      }
    }
    submittingRef.current = true
    submittedDraftRef.current = localDraft
  }, [])

  return { commit }
}
