import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  AlertCircle,
  AlertTriangle,
  Archive,
  Ban,
  Clock,
  Download,
  Pencil,
  Plus,
  RefreshCw,
  RotateCcw,
  ShieldAlert,
  Trash2,
  Upload,
  X,
} from 'lucide-react'
import { isConflictError } from '@/shared/api/client'
import {
  storageService as defaultStorageService,
  type StorageService,
} from '@/shared/api/storage-service'
import {
  createWorkerHasher,
  validateUploadFile,
  type HashFile,
} from '@/features/ai/composer'
import { presentConflict } from '@/shared/conflict/conflict-presenter'
import { queryKeys } from '@/shared/lib/query-keys'
import { createUuid } from '@/shared/lib/uuid'
import { useCatalogAgentNames } from '../useCatalogAgentNames'
import type { ProjectsApi } from '../projects-api'
import { projectsApi } from '../projects-api'
import type {
  IssueActivityDTO,
  IssueDetailDTO,
  IssueEvidenceDTO,
  IssueRunRole,
  IssueStatus,
  ProjectIssueSnapshotDTO,
  ReviewDecision,
} from '../types'

export interface IssueDetailModalProps {
  isOpen: boolean
  issueId: string | null
  projectId: string
  projectIssues?: ProjectIssueSnapshotDTO[]
  onClose: () => void
  onUpdated: () => void
  api?: ProjectsApi
  storageService?: StorageService
  hashFile?: HashFile
}

type TabKey = 'spec' | 'deps' | 'activities' | 'runs' | 'evidence'

async function calculateFileSha256(file: File, customHasher?: HashFile): Promise<string> {
  if (customHasher) {
    return customHasher(file)
  }
  if (typeof Worker !== 'undefined') {
    try {
      const workerHasher = createWorkerHasher()
      return await workerHasher(file)
    } catch {
      // fallback
    }
  }
  if (typeof crypto !== 'undefined' && crypto.subtle) {
    const buf = await file.arrayBuffer()
    const digest = await crypto.subtle.digest('SHA-256', buf)
    return Array.from(new Uint8Array(digest))
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('')
  }
  return '0'.repeat(64)
}

export function IssueDetailModal({
  isOpen,
  issueId,
  projectId,
  projectIssues = [],
  onClose,
  onUpdated,
  api = projectsApi,
  storageService = defaultStorageService,
  hashFile,
}: IssueDetailModalProps) {
  const queryClient = useQueryClient()
  const issueQueryKey = queryKeys.projects.issue(projectId, issueId ?? '')

  const {
    data: detail,
    isLoading,
    error: queryError,
    refetch,
  } = useQuery({
    queryKey: issueQueryKey,
    queryFn: () => api.getIssue(issueId!),
    enabled: isOpen && Boolean(issueId),
  })

  const [activeTab, setActiveTab] = useState<TabKey>('spec')
  const [actionError, setActionError] = useState<string | null>(null)
  const errorMessage = actionError || (queryError instanceof Error ? queryError.message : null)

  // Edit Spec state
  const [isEditingSpec, setIsEditingSpec] = useState(false)
  const [draftTitle, setDraftTitle] = useState('')
  const [draftDescription, setDraftDescription] = useState('')
  const [draftAssignee, setDraftAssignee] = useState('')
  const [draftReviewer, setDraftReviewer] = useState('')
  const [draftExpectedVersion, setDraftExpectedVersion] = useState('0')
  const [isSavingSpec, setIsSavingSpec] = useState(false)
  const [specConflict, setSpecConflict] = useState<{
    reason: string
    detail: string
  } | null>(null)

  const { agentOptions } = useCatalogAgentNames({
    preserveNames: [
      detail?.issue?.assigneeAgentName,
      detail?.issue?.reviewerAgentName,
      draftAssignee,
      draftReviewer,
    ],
    enabled: isOpen && Boolean(issueId),
  })

  // Cancel state
  const [isCanceling, setIsCanceling] = useState(false)
  const [cancelReason, setCancelReason] = useState('')

  // Block state
  const [isBlocking, setIsBlocking] = useState(false)
  const [blockReason, setBlockReason] = useState('')
  const [isSubmittingBlock, setIsSubmittingBlock] = useState(false)

  // Recover state
  const [isRecovering, setIsRecovering] = useState(false)
  const [recoverToBacklog, setRecoverToBacklog] = useState(false)
  const [recoverComment, setRecoverComment] = useState('')
  const [isSubmittingRecover, setIsSubmittingRecover] = useState(false)

  // Add dependency state
  const [selectedDepIssueId, setSelectedDepIssueId] = useState('')
  const [isAddingDep, setIsAddingDep] = useState(false)

  // Activity list & pagination state
  const [activities, setActivities] = useState<IssueActivityDTO[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [isLoadingMoreActivities, setIsLoadingMoreActivities] = useState(false)

  // Append Activity state
  const [activityBody, setActivityBody] = useState('')
  const [activityTargetRole, setActivityTargetRole] = useState<IssueRunRole | ''>('')
  const [isAppendingActivity, setIsAppendingActivity] = useState(false)

  // Human review state
  const [reviewDecision, setReviewDecision] = useState<ReviewDecision>('APPROVE')
  const [reviewReason, setReviewReason] = useState('')
  const [isSubmittingReview, setIsSubmittingReview] = useState(false)

  // Retry state
  const [isRetrying, setIsRetrying] = useState(false)

  // Evidence state
  const currentIssueIdRef = useRef(issueId)
  useEffect(() => {
    currentIssueIdRef.current = issueId
  }, [issueId])

  const [isUploadingEvidence, setIsUploadingEvidence] = useState(false)
  const [evidenceActionError, setEvidenceActionError] = useState<string | null>(null)
  const evidenceFileInputRef = useRef<HTMLInputElement>(null)

  const [downloadingBlobId, setDownloadingBlobId] = useState<string | null>(null)
  const [downloadError, setDownloadError] = useState<{ blobId: string; message: string } | null>(null)

  // Sync activities from detail query
  useEffect(() => {
    if (detail) {
      setActivities(detail.activities ?? [])
      setNextCursor(detail.nextActivityCursor ?? null)
    }
  }, [detail])

  // Reset transient modal states on issue or open change
  useEffect(() => {
    if (isOpen && issueId) {
      setIsEditingSpec(false)
      setSpecConflict(null)
      setIsCanceling(false)
      setCancelReason('')
      setIsBlocking(false)
      setBlockReason('')
      setIsRecovering(false)
      setRecoverToBacklog(false)
      setRecoverComment('')
      setActivityBody('')
      setActivityTargetRole('')
      setActiveTab('spec')
      setActionError(null)
      setIsUploadingEvidence(false)
      setEvidenceActionError(null)
      setDownloadingBlobId(null)
      setDownloadError(null)
    }
  }, [isOpen, issueId])

  // Synchronize drafts with detail when not actively editing spec
  const lastSyncedIssueIdRef = useRef<string | null>(null)

  useEffect(() => {
    if (!isOpen) {
      lastSyncedIssueIdRef.current = null
      return
    }
    if (!detail) {
      return
    }
    const issueChanged = lastSyncedIssueIdRef.current !== detail.issue.id
    if (!isEditingSpec || issueChanged) {
      setDraftTitle(detail.issue.title)
      setDraftDescription(detail.issue.description ?? '')
      setDraftAssignee(detail.issue.assigneeAgentName ?? '')
      setDraftReviewer(detail.issue.reviewerAgentName ?? '')
      setDraftExpectedVersion(detail.issue.version)
      lastSyncedIssueIdRef.current = detail.issue.id
    }
  }, [isOpen, detail, isEditingSpec])

  useEffect(() => {
    if (!isOpen) {
      return
    }
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !isEditingSpec) {
        e.preventDefault()
        onClose()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [isOpen, isEditingSpec, onClose])

  if (!isOpen || !issueId) {
    return null
  }

  const issue = detail?.issue

  // 1. Save Spec edit
  const handleSaveSpec = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) {
      return
    }
    setIsSavingSpec(true)
    setActionError(null)
    try {
      const updated = await api.updateIssue(issue.id, {
        expectedVersion: draftExpectedVersion,
        title: draftTitle.trim(),
        description: draftDescription.trim() || null,
        assigneeAgentName: draftAssignee.trim() || null,
        reviewerAgentName: draftReviewer.trim() || null,
      })
      queryClient.setQueryData<IssueDetailDTO>(issueQueryKey, (prev) =>
        prev ? { ...prev, issue: updated } : prev,
      )
      setIsEditingSpec(false)
      setSpecConflict(null)
      onUpdated()
    } catch (err) {
      if (isConflictError(err)) {
        const presentation = presentConflict(err)
        setSpecConflict({
          reason: presentation?.reason || 'PROJECT_VERSION_CONFLICT',
          detail: presentation?.detail || '版本已冲突，您的草稿已保留。',
        })
      } else {
        setActionError(err instanceof Error ? err.message : '保存修改失败')
      }
    } finally {
      setIsSavingSpec(false)
    }
  }

  const handleReloadLatestSpec = async () => {
    if (!issue) {
      return
    }
    try {
      const fresh = await api.getIssue(issue.id)
      queryClient.setQueryData<IssueDetailDTO>(issueQueryKey, fresh)
      setDraftExpectedVersion(fresh.issue.version)
      setSpecConflict(null)
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '重新同步版本号失败')
    }
  }

  // 2. Change Status (BACKLOG <-> TODO, or Reopen to TODO)
  const handleChangeStatus = async (targetStatus: IssueStatus) => {
    if (!issue) {
      return
    }
    setActionError(null)
    try {
      const updated = await api.changeIssueStatus(issue.id, {
        expectedVersion: issue.version,
        status: targetStatus,
      })
      queryClient.setQueryData<IssueDetailDTO>(issueQueryKey, (prev) =>
        prev ? { ...prev, issue: updated } : prev,
      )
      onUpdated()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '变更状态失败')
    }
  }

  // 3. Block Issue (POST /block with required reason)
  const handleBlockIssue = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) {
      return
    }
    const trimmedReason = blockReason.trim()
    if (!trimmedReason) {
      setActionError('阻塞理由不能为空')
      return
    }
    setIsSubmittingBlock(true)
    setActionError(null)
    try {
      const updated = await api.blockIssue(issue.id, {
        expectedVersion: issue.version,
        reason: trimmedReason,
      })
      queryClient.setQueryData<IssueDetailDTO>(issueQueryKey, (prev) =>
        prev ? { ...prev, issue: updated, blocked: true } : prev,
      )
      setIsBlocking(false)
      setBlockReason('')
      await refetch()
      onUpdated()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '阻塞 Issue 失败')
    } finally {
      setIsSubmittingBlock(false)
    }
  }

  // 4. Recover Issue (POST /recover with toBacklog and optional comment)
  const handleRecoverIssue = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) {
      return
    }
    setIsSubmittingRecover(true)
    setActionError(null)
    try {
      const updated = await api.recoverIssue(issue.id, {
        expectedVersion: issue.version,
        toBacklog: recoverToBacklog,
        comment: recoverComment.trim() || null,
      })
      queryClient.setQueryData<IssueDetailDTO>(issueQueryKey, (prev) =>
        prev ? { ...prev, issue: updated, blocked: false } : prev,
      )
      setIsRecovering(false)
      setRecoverComment('')
      await refetch()
      onUpdated()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '恢复 Issue 失败')
    } finally {
      setIsSubmittingRecover(false)
    }
  }

  // 5. Cancel Issue
  const handleCancelIssue = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) {
      return
    }
    setActionError(null)
    try {
      const updated = await api.cancelIssue(issue.id, {
        expectedVersion: issue.version,
        reason: cancelReason.trim() || null,
      })
      queryClient.setQueryData<IssueDetailDTO>(issueQueryKey, (prev) =>
        prev ? { ...prev, issue: updated } : prev,
      )
      setIsCanceling(false)
      setCancelReason('')
      onUpdated()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '取消 Issue 失败')
    }
  }

  // 6. Archive / Unarchive
  const handleArchive = async () => {
    if (!issue) {
      return
    }
    setActionError(null)
    try {
      const updated = await api.archiveIssue(issue.id, {
        expectedVersion: issue.version,
      })
      queryClient.setQueryData<IssueDetailDTO>(issueQueryKey, (prev) =>
        prev ? { ...prev, issue: updated } : prev,
      )
      onUpdated()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '归档失败')
    }
  }

  const handleUnarchive = async () => {
    if (!issue) {
      return
    }
    setActionError(null)
    try {
      const updated = await api.unarchiveIssue(issue.id, {
        expectedVersion: issue.version,
      })
      queryClient.setQueryData<IssueDetailDTO>(issueQueryKey, (prev) =>
        prev ? { ...prev, issue: updated } : prev,
      )
      onUpdated()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '取消归档失败')
    }
  }

  // 7. Add / Remove Dependency
  const handleAddDependency = async () => {
    if (!issue || !selectedDepIssueId) {
      return
    }
    setIsAddingDep(true)
    setActionError(null)
    try {
      await api.addIssueDependency(issue.id, {
        expectedVersion: issue.version,
        dependsOnIssueId: selectedDepIssueId,
      })
      setSelectedDepIssueId('')
      await refetch()
      onUpdated()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '添加依赖失败')
    } finally {
      setIsAddingDep(false)
    }
  }

  const handleRemoveDependency = async (dependsOnIssueId: string) => {
    if (!issue) {
      return
    }
    setActionError(null)
    try {
      await api.removeIssueDependency(issue.id, dependsOnIssueId, issue.version)
      await refetch()
      onUpdated()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '移除依赖失败')
    }
  }

  // 8. Append Activity
  const handleAppendActivity = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue || !activityBody.trim()) {
      return
    }
    setIsAppendingActivity(true)
    setActionError(null)
    try {
      const appended = await api.appendIssueActivity(issue.id, {
        body: activityBody.trim(),
        targetRole: activityTargetRole || null,
        idempotencyKey: createUuid(),
      })
      setActivities((prev) => [...prev, appended])
      setActivityBody('')
      setActivityTargetRole('')
      await refetch()
      onUpdated()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '追加活动失败')
    } finally {
      setIsAppendingActivity(false)
    }
  }

  // 9. Load more activities
  const handleLoadMoreActivities = async () => {
    if (!issue || !nextCursor) {
      return
    }
    setIsLoadingMoreActivities(true)
    setActionError(null)
    try {
      const nextPage = await api.listActivities(issue.id, nextCursor, 50)
      setActivities((prev) => [...prev, ...nextPage])
      // Calculate next cursor: if page size reached limit, last sequence is next cursor
      if (nextPage.length >= 50) {
        setNextCursor(nextPage[nextPage.length - 1].sequence)
      } else {
        setNextCursor(null)
      }
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '加载更多活动失败')
    } finally {
      setIsLoadingMoreActivities(false)
    }
  }

  // 10. Human Review
  const handleReview = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) {
      return
    }
    setIsSubmittingReview(true)
    setActionError(null)
    try {
      await api.reviewIssue(issue.id, {
        decision: reviewDecision,
        reason: reviewReason.trim() || null,
        idempotencyKey: createUuid(),
      })
      setReviewReason('')
      await refetch()
      onUpdated()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '提交审核失败')
    } finally {
      setIsSubmittingReview(false)
    }
  }

  // 11. Retry Run
  const handleRetryRun = async () => {
    if (!issue) {
      return
    }
    setIsRetrying(true)
    setActionError(null)
    try {
      await api.retryIssue(issue.id, {
        idempotencyKey: createUuid(),
      })
      await refetch()
      onUpdated()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '重试 Run 失败')
    } finally {
      setIsRetrying(false)
    }
  }

  // 12. Evidence Upload & Download
  const handleEvidenceUpload = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    if (!file || !issue) {
      return
    }
    event.target.value = ''
    const targetIssueId = issue.id

    const sizeError = validateUploadFile(file)
    if (sizeError) {
      setEvidenceActionError(sizeError)
      return
    }

    setIsUploadingEvidence(true)
    setEvidenceActionError(null)
    let reservationId: string | null = null

    try {
      const sha256 = await calculateFileSha256(file, hashFile)
      if (currentIssueIdRef.current !== targetIssueId) {
        return
      }

      const reservation = await storageService.reserveUpload({
        filename: file.name,
        mediaType: file.type || 'application/octet-stream',
        sizeBytes: file.size,
        sha256,
      })
      reservationId = reservation.id
      if (currentIssueIdRef.current !== targetIssueId) {
        void storageService.deleteUpload(reservation.id).catch(() => undefined)
        return
      }

      if (reservation.state === 'PENDING') {
        await storageService.uploadFile(reservation.presignedPut, file)
        if (currentIssueIdRef.current !== targetIssueId) {
          void storageService.deleteUpload(reservation.id).catch(() => undefined)
          return
        }
      }

      const completed = await storageService.completeUpload(reservation.id)
      if (currentIssueIdRef.current !== targetIssueId) {
        void storageService.deleteUpload(completed.id).catch(() => undefined)
        return
      }

      await api.addIssueEvidence(targetIssueId, { uploadId: completed.id })
      if (currentIssueIdRef.current !== targetIssueId) {
        return
      }

      await refetch()
      onUpdated()
    } catch (err) {
      if (currentIssueIdRef.current !== targetIssueId) {
        return
      }
      if (reservationId) {
        void storageService.deleteUpload(reservationId).catch(() => undefined)
      }
      setEvidenceActionError(err instanceof Error ? err.message : '上传证据失败')
    } finally {
      if (currentIssueIdRef.current === targetIssueId) {
        setIsUploadingEvidence(false)
      }
    }
  }

  const handleDownloadEvidence = async (ev: IssueEvidenceDTO) => {
    if (!issue || !ev.blobId || downloadingBlobId === ev.blobId) {
      return
    }
    const targetIssueId = issue.id
    setDownloadingBlobId(ev.blobId)
    setDownloadError(null)
    try {
      const result = await storageService.getBlobDownloadUrl(ev.blobId)
      if (currentIssueIdRef.current !== targetIssueId) {
        return
      }
      if (result?.url) {
        const a = document.createElement('a')
        a.href = result.url
        a.target = '_blank'
        a.rel = 'noreferrer noopener'
        if (ev.name) {
          a.download = ev.name
        }
        document.body.appendChild(a)
        a.click()
        document.body.removeChild(a)
      } else {
        throw new Error('未获取到有效的下载地址')
      }
    } catch (err) {
      if (currentIssueIdRef.current !== targetIssueId) {
        return
      }
      setDownloadError({
        blobId: ev.blobId,
        message: err instanceof Error ? err.message : '获取下载链接失败',
      })
    } finally {
      if (currentIssueIdRef.current === targetIssueId) {
        setDownloadingBlobId(null)
      }
    }
  }

  const isBlocked = Boolean(detail?.blocked) || issue?.status === 'BLOCKED'
  const isTerminal = issue?.status === 'DONE' || issue?.status === 'CANCELED'
  const canHumanReview =
    issue?.status === 'IN_REVIEW' && issue?.reviewerAgentName === null
  const currentRun = detail?.currentRun
  const isWaitingHuman = currentRun?.status === 'WAITING_HUMAN'
  const isFailedOrUnknown =
    currentRun?.status === 'FAILED' ||
    currentRun?.status === 'UNKNOWN' ||
    detail?.latestRun?.status === 'FAILED' ||
    detail?.latestRun?.status === 'UNKNOWN'

  // Dependency candidates
  const existingDepIds = new Set(detail?.dependencies.map((d) => d.dependsOnIssueId) ?? [])
  const depCandidates = projectIssues.filter(
    (item) =>
      item.issue.id !== issue?.id &&
      !existingDepIds.has(item.issue.id) &&
      !item.issue.archivedAt,
  )

  return (
    <div
      className="modal-backdrop"
      role="presentation"
      onClick={(e) => {
        if (e.target === e.currentTarget && !isEditingSpec) {
          onClose()
        }
      }}
    >
      <div
        className="modal-card resource-modal-card"
        style={{ maxWidth: '820px', width: '92%' }}
        role="dialog"
        aria-modal="true"
        aria-label={`Issue #${issue?.number || ''} 详情`}
      >
        <div className="modal-header">
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
            <h3 style={{ margin: 0 }}>
              Issue #{issue?.number}: {issue?.title}
            </h3>
            {issue && (
              <span className="badge badge-status" style={{ fontWeight: 600 }}>
                {issue.status}
              </span>
            )}
            {isBlocked && (
              <span className="badge badge-blocked">
                <AlertCircle size={12} aria-hidden="true" />
                BLOCKED
              </span>
            )}
            {issue?.archivedAt && (
              <span className="badge badge-archived">已归档</span>
            )}
          </div>
          <button
            type="button"
            className="modal-close-button"
            onClick={onClose}
            aria-label="关闭"
          >
            <X size={16} aria-hidden="true" />
          </button>
        </div>

        <nav className="modal-tabs" aria-label="Issue 详情标签页">
          <button
            type="button"
            className={`modal-tab-button ${activeTab === 'spec' ? 'is-active' : ''}`}
            onClick={() => setActiveTab('spec')}
          >
            规格与状态
          </button>
          <button
            type="button"
            className={`modal-tab-button ${activeTab === 'deps' ? 'is-active' : ''}`}
            onClick={() => setActiveTab('deps')}
          >
            依赖关系 ({detail?.dependencies.length ?? 0})
          </button>
          <button
            type="button"
            className={`modal-tab-button ${activeTab === 'activities' ? 'is-active' : ''}`}
            onClick={() => setActiveTab('activities')}
          >
            活动流 ({activities.length})
          </button>
          <button
            type="button"
            className={`modal-tab-button ${activeTab === 'runs' ? 'is-active' : ''}`}
            onClick={() => setActiveTab('runs')}
          >
            执行与审核 ({detail?.runs.length ?? 0})
          </button>
          <button
            type="button"
            className={`modal-tab-button ${activeTab === 'evidence' ? 'is-active' : ''}`}
            onClick={() => setActiveTab('evidence')}
          >
            已发布证据 ({detail?.evidence?.length ?? 0})
          </button>
        </nav>

        <div className="modal-body" style={{ maxHeight: '68vh', overflowY: 'auto' }}>
          {errorMessage && (
            <div className="form-error-banner" role="alert" style={{ marginBottom: '14px' }}>
              <AlertTriangle size={14} aria-hidden="true" />
              <span>{errorMessage}</span>
            </div>
          )}

          {isLoading && !detail && (
            <div style={{ textAlign: 'center', padding: '32px', color: 'var(--fg-muted)' }}>
              加载 Issue 详情中...
            </div>
          )}

          {detail && issue && activeTab === 'spec' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
              {isBlocked && (
                <div
                  style={{
                    background: 'rgba(239, 68, 68, 0.1)',
                    border: '1px solid rgba(239, 68, 68, 0.3)',
                    borderRadius: 'var(--radius-sm)',
                    padding: '12px 14px',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    gap: '12px',
                    flexWrap: 'wrap',
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: '#f87171', fontWeight: 600 }}>
                    <ShieldAlert size={16} aria-hidden="true" />
                    <span>Issue 当前处于阻塞状态 (BLOCKED)，自动推进已停止</span>
                  </div>
                  {!isRecovering && (
                    <button
                      type="button"
                      className="btn-primary"
                      style={{ fontSize: '0.8125rem', height: '28px', padding: '0 12px' }}
                      onClick={() => setIsRecovering(true)}
                    >
                      <RotateCcw size={12} style={{ marginRight: '4px' }} aria-hidden="true" />
                      恢复 Issue
                    </button>
                  )}
                </div>
              )}

              {isWaitingHuman && (
                <div
                  style={{
                    background: 'rgba(245, 158, 11, 0.1)',
                    border: '1px solid rgba(245, 158, 11, 0.3)',
                    borderRadius: 'var(--radius-sm)',
                    padding: '12px 14px',
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: '#fbbf24', fontWeight: 600 }}>
                    <Clock size={16} aria-hidden="true" />
                    <span>Run 当前正等待人类输入 (WAITING_HUMAN)</span>
                  </div>
                  <div style={{ fontSize: '0.875rem', marginTop: '4px', color: 'var(--fg)' }}>
                    原因：{currentRun?.waitingReason || '无具体说明'}
                  </div>
                  <div style={{ marginTop: '8px' }}>
                    <button
                      type="button"
                      className="btn-primary"
                      style={{ fontSize: '0.8125rem', height: '28px', padding: '0 10px' }}
                      onClick={() => setActiveTab('activities')}
                    >
                      前往“活动流”追加答复
                    </button>
                  </div>
                </div>
              )}

              {isEditingSpec ? (
                <form onSubmit={handleSaveSpec}>
                  {specConflict && (
                    <div className="cas-conflict-banner" role="alert">
                      <div className="cas-conflict-title">
                        <AlertTriangle size={16} aria-hidden="true" />
                        <span>版本冲突 ({specConflict.reason})</span>
                      </div>
                      <div className="cas-conflict-text">
                        服务端 Issue 内容已更新，您的草稿已保留在下方表单。
                      </div>
                      <div className="cas-conflict-actions">
                        <button
                          type="button"
                          className="btn-primary"
                          onClick={handleReloadLatestSpec}
                        >
                          <RefreshCw size={14} aria-hidden="true" />
                          同步最新版本号
                        </button>
                      </div>
                    </div>
                  )}

                  <div className="form-group">
                    <label htmlFor="edit-spec-title">标题</label>
                    <input
                      id="edit-spec-title"
                      type="text"
                      value={draftTitle}
                      onChange={(e) => setDraftTitle(e.target.value)}
                      required
                    />
                  </div>

                  <div className="form-group">
                    <label htmlFor="edit-spec-desc">规格要求 (Spec)</label>
                    <textarea
                      id="edit-spec-desc"
                      value={draftDescription}
                      onChange={(e) => setDraftDescription(e.target.value)}
                      rows={5}
                    />
                  </div>

                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                    <div className="form-group">
                      <label htmlFor="edit-spec-assignee">执行 Agent (EXECUTOR)</label>
                      <select
                        id="edit-spec-assignee"
                        value={draftAssignee}
                        onChange={(e) => setDraftAssignee(e.target.value)}
                        aria-label="执行 Agent (EXECUTOR)"
                      >
                        <option value="">未指定</option>
                        {agentOptions.map((name) => (
                          <option key={name} value={name}>
                            {name}
                          </option>
                        ))}
                      </select>
                    </div>
                    <div className="form-group">
                      <label htmlFor="edit-spec-reviewer">审查 Agent (REVIEWER)</label>
                      <select
                        id="edit-spec-reviewer"
                        value={draftReviewer}
                        onChange={(e) => setDraftReviewer(e.target.value)}
                        aria-label="审查 Agent (REVIEWER)"
                      >
                        <option value="">人工审核</option>
                        {agentOptions.map((name) => (
                          <option key={name} value={name}>
                            {name}
                          </option>
                        ))}
                      </select>
                    </div>
                  </div>

                  <div style={{ display: 'flex', gap: '8px', marginTop: '12px' }}>
                    <button
                      type="submit"
                      className="btn-primary"
                      disabled={isSavingSpec || !draftTitle.trim()}
                    >
                      {isSavingSpec ? '保存中...' : '保存修改'}
                    </button>
                    <button
                      type="button"
                      className="ghost-btn"
                      onClick={() => setIsEditingSpec(false)}
                      disabled={isSavingSpec}
                    >
                      取消
                    </button>
                  </div>
                </form>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                  <div>
                    <h4 style={{ margin: '0 0 6px 0', fontSize: '0.875rem', color: 'var(--fg-muted)' }}>
                      规格与任务描述
                    </h4>
                    <div
                      style={{
                        background: 'var(--bg-subtle, rgba(255,255,255,0.03))',
                        border: '1px solid var(--border)',
                        borderRadius: 'var(--radius-sm)',
                        padding: '12px 14px',
                        fontSize: '0.875rem',
                        lineHeight: 1.5,
                        whiteSpace: 'pre-wrap',
                      }}
                    >
                      {issue.description || '（无规格描述）'}
                    </div>
                  </div>

                  <div
                    style={{
                      display: 'grid',
                      gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))',
                      gap: '12px',
                      background: 'var(--bg-subtle, rgba(255,255,255,0.02))',
                      border: '1px solid var(--border)',
                      borderRadius: 'var(--radius-sm)',
                      padding: '12px',
                      fontSize: '0.8125rem',
                    }}
                  >
                    <div>
                      <span style={{ color: 'var(--fg-muted)' }}>执行 Agent (EXECUTOR): </span>
                      <strong style={{ color: 'var(--fg)' }}>
                        {issue.assigneeAgentName || '未指定'}
                      </strong>
                    </div>
                    <div>
                      <span style={{ color: 'var(--fg-muted)' }}>审查 Agent (REVIEWER): </span>
                      <strong style={{ color: 'var(--fg)' }}>
                        {issue.reviewerAgentName || '人工 Review'}
                      </strong>
                    </div>
                    <div>
                      <span style={{ color: 'var(--fg-muted)' }}>Version: </span>
                      <code>{issue.version}</code>
                    </div>
                    <div>
                      <span style={{ color: 'var(--fg-muted)' }}>状态: </span>
                      <code>{issue.status}</code>
                    </div>
                  </div>

                  {/* Agent Sessions & Work Branches */}
                  {detail.sessions && detail.sessions.length > 0 && (
                    <div>
                      <h4 style={{ margin: '8px 0 6px 0', fontSize: '0.875rem', color: 'var(--fg-muted)' }}>
                        Agent 稳定归属与工作分支 (Sessions)
                      </h4>
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                        {detail.sessions.map((sess) => (
                          <div
                            key={sess.id}
                            style={{
                              padding: '10px 12px',
                              background: 'var(--bg-subtle, rgba(255,255,255,0.02))',
                              border: '1px solid var(--border)',
                              borderRadius: 'var(--radius-sm)',
                              fontSize: '0.8125rem',
                            }}
                          >
                            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '8px' }}>
                              <span style={{ fontWeight: 600 }}>
                                {sess.role === 'EXECUTOR' ? '执行' : '审查'}角色: {sess.agentName}
                              </span>
                              <span className="badge badge-status" style={{ fontSize: '0.75rem' }}>
                                Branch: {sess.branchId.slice(0, 8)}...
                              </span>
                            </div>
                            <div style={{ color: 'var(--fg-muted)', fontSize: '0.75rem', marginTop: '4px' }}>
                              Session: <code>{sess.sessionId}</code>
                            </div>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* Recover Form */}
                  {isRecovering && (
                    <form
                      onSubmit={handleRecoverIssue}
                      style={{
                        background: 'rgba(59, 130, 246, 0.08)',
                        border: '1px solid rgba(59, 130, 246, 0.3)',
                        borderRadius: 'var(--radius-sm)',
                        padding: '14px',
                        display: 'flex',
                        flexDirection: 'column',
                        gap: '10px',
                      }}
                    >
                      <h4 style={{ margin: 0, color: '#60a5fa' }}>人工恢复 Issue</h4>
                      <p style={{ margin: 0, fontSize: '0.8125rem', color: 'var(--fg-muted)' }}>
                        恢复将解除阻塞并开启新的打回计数区间，旧的未处理提交不重新获得审查资格。
                      </p>

                      <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
                        <label style={{ display: 'flex', alignItems: 'center', gap: '6px', cursor: 'pointer' }}>
                          <input
                            type="radio"
                            name="recoverTarget"
                            checked={!recoverToBacklog}
                            onChange={() => setRecoverToBacklog(false)}
                          />
                          <span>恢复至 TODO（要求不变，直接就绪自动推进）</span>
                        </label>
                        <label style={{ display: 'flex', alignItems: 'center', gap: '6px', cursor: 'pointer' }}>
                          <input
                            type="radio"
                            name="recoverTarget"
                            checked={recoverToBacklog}
                            onChange={() => setRecoverToBacklog(true)}
                          />
                          <span>恢复至 BACKLOG（放回需求池，需先修改要求再重新开始）</span>
                        </label>
                      </div>

                      <div className="form-group" style={{ margin: 0 }}>
                        <label htmlFor="recover-comment">恢复说明 (可选)</label>
                        <textarea
                          id="recover-comment"
                          placeholder="说明恢复原因或指导意见..."
                          value={recoverComment}
                          onChange={(e) => setRecoverComment(e.target.value)}
                          rows={2}
                        />
                      </div>

                      <div style={{ display: 'flex', gap: '8px' }}>
                        <button
                          type="submit"
                          className="btn-primary"
                          disabled={isSubmittingRecover}
                        >
                          {isSubmittingRecover ? '恢复中...' : '确认恢复'}
                        </button>
                        <button
                          type="button"
                          className="ghost-btn"
                          onClick={() => setIsRecovering(false)}
                          disabled={isSubmittingRecover}
                        >
                          取消
                        </button>
                      </div>
                    </form>
                  )}

                  {/* Block Form */}
                  {isBlocking && (
                    <form
                      onSubmit={handleBlockIssue}
                      style={{
                        background: 'rgba(239, 68, 68, 0.08)',
                        border: '1px solid rgba(239, 68, 68, 0.3)',
                        borderRadius: 'var(--radius-sm)',
                        padding: '14px',
                        display: 'flex',
                        flexDirection: 'column',
                        gap: '10px',
                      }}
                    >
                      <h4 style={{ margin: 0, color: '#f87171' }}>显式阻塞 Issue</h4>
                      <p style={{ margin: 0, fontSize: '0.8125rem', color: 'var(--fg-muted)' }}>
                        阻塞会停止自动推进并将 Issue 转入 BLOCKED 状态，必须填写阻塞原因。
                      </p>

                      <div className="form-group" style={{ margin: 0 }}>
                        <label htmlFor="block-reason">
                          阻塞原因 <span style={{ color: 'var(--danger)' }}>*</span>
                        </label>
                        <textarea
                          id="block-reason"
                          placeholder="详细说明业务障碍或待决问题..."
                          value={blockReason}
                          onChange={(e) => setBlockReason(e.target.value)}
                          rows={3}
                          required
                        />
                      </div>

                      <div style={{ display: 'flex', gap: '8px' }}>
                        <button
                          type="submit"
                          className="btn-primary danger"
                          disabled={isSubmittingBlock || !blockReason.trim()}
                        >
                          {isSubmittingBlock ? '阻塞中...' : '确认阻塞'}
                        </button>
                        <button
                          type="button"
                          className="ghost-btn"
                          onClick={() => setIsBlocking(false)}
                          disabled={isSubmittingBlock}
                        >
                          取消
                        </button>
                      </div>
                    </form>
                  )}

                  {/* Cancel Form */}
                  {isCanceling && (
                    <form
                      onSubmit={handleCancelIssue}
                      style={{
                        background: 'rgba(239, 68, 68, 0.05)',
                        border: '1px solid rgba(239, 68, 68, 0.2)',
                        borderRadius: 'var(--radius-sm)',
                        padding: '12px',
                        display: 'flex',
                        flexDirection: 'column',
                        gap: '8px',
                      }}
                    >
                      <h4 style={{ margin: 0, color: '#f87171' }}>确认取消 Issue #{issue.number}</h4>
                      <input
                        type="text"
                        placeholder="可选：取消原因"
                        value={cancelReason}
                        onChange={(e) => setCancelReason(e.target.value)}
                        aria-label="取消原因"
                      />
                      <div style={{ display: 'flex', gap: '8px' }}>
                        <button type="submit" className="btn-primary danger">
                          确认取消
                        </button>
                        <button
                          type="button"
                          className="ghost-btn"
                          onClick={() => setIsCanceling(false)}
                        >
                          返回
                        </button>
                      </div>
                    </form>
                  )}

                  {/* Actions Toolbar */}
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: '8px',
                      flexWrap: 'wrap',
                      marginTop: '8px',
                      borderTop: '1px solid var(--border)',
                      paddingTop: '12px',
                    }}
                  >
                    <button
                      type="button"
                      className="ghost-btn"
                      onClick={() => setIsEditingSpec(true)}
                    >
                      <Pencil size={14} aria-hidden="true" />
                      编辑规格
                    </button>

                    {issue.status === 'BACKLOG' && (
                      <button
                        type="button"
                        className="btn-primary"
                        onClick={() => handleChangeStatus('TODO')}
                      >
                        移至 TODO
                      </button>
                    )}

                    {issue.status === 'TODO' && (
                      <button
                        type="button"
                        className="ghost-btn"
                        onClick={() => handleChangeStatus('BACKLOG')}
                      >
                        放回 BACKLOG
                      </button>
                    )}

                    {isTerminal && (
                      <button
                        type="button"
                        className="btn-primary"
                        onClick={() => handleChangeStatus('TODO')}
                      >
                        <RotateCcw size={14} aria-hidden="true" />
                        重新打开至 TODO
                      </button>
                    )}

                    {isBlocked && !isRecovering && (
                      <button
                        type="button"
                        className="btn-primary"
                        onClick={() => setIsRecovering(true)}
                      >
                        <RotateCcw size={14} aria-hidden="true" />
                        恢复 Issue
                      </button>
                    )}

                    {!isTerminal && !isBlocked && !isBlocking && (
                      <button
                        type="button"
                        className="ghost-btn danger"
                        onClick={() => setIsBlocking(true)}
                      >
                        <AlertCircle size={14} aria-hidden="true" />
                        阻塞 Issue
                      </button>
                    )}

                    {!isTerminal && !isCanceling && (
                      <button
                        type="button"
                        className="ghost-btn danger"
                        onClick={() => setIsCanceling(true)}
                      >
                        <Ban size={14} aria-hidden="true" />
                        取消 Issue
                      </button>
                    )}

                    {isTerminal && !issue.archivedAt && (
                      <button
                        type="button"
                        className="ghost-btn"
                        onClick={handleArchive}
                      >
                        <Archive size={14} aria-hidden="true" />
                        归档
                      </button>
                    )}

                    {isTerminal && issue.archivedAt && (
                      <button
                        type="button"
                        className="ghost-btn"
                        onClick={handleUnarchive}
                      >
                        <RotateCcw size={14} aria-hidden="true" />
                        取消归档
                      </button>
                    )}
                  </div>
                </div>
              )}
            </div>
          )}

          {detail && activeTab === 'deps' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
              <div>
                <h4 style={{ margin: '0 0 8px 0', fontSize: '0.875rem' }}>依赖的 Issue</h4>
                {detail.dependencies.length === 0 ? (
                  <p style={{ fontSize: '0.875rem', color: 'var(--fg-muted)', margin: 0 }}>
                    此 Issue 当前没有依赖项。
                  </p>
                ) : (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                    {detail.dependencies.map((dep) => {
                      const match = projectIssues.find((i) => i.issue.id === dep.dependsOnIssueId)
                      return (
                        <div
                          key={dep.dependsOnIssueId}
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'space-between',
                            background: 'var(--bg-subtle, rgba(255,255,255,0.03))',
                            border: '1px solid var(--border)',
                            borderRadius: 'var(--radius-sm)',
                            padding: '8px 12px',
                          }}
                        >
                          <div>
                            <strong>
                              {match ? `#${match.issue.number} ${match.issue.title}` : dep.dependsOnIssueId}
                            </strong>
                            {match && (
                              <span
                                className="badge badge-status"
                                style={{ marginLeft: '8px', fontSize: '0.75rem' }}
                              >
                                {match.issue.status}
                              </span>
                            )}
                          </div>
                          <button
                            type="button"
                            className="quick-action-btn"
                            style={{ color: 'var(--danger)' }}
                            onClick={() => handleRemoveDependency(dep.dependsOnIssueId)}
                            title="移除此依赖"
                          >
                            <Trash2 size={12} aria-hidden="true" />
                            移除
                          </button>
                        </div>
                      )
                    })}
                  </div>
                )}
              </div>

              <div
                style={{
                  borderTop: '1px solid var(--border)',
                  paddingTop: '14px',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '8px',
                  flexWrap: 'wrap',
                }}
              >
                <select
                  value={selectedDepIssueId}
                  onChange={(e) => setSelectedDepIssueId(e.target.value)}
                  style={{ flex: 1, minWidth: '220px' }}
                  aria-label="选择要依赖的 Issue"
                >
                  <option value="">选择要添加的依赖 Issue...</option>
                  {depCandidates.map((c) => (
                    <option key={c.issue.id} value={c.issue.id}>
                      #{c.issue.number} {c.issue.title} ({c.issue.status})
                    </option>
                  ))}
                </select>
                <button
                  type="button"
                  className="btn-primary"
                  onClick={handleAddDependency}
                  disabled={isAddingDep || !selectedDepIssueId}
                  style={{ display: 'flex', alignItems: 'center', gap: '4px' }}
                >
                  <Plus size={14} aria-hidden="true" />
                  <span>{isAddingDep ? '添加中...' : '添加依赖'}</span>
                </button>
              </div>
            </div>
          )}

          {detail && activeTab === 'activities' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
              <div>
                <h4 style={{ margin: '0 0 8px 0', fontSize: '0.875rem' }}>唯一有序事实流 (Activities)</h4>
                {activities.length === 0 ? (
                  <p style={{ fontSize: '0.875rem', color: 'var(--fg-muted)', margin: 0 }}>
                    暂无活动记录。
                  </p>
                ) : (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
                    {activities.map((act) => (
                      <div
                        key={act.sequence}
                        style={{
                          background: 'var(--bg-subtle, rgba(255,255,255,0.03))',
                          border: '1px solid var(--border)',
                          borderRadius: 'var(--radius-sm)',
                          padding: '10px 12px',
                        }}
                      >
                        <div
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'space-between',
                            fontSize: '0.75rem',
                            color: 'var(--fg-muted)',
                            marginBottom: '4px',
                            gap: '8px',
                            flexWrap: 'wrap',
                          }}
                        >
                          <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                            <span style={{ fontWeight: 600 }}>#{act.sequence}</span>
                            <span className="badge badge-status" style={{ fontSize: '0.7rem' }}>
                              {act.kind}
                            </span>
                            <span>
                              {act.actorType === 'AGENT'
                                ? `Agent: ${act.actorAgentName}`
                                : act.actorType}
                            </span>
                            {act.targetRole && (
                              <span className="badge badge-waiting" style={{ fontSize: '0.7rem' }}>
                                @{act.targetRole}
                              </span>
                            )}
                            {act.decision && (
                              <span className="badge badge-run" style={{ fontSize: '0.7rem' }}>
                                {act.decision}
                              </span>
                            )}
                          </div>
                          <span>{act.createdAt}</span>
                        </div>
                        <div style={{ fontSize: '0.875rem', whiteSpace: 'pre-wrap', lineHeight: 1.5 }}>
                          {act.body}
                        </div>
                      </div>
                    ))}
                  </div>
                )}

                {nextCursor && (
                  <div style={{ marginTop: '12px', textAlign: 'center' }}>
                    <button
                      type="button"
                      className="ghost-btn"
                      onClick={handleLoadMoreActivities}
                      disabled={isLoadingMoreActivities}
                    >
                      {isLoadingMoreActivities ? '加载中...' : '加载更早的活动记录'}
                    </button>
                  </div>
                )}
              </div>

              {/* Append Activity Form */}
              <form
                onSubmit={handleAppendActivity}
                style={{
                  borderTop: '1px solid var(--border)',
                  paddingTop: '14px',
                  display: 'flex',
                  flexDirection: 'column',
                  gap: '10px',
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '8px', flexWrap: 'wrap' }}>
                  <label htmlFor="append-activity-body" style={{ fontWeight: 600, fontSize: '0.875rem' }}>
                    追加活动记录 / 定向指示
                  </label>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                    <span style={{ fontSize: '0.75rem', color: 'var(--fg-muted)' }}>目标角色:</span>
                    <select
                      value={activityTargetRole}
                      onChange={(e) => setActivityTargetRole(e.target.value as IssueRunRole | '')}
                      style={{ fontSize: '0.75rem', padding: '2px 8px' }}
                      aria-label="目标职责"
                    >
                      <option value="">无定向 (普通评论 COMMENT)</option>
                      <option value="EXECUTOR">@EXECUTOR (定向指示)</option>
                      <option value="REVIEWER">@REVIEWER (定向指示)</option>
                    </select>
                  </div>
                </div>

                <textarea
                  id="append-activity-body"
                  aria-label="活动内容"
                  value={activityBody}
                  onChange={(e) => setActivityBody(e.target.value)}
                  placeholder={
                    activityTargetRole
                      ? `输入对 ${activityTargetRole} 的定向补充说明或指令...`
                      : '输入评论留言...'
                  }
                  rows={3}
                  required
                />

                <div style={{ alignSelf: 'flex-end' }}>
                  <button
                    type="submit"
                    className="btn-primary"
                    disabled={isAppendingActivity || !activityBody.trim()}
                  >
                    {isAppendingActivity ? '提交中...' : '追加活动'}
                  </button>
                </div>
              </form>
            </div>
          )}

          {detail && activeTab === 'runs' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
              {canHumanReview && (
                <form
                  onSubmit={handleReview}
                  style={{
                    background: 'rgba(59, 130, 246, 0.06)',
                    border: '1px solid rgba(59, 130, 246, 0.3)',
                    borderRadius: 'var(--radius-sm)',
                    padding: '14px',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: '10px',
                  }}
                >
                  <h4 style={{ margin: 0, color: '#60a5fa' }}>人工审核 (Human Review)</h4>
                  <p style={{ margin: 0, fontSize: '0.8125rem', color: 'var(--fg-muted)' }}>
                    此 Issue 处于 IN_REVIEW 状态且未配置 Reviewer Agent，请人工核查提交成果。
                  </p>

                  <div style={{ display: 'flex', gap: '16px' }}>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '6px', cursor: 'pointer' }}>
                      <input
                        type="radio"
                        name="reviewDecision"
                        value="APPROVE"
                        checked={reviewDecision === 'APPROVE'}
                        onChange={() => setReviewDecision('APPROVE')}
                      />
                      <span>批准并完成 (APPROVE → DONE)</span>
                    </label>
                    <label style={{ display: 'flex', alignItems: 'center', gap: '6px', cursor: 'pointer' }}>
                      <input
                        type="radio"
                        name="reviewDecision"
                        value="REQUEST_CHANGES"
                        checked={reviewDecision === 'REQUEST_CHANGES'}
                        onChange={() => setReviewDecision('REQUEST_CHANGES')}
                      />
                      <span>打回修改 (REQUEST_CHANGES → TODO)</span>
                    </label>
                  </div>

                  <div className="form-group" style={{ margin: 0 }}>
                    <label htmlFor="review-reason">审核理由 / 反馈说明</label>
                    <textarea
                      id="review-reason"
                      placeholder="说明审核意见或修改要求..."
                      value={reviewReason}
                      onChange={(e) => setReviewReason(e.target.value)}
                      rows={3}
                    />
                  </div>

                  <button
                    type="submit"
                    className="btn-primary"
                    disabled={isSubmittingReview}
                    style={{ alignSelf: 'flex-start' }}
                  >
                    {isSubmittingReview ? '提交中...' : '提交审核决定'}
                  </button>
                </form>
              )}

              {isFailedOrUnknown && (
                <div
                  style={{
                    background: 'rgba(239, 68, 68, 0.08)',
                    border: '1px solid rgba(239, 68, 68, 0.3)',
                    borderRadius: 'var(--radius-sm)',
                    padding: '12px 14px',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    gap: '12px',
                    flexWrap: 'wrap',
                  }}
                >
                  <div style={{ color: '#f87171', fontSize: '0.875rem' }}>
                    Run 执行失败或处于未知状态，可执行显式重试。
                  </div>
                  <button
                    type="button"
                    className="btn-primary"
                    onClick={handleRetryRun}
                    disabled={isRetrying}
                  >
                    <RefreshCw size={14} className={isRetrying ? 'animate-spin' : ''} aria-hidden="true" />
                    <span>{isRetrying ? '重试中...' : '重试 Run'}</span>
                  </button>
                </div>
              )}

              <div>
                <h4 style={{ margin: '0 0 8px 0', fontSize: '0.875rem' }}>历史 Run 记录</h4>
                {detail.runs.length === 0 ? (
                  <p style={{ fontSize: '0.875rem', color: 'var(--fg-muted)', margin: 0 }}>
                    暂无 Run 记录。
                  </p>
                ) : (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
                    {detail.runs.map((run) => (
                      <div
                        key={run.id}
                        style={{
                          background: 'var(--bg-subtle, rgba(255,255,255,0.02))',
                          border: '1px solid var(--border)',
                          borderRadius: 'var(--radius-sm)',
                          padding: '12px',
                          display: 'flex',
                          flexDirection: 'column',
                          gap: '6px',
                        }}
                      >
                        <div
                          style={{
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'space-between',
                            flexWrap: 'wrap',
                            gap: '8px',
                          }}
                        >
                          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                            <strong style={{ fontSize: '0.875rem' }}>
                              Run #{run.ordinal} ({run.role === 'REVIEWER' ? '审核' : '执行'})
                            </strong>
                            <span className="badge badge-status">{run.status}</span>
                            {run.outcome && (
                              <span className="badge badge-run">{run.outcome}</span>
                            )}
                          </div>
                          <span style={{ fontSize: '0.75rem', color: 'var(--fg-muted)' }}>
                            {run.createdAt}
                          </span>
                        </div>

                        <div style={{ fontSize: '0.8125rem', color: 'var(--fg-muted)' }}>
                          Agent: <strong style={{ color: 'var(--fg)' }}>{run.agentName || '人工'}</strong>
                          {run.continuationCount > 0 && ` · 轮次: ${run.continuationCount}/${run.maxContinuations}`}
                          {run.observedActivitySequence && ` · 已消费活动: #${run.observedActivitySequence}`}
                        </div>

                        {run.waitingReason && (
                          <div style={{ fontSize: '0.8125rem', color: '#fbbf24' }}>
                            等待原因: {run.waitingReason}
                          </div>
                        )}

                        {run.result && (
                          <div
                            style={{
                              fontSize: '0.8125rem',
                              background: 'rgba(0,0,0,0.15)',
                              padding: '8px',
                              borderRadius: 'var(--radius-sm)',
                              marginTop: '4px',
                              whiteSpace: 'pre-wrap',
                            }}
                          >
                            {run.result}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          )}

          {detail && activeTab === 'evidence' && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  flexWrap: 'wrap',
                  gap: '8px',
                }}
              >
                <div>
                  <h4 style={{ margin: '0 0 4px 0', fontSize: '0.875rem' }}>
                    已发布证据 (Published Evidence)
                  </h4>
                  <p style={{ margin: 0, fontSize: '0.75rem', color: 'var(--fg-muted)' }}>
                    由 Issue 自主持有的已发布产物与人工证明材料；仅在点击时换取下载原件地址。
                  </p>
                </div>
                <div>
                  <input
                    type="file"
                    ref={evidenceFileInputRef}
                    style={{ display: 'none' }}
                    onChange={handleEvidenceUpload}
                  />
                  <button
                    type="button"
                    className="btn-primary"
                    disabled={isUploadingEvidence || isLoading}
                    onClick={() => evidenceFileInputRef.current?.click()}
                    style={{ fontSize: '0.8125rem', padding: '6px 12px' }}
                  >
                    <Upload size={14} aria-hidden="true" style={{ marginRight: '6px' }} />
                    {isUploadingEvidence ? '正在上传证据...' : '上传证据'}
                  </button>
                </div>
              </div>

              {evidenceActionError && (
                <div
                  className="form-error-banner"
                  role="alert"
                  style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                    <AlertTriangle size={14} aria-hidden="true" />
                    <span>{evidenceActionError}</span>
                  </div>
                  <button
                    type="button"
                    className="ghost-btn"
                    onClick={() => setEvidenceActionError(null)}
                    aria-label="关闭上传错误提示"
                    style={{ padding: '2px 8px', fontSize: '0.75rem' }}
                  >
                    关闭
                  </button>
                </div>
              )}

              {isUploadingEvidence && (
                <div
                  style={{
                    padding: '12px',
                    borderRadius: 'var(--radius-sm)',
                    border: '1px solid var(--border)',
                    background: 'var(--bg-subtle, rgba(255,255,255,0.02))',
                    fontSize: '0.8125rem',
                    color: 'var(--fg-muted)',
                    display: 'flex',
                    alignItems: 'center',
                    gap: '8px',
                  }}
                >
                  <RefreshCw size={14} className="spin" aria-hidden="true" />
                  <span>正在上传并发布证据材料，请稍候...</span>
                </div>
              )}

              {(() => {
                const scopedEvidence = (detail.evidence ?? []).filter((e) => e.issueId === issueId)
                if (scopedEvidence.length === 0) {
                  return (
                    <p style={{ fontSize: '0.875rem', color: 'var(--fg-muted)', margin: '16px 0' }}>
                      暂无已发布证据。执行者完成交付或人工上传后，将在此公开展示。
                    </p>
                  )
                }

                return (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
                    {scopedEvidence.map((ev) => {
                      const isDownloading = downloadingBlobId === ev.blobId
                      const currentError = downloadError?.blobId === ev.blobId ? downloadError.message : null
                      const displayName = ev.name || ev.uri
                      const originLabel =
                        ev.origin === 'HUMAN'
                          ? '人工上传'
                          : ev.origin === 'EXECUTOR'
                            ? '执行者交付'
                            : ev.origin

                      return (
                        <div
                          key={`${ev.issueId}-${ev.blobId}`}
                          className="evidence-card"
                          data-blob-id={ev.blobId}
                          data-origin={ev.origin}
                          style={{
                            background: 'var(--bg-subtle, rgba(255,255,255,0.02))',
                            border: '1px solid var(--border)',
                            borderRadius: 'var(--radius-sm)',
                            padding: '12px',
                            display: 'flex',
                            flexDirection: 'column',
                            gap: '8px',
                          }}
                        >
                          <div
                            style={{
                              display: 'flex',
                              alignItems: 'center',
                              justifyContent: 'space-between',
                              flexWrap: 'wrap',
                              gap: '8px',
                            }}
                          >
                            <div style={{ display: 'flex', alignItems: 'center', gap: '8px', minWidth: 0, flex: 1 }}>
                              <span
                                className={`badge ${ev.origin === 'HUMAN' ? 'badge-waiting' : 'badge-run'}`}
                                style={{ fontSize: '0.7rem', flexShrink: 0 }}
                              >
                                {originLabel}
                              </span>
                              <strong
                                style={{
                                  fontSize: '0.875rem',
                                  color: 'var(--fg)',
                                  wordBreak: 'break-all',
                                }}
                                title={displayName}
                              >
                                {displayName}
                              </strong>
                            </div>
                            <button
                              type="button"
                              className="quick-action-btn"
                              disabled={isDownloading}
                              onClick={() => handleDownloadEvidence(ev)}
                              style={{ display: 'inline-flex', alignItems: 'center', gap: '4px', flexShrink: 0 }}
                              aria-label={`下载 ${displayName}`}
                            >
                              <Download size={13} aria-hidden="true" />
                              <span>{isDownloading ? '获取链接中...' : '下载原件'}</span>
                            </button>
                          </div>

                          <div
                            style={{
                              display: 'flex',
                              alignItems: 'center',
                              gap: '12px',
                              flexWrap: 'wrap',
                              fontSize: '0.75rem',
                              color: 'var(--fg-muted)',
                            }}
                          >
                            <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px' }}>
                              <span>URI:</span>
                              <code
                                style={{
                                  fontFamily: 'var(--mono)',
                                  fontSize: '0.75rem',
                                  color: 'var(--fg-dim)',
                                  background: 'rgba(0, 0, 0, 0.2)',
                                  padding: '1px 5px',
                                  borderRadius: '3px',
                                }}
                              >
                                {ev.uri}
                              </code>
                            </span>
                            {ev.runId && <span>关联 Run: #{ev.runId.slice(0, 8)}</span>}
                            <span>发布于: {ev.publishedAt}</span>
                          </div>

                          {currentError && (
                            <div
                              className="form-error-banner"
                              role="alert"
                              style={{
                                marginTop: '4px',
                                display: 'flex',
                                alignItems: 'center',
                                justifyContent: 'space-between',
                                fontSize: '0.75rem',
                              }}
                            >
                              <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                                <AlertTriangle size={13} aria-hidden="true" />
                                <span>{currentError}</span>
                              </div>
                              <button
                                type="button"
                                className="ghost-btn"
                                onClick={() => setDownloadError(null)}
                                aria-label="关闭下载错误提示"
                                style={{ padding: '1px 6px', fontSize: '0.75rem' }}
                              >
                                关闭
                              </button>
                            </div>
                          )}
                        </div>
                      )
                    })}
                  </div>
                )
              })()}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
