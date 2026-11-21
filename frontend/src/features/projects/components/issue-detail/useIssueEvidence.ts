import { useEffect, useRef, useState, type RefObject } from 'react'
import { useQuery, useQueryClient, type QueryKey } from '@tanstack/react-query'
import { readOriginalImageMetadata } from '@/shared/lib/image-metadata'
import { mediaKindOf, validateUploadFile, type HashFile } from '@/shared/lib/upload-file'
import type { StorageService } from '@/shared/api/storage-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'
import type { IssueDetailDTO, IssueEvidenceDTO } from '@/shared/api/generated/contracts'
import type { ProjectsApi } from '../../projects-api'
import { calculateFileSha256, mergeEvidences } from './evidence-files'

interface UseIssueEvidenceInput {
  isOpen: boolean
  issueId: string | null
  projectId: string
  issue: IssueDetailDTO['issue'] | undefined
  api: ProjectsApi
  storageService: StorageService
  hashFile?: HashFile
  isWriteBlocked: boolean
  isActionPendingRef: RefObject<boolean>
  setActionError: (message: string | null) => void
  issueQueryKey: QueryKey
}

interface UseIssueEvidenceResult {
  /** 本地刚上传的证据在前、服务端列表在后。 */
  evidences: IssueEvidenceDTO[]
  /** 权威计数：服务端读取未成功时为 null，不能把未知或本地乐观列表当成权威计数。 */
  evidenceCount: number | null
  isLoading: boolean
  loadFailed: boolean
  isRetrying: boolean
  retry: () => void
  isUploading: boolean
  fileInputRef: RefObject<HTMLInputElement | null>
  handleFileSelected: (event: React.ChangeEvent<HTMLInputElement>) => Promise<void>
}

/**
 * Issue 公开证据：服务端列表查询、本地乐观列表与上传（校验、哈希、预留、直传、完成、挂接）。
 * 写操作进行中或有未决侧车时拒绝上传，拒绝理由沿用统一的操作错误位。
 * 查询状态原样上抛：加载中、失败与真正的空列表由调用方分别呈现。
 */
export function useIssueEvidence({
  isOpen,
  issueId,
  projectId,
  issue,
  api,
  storageService,
  hashFile,
  isWriteBlocked,
  isActionPendingRef,
  setActionError,
  issueQueryKey,
}: UseIssueEvidenceInput): UseIssueEvidenceResult {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const evidenceQueryKey = queryKeys.projects.evidence(projectId, issueId ?? '')
  const evidenceQuery = useQuery({
    queryKey: evidenceQueryKey,
    queryFn: () => (api.listIssueEvidence ? api.listIssueEvidence(issueId!) : Promise.resolve([])),
    enabled: isOpen && Boolean(issueId),
  })

  const [localEvidences, setLocalEvidences] = useState<IssueEvidenceDTO[]>([])
  const [isUploading, setIsUploading] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)

  // 关闭时清理本地证据，重新打开以服务端事实为准。
  useEffect(() => {
    if (!isOpen) {
      setLocalEvidences([])
    }
  }, [isOpen])

  const evidences = mergeEvidences(localEvidences, evidenceQuery.data ?? [])
  // 计数只来自成功的服务端读取：加载中、失败以及仅有本地乐观上传时都不写数字。
  const evidenceCount = evidenceQuery.isSuccess ? evidences.length : null

  const handleFileSelected = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    if (!file || !issue) return
    if (isWriteBlocked || isActionPendingRef.current) {
      setActionError(t('projects.issue.uploadEvidenceBlockedByPending'))
      return
    }
    const sizeError = validateUploadFile(file)
    if (sizeError) {
      setActionError(sizeError)
      return
    }

    isActionPendingRef.current = true
    setIsUploading(true)
    setActionError(null)
    try {
      const mediaType = file.type || 'application/octet-stream'
      // 原文件字节直接上传；图片必须先解码出真实尺寸，失败时不得 reserve。
      const imageMetadata = mediaKindOf(mediaType) === 'image'
        ? await readOriginalImageMetadata(file)
        : null
      const sha256 = await calculateFileSha256(file, hashFile)
      const upload = await storageService.reserveUpload({
        filename: file.name,
        sizeBytes: String(file.size),
        mediaType,
        sha256,
        ...(imageMetadata
          ? {
            format: imageMetadata.format,
            originalWidth: imageMetadata.originalWidth,
            originalHeight: imageMetadata.originalHeight,
            storedWidth: imageMetadata.storedWidth,
            storedHeight: imageMetadata.storedHeight,
          }
          : {}),
      })
      if (upload.type === 'PENDING') {
        await storageService.uploadFile(upload.presignedPut, file)
      }
      await storageService.completeUpload(upload.id)

      const published = await api.addIssueEvidence(issue.id, {
        uploadId: upload.id,
      })
      setLocalEvidences((prev) => [published, ...prev])
      await queryClient.invalidateQueries({ queryKey: evidenceQueryKey })
      await queryClient.invalidateQueries({ queryKey: issueQueryKey })
    } catch (err) {
      setActionError(err instanceof Error ? err.message : t('projects.issue.uploadEvidenceFailed'))
    } finally {
      isActionPendingRef.current = false
      setIsUploading(false)
      if (fileInputRef.current) {
        fileInputRef.current.value = ''
      }
    }
  }

  return {
    evidences,
    evidenceCount,
    isLoading: evidenceQuery.isPending,
    loadFailed: evidenceQuery.isError,
    isRetrying: evidenceQuery.isFetching,
    retry: () => void evidenceQuery.refetch({ cancelRefetch: false }),
    isUploading,
    fileInputRef,
    handleFileSelected,
  }
}
