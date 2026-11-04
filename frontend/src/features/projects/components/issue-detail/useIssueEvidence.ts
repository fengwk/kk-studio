import { useEffect, useRef, useState, type RefObject } from 'react'
import { useQuery, useQueryClient, type QueryKey } from '@tanstack/react-query'
import { validateUploadFile, type HashFile } from '@/shared/lib/upload-file'
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
  isUploading: boolean
  fileInputRef: RefObject<HTMLInputElement | null>
  handleFileSelected: (event: React.ChangeEvent<HTMLInputElement>) => Promise<void>
}

/**
 * Issue 公开证据：服务端列表查询、本地乐观列表与上传（校验、哈希、预留、直传、完成、挂接）。
 * 写操作进行中或有未决侧车时拒绝上传，拒绝理由沿用统一的操作错误位。
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
  const { data: serverEvidences = [] } = useQuery({
    queryKey: evidenceQueryKey,
    queryFn: () => (api.listIssueEvidence ? api.listIssueEvidence(issueId!) : Promise.resolve([])),
    enabled: isOpen && Boolean(issueId),
  })

  const [localEvidences, setLocalEvidences] = useState<IssueEvidenceDTO[]>([])
  const [isUploading, setIsUploading] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)

  // 关闭时清理本地证据，重新打开以服务端事实为准 (I09)。
  useEffect(() => {
    if (!isOpen) {
      setLocalEvidences([])
    }
  }, [isOpen])

  const evidences = mergeEvidences(localEvidences, serverEvidences)

  const handleFileSelected = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    if (!file || !issue) return
    if (isWriteBlocked || isActionPendingRef.current) {
      setActionError(t('projects.issue.uploadEvidenceBlockedByPending'))
      return
    }
    try {
      validateUploadFile(file)
    } catch (err) {
      setActionError(err instanceof Error ? err.message : t('projects.issue.fileValidationFailed'))
      return
    }

    isActionPendingRef.current = true
    setIsUploading(true)
    setActionError(null)
    try {
      const sha256 = await calculateFileSha256(file, hashFile)
      const upload = await storageService.reserveUpload({
        filename: file.name,
        sizeBytes: file.size,
        mediaType: file.type || 'application/octet-stream',
        sha256,
      })
      if (upload.state === 'PENDING') {
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

  return { evidences, isUploading, fileInputRef, handleFileSelected }
}
