import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Activity,
  AlertCircle,
  AlertTriangle,
  ArrowRight,
  Bot,
  Coins,
  Download,
  Eye,
  FileText,
  ListOrdered,
  MessageSquare,
  Paperclip,
  Pencil,
  RefreshCw,
  RotateCcw,
  Send,
  ShieldAlert,
  Square,
  Trash2,
  Upload,
  User,
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
import { queryKeys } from '@/shared/lib/query-keys'
import { createUuid } from '@/shared/lib/uuid'
import type { ProjectsApi } from '../projects-api'
import { projectsApi } from '../projects-api'
import type {
  IssueEvidenceDTO,
  ProjectWorkflowDTO,
} from '../types'

export interface IssueDetailModalProps {
  isOpen: boolean
  issueId: string | null
  projectId?: string
  workflow?: ProjectWorkflowDTO
  onClose: () => void
  onUpdated?: () => void
  onIssueUpdated?: () => void
  api?: ProjectsApi
  storageService?: StorageService
  hashFile?: HashFile
  onOpenThread?: (threadId: string) => void
}

type TabKey = 'spec' | 'activities' | 'runs' | 'stageBudgets' | 'agentThreads' | 'evidence'

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

interface EvidenceRowProps {
  evidence: IssueEvidenceDTO
  storageService: StorageService
}

function EvidenceRow({ evidence, storageService }: EvidenceRowProps) {
  const { data: presignedUrl, isLoading } = useQuery({
    queryKey: ['storage', 'blob', 'download-url', evidence.blobId],
    queryFn: async () => {
      try {
        return await storageService.getBlobDownloadUrl(evidence.blobId)
      } catch {
        return await storageService.getBlobPreviewUrl(evidence.blobId)
      }
    },
    enabled: Boolean(evidence.blobId),
    staleTime: 60_000,
  })

  const [isOpening, setIsOpening] = useState(false)

  const handleClick = async (e: React.MouseEvent<HTMLAnchorElement>) => {
    if (presignedUrl?.url) {
      return
    }
    e.preventDefault()
    setIsOpening(true)
    try {
      let res: { url: string } | null = null
      try {
        res = await storageService.getBlobDownloadUrl(evidence.blobId)
      } catch {
        res = await storageService.getBlobPreviewUrl(evidence.blobId)
      }
      if (res?.url) {
        window.open(res.url, '_blank', 'noreferrer')
      }
    } catch {
      // 容错处理
    } finally {
      setIsOpening(false)
    }
  }

  return (
    <div key={evidence.blobId} className="evidence-card">
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
        <Paperclip size={14} aria-hidden="true" />
        <strong>{evidence.name || evidence.blobId}</strong>
        {evidence.actorAgentName && (
          <span className="badge badge-agent">{evidence.actorAgentName}</span>
        )}
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
        <a
          href={presignedUrl?.url || '#'}
          target="_blank"
          rel="noreferrer"
          className="ghost-btn"
          onClick={handleClick}
          title="预览或下载"
        >
          <Download size={14} className={isLoading || isOpening ? 'animate-spin' : ''} aria-hidden="true" />
          <span>{isLoading || isOpening ? '获取中...' : '下载/查看'}</span>
        </a>
      </div>
    </div>
  )
}

export function IssueDetailModal({
  isOpen,
  issueId,
  projectId = '',
  workflow,
  onClose,
  onUpdated,
  onIssueUpdated,
  api = projectsApi,
  storageService = defaultStorageService,
  hashFile,
  onOpenThread,
}: IssueDetailModalProps) {
  const notifyUpdated = () => {
    onUpdated?.()
    onIssueUpdated?.()
  }
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

  const evidenceQueryKey = queryKeys.projects.evidence(issueId ?? '')
  const { data: serverEvidences = [] } = useQuery({
    queryKey: evidenceQueryKey,
    queryFn: () => (api.listIssueEvidence ? api.listIssueEvidence(issueId!) : Promise.resolve([])),
    enabled: isOpen && Boolean(issueId),
  })

  const [activeTab, setActiveTab] = useState<TabKey>('spec')
  const [actionError, setActionError] = useState<string | null>(null)
  const displayError = actionError || (queryError instanceof Error ? queryError.message : null)
  const [conflictDetail, setConflictDetail] = useState<string | null>(null)
  const [isActionPending, setIsActionPending] = useState(false)

  // 1. Spec 编辑草稿状态与 409 保护
  const [isEditingSpec, setIsEditingSpec] = useState(false)
  const [draftTitle, setDraftTitle] = useState('')
  const [draftDescription, setDraftDescription] = useState('')
  const [specVersion, setSpecVersion] = useState('0')

  // 2. Activity 发布状态（普通评论 vs 运行指令）
  const [activityKind, setActivityKind] = useState<'COMMENT' | 'INSTRUCTION'>('COMMENT')
  const [activityBody, setActivityBody] = useState('')
  const [activityRequestKey, setActivityRequestKey] = useState<string>(() => createUuid())

  // 3. 阻塞表单
  const [isBlockModalOpen, setIsBlockModalOpen] = useState(false)
  const [blockReason, setBlockReason] = useState('')
  const [blockRequestKey, setBlockRequestKey] = useState<string>(() => createUuid())

  // 4. UNKNOWN 人工核查表单
  const [isResolveUnknownOpen, setIsResolveUnknownOpen] = useState(false)
  const [verificationInput, setVerificationInput] = useState('')
  const [resolveUnknownRequestKey, setResolveUnknownRequestKey] = useState<string>(() => createUuid())

  // 5. 阶段预算重置表单
  const [isResetBudgetOpen, setIsResetBudgetOpen] = useState(false)
  const [resetBudgetState, setResetBudgetState] = useState('')
  const [resetBudgetMaxRuns, setResetBudgetMaxRuns] = useState<number>(3)
  const [budgetRequestKey, setBudgetRequestKey] = useState<string>(() => createUuid())

  // 6. Stop 终止表单
  const [isStopModalOpen, setIsStopModalOpen] = useState(false)
  const [stopDetail, setStopDetail] = useState('')
  const [stopRequestKey, setStopRequestKey] = useState<string>(() => createUuid())

  // 7. 删除 Issue 确认
  const [isDeleteModalOpen, setIsDeleteModalOpen] = useState(false)

  // 8. Evidence 状态
  const [localEvidences, setLocalEvidences] = useState<IssueEvidenceDTO[]>([])
  const [isUploadingEvidence, setIsUploadingEvidence] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const allEvidences = useMemo(() => {
    const seen = new Set<string>()
    const merged: IssueEvidenceDTO[] = []
    for (const ev of [...localEvidences, ...serverEvidences]) {
      const key = `${ev.blobId}-${ev.name ?? ''}`
      if (!seen.has(key)) {
        seen.add(key)
        merged.push(ev)
      }
    }
    return merged
  }, [localEvidences, serverEvidences])

  // 初始化草稿
  useEffect(() => {
    if (detail?.issue) {
      if (!isEditingSpec) {
        setDraftTitle(detail.issue.title)
        setDraftDescription(detail.issue.description)
        setSpecVersion(detail.issue.version)
      }
    }
  }, [detail, isEditingSpec])

  // 关闭时清理临时对话框
  useEffect(() => {
    if (!isOpen) {
      setIsEditingSpec(false)
      setIsBlockModalOpen(false)
      setIsResolveUnknownOpen(false)
      setIsResetBudgetOpen(false)
      setIsStopModalOpen(false)
      setIsDeleteModalOpen(false)
      setActionError(null)
      setConflictDetail(null)
    }
  }, [isOpen])

  // ESC 键关闭
  useEffect(() => {
    if (!isOpen) {
      return
    }
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault()
        onClose()
      }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [isOpen, onClose])

  if (!isOpen || !issueId) {
    return null
  }

  const issue = detail?.issue
  const isBlocked = issue?.state === 'BLOCKED' || Boolean(issue?.blockedFromState)
  const isUnknown = issue?.state === 'UNKNOWN' || issue?.pauseReason === 'UNKNOWN'
  const isDone = issue?.state === 'DONE'

  // 从传入的 workflow 找到可转移的 next 列表
  const currentWorkflowState = workflow?.states.find((s) => s.state === issue?.state)
  const availableNextStates = currentWorkflowState?.next ?? []

  // 刷新最新数据（保留草稿）
  const handleReloadFreshData = async () => {
    setActionError(null)
    setConflictDetail(null)
    const fresh = await refetch()
    if (fresh.data?.issue) {
      setSpecVersion(fresh.data.issue.version)
    }
  }

  // --- 写操作执行封装：冻结 requestKey 支持相同重试，409 保留草稿 ---

  // 1. 保存 Spec
  const handleSaveSpec = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) return
    const trimmedTitle = draftTitle.trim()
    if (!trimmedTitle) {
      setActionError('标题不能为空')
      return
    }

    setIsActionPending(true)
    setActionError(null)
    setConflictDetail(null)
    try {
      await api.updateIssue(issue.id, {
        expectedVersion: specVersion,
        title: trimmedTitle,
        description: draftDescription.trim(),
      })
      setIsEditingSpec(false)
      await queryClient.invalidateQueries({ queryKey: issueQueryKey })
      notifyUpdated()
    } catch (err) {
      if (isConflictError(err)) {
        setConflictDetail('Issue 已被其他操作更新 (409 冲突)。已保留编辑草稿，请刷新版本后重试。')
      } else {
        setActionError(err instanceof Error ? err.message : '更新 Issue 失败')
      }
    } finally {
      setIsActionPending(false)
    }
  }

  // 2. 流转状态 (Transition) - 每次操作开始时分配 key，重试时沿用 key
  const handleTransition = async (toState: string, retryKey?: string) => {
    if (!issue) return
    const reqKey = retryKey || createUuid()
    setIsActionPending(true)
    setActionError(null)
    setConflictDetail(null)
    try {
      await api.transitionIssue(issue.id, {
        expectedVersion: issue.version,
        requestKey: reqKey,
        toState,
      })
      await queryClient.invalidateQueries({ queryKey: issueQueryKey })
      notifyUpdated()
    } catch (err) {
      if (isConflictError(err)) {
        setConflictDetail(`流转到 ${toState} 失败：版本冲突 (409)，请刷新数据后重试。`)
      } else {
        setActionError(err instanceof Error ? err.message : '状态流转失败')
      }
    } finally {
      setIsActionPending(false)
    }
  }

  // 3. 阻塞 (Block) - 冻结 blockRequestKey
  const handleBlockSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) return
    if (!blockReason.trim()) {
      setActionError('阻塞原因不能为空')
      return
    }

    setIsActionPending(true)
    setActionError(null)
    setConflictDetail(null)
    try {
      await api.blockIssue(issue.id, {
        expectedVersion: issue.version,
        requestKey: blockRequestKey,
        reason: blockReason.trim(),
      })
      setIsBlockModalOpen(false)
      setBlockReason('')
      setBlockRequestKey(createUuid()) // 成功后重置下一次 key
      await queryClient.invalidateQueries({ queryKey: issueQueryKey })
      notifyUpdated()
    } catch (err) {
      if (isConflictError(err)) {
        setConflictDetail('阻塞操作遇到版本冲突 (409)。已为您保留输入内容，请刷新版本后以相同请求键重试。')
      } else {
        setActionError(err instanceof Error ? err.message : '阻塞 Issue 失败')
      }
    } finally {
      setIsActionPending(false)
    }
  }

  // 4. 恢复 (Recover) - 沿用或分配 requestKey
  const handleRecover = async (retryKey?: string) => {
    if (!issue) return
    const reqKey = retryKey || createUuid()
    setIsActionPending(true)
    setActionError(null)
    setConflictDetail(null)
    try {
      await api.recoverIssue(issue.id, {
        expectedVersion: issue.version,
        requestKey: reqKey,
      })
      await queryClient.invalidateQueries({ queryKey: issueQueryKey })
      notifyUpdated()
    } catch (err) {
      if (isConflictError(err)) {
        setConflictDetail('恢复操作遇到版本冲突 (409)，请刷新后重试。')
      } else {
        setActionError(err instanceof Error ? err.message : '恢复 Issue 失败')
      }
    } finally {
      setIsActionPending(false)
    }
  }

  // 5. 人工核查 UNKNOWN - 冻结 resolveUnknownRequestKey
  const handleResolveUnknownSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) return
    if (!verificationInput.trim()) {
      setActionError('人工核查说明不能为空')
      return
    }

    setIsActionPending(true)
    setActionError(null)
    setConflictDetail(null)
    try {
      await api.resolveUnknown(issue.id, {
        expectedVersion: issue.version,
        requestKey: resolveUnknownRequestKey,
        verification: verificationInput.trim(),
      })
      setIsResolveUnknownOpen(false)
      setVerificationInput('')
      setResolveUnknownRequestKey(createUuid())
      await queryClient.invalidateQueries({ queryKey: issueQueryKey })
      notifyUpdated()
    } catch (err) {
      if (isConflictError(err)) {
        setConflictDetail('核查操作遇到版本冲突 (409)。已为您保留核查文本，请刷新版本后重试。')
      } else {
        setActionError(err instanceof Error ? err.message : '解除 UNKNOWN 门禁失败')
      }
    } finally {
      setIsActionPending(false)
    }
  }

  // 6. Stop 终止 - 冻结 stopRequestKey
  const handleStopSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) return
    setIsActionPending(true)
    setActionError(null)
    setConflictDetail(null)
    try {
      await api.stopIssue(issue.id, {
        expectedVersion: issue.version,
        requestKey: stopRequestKey,
        detail: stopDetail.trim() || null,
      })
      setIsStopModalOpen(false)
      setStopDetail('')
      setStopRequestKey(createUuid())
      await queryClient.invalidateQueries({ queryKey: issueQueryKey })
      notifyUpdated()
    } catch (err) {
      if (isConflictError(err)) {
        setConflictDetail('终止操作遇到版本冲突 (409)，请刷新后重试。')
      } else {
        setActionError(err instanceof Error ? err.message : '终止运行失败')
      }
    } finally {
      setIsActionPending(false)
    }
  }

  // 7. 重开 (Reopen) - 沿用或分配 requestKey
  const handleReopen = async (retryKey?: string) => {
    if (!issue) return
    const reqKey = retryKey || createUuid()
    setIsActionPending(true)
    setActionError(null)
    try {
      await api.reopenIssue(issue.id, {
        expectedVersion: issue.version,
        requestKey: reqKey,
      })
      await queryClient.invalidateQueries({ queryKey: issueQueryKey })
      notifyUpdated()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '重新打开 Issue 失败')
    } finally {
      setIsActionPending(false)
    }
  }

  // 8. 重置阶段预算 - 冻结 budgetRequestKey
  const handleResetBudgetSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) return
    if (!resetBudgetState) {
      setActionError('请选择要重置预算的工作阶段')
      return
    }
    if (resetBudgetMaxRuns <= 0) {
      setActionError('最大额度必须大于 0')
      return
    }

    setIsActionPending(true)
    setActionError(null)
    setConflictDetail(null)
    try {
      await api.resetStageBudget(issue.id, {
        expectedVersion: issue.version,
        requestKey: budgetRequestKey,
        state: resetBudgetState,
        maxRuns: resetBudgetMaxRuns,
      })
      setIsResetBudgetOpen(false)
      setBudgetRequestKey(createUuid())
      await queryClient.invalidateQueries({ queryKey: issueQueryKey })
      notifyUpdated()
    } catch (err) {
      if (isConflictError(err)) {
        setConflictDetail('重置阶段预算遇到版本冲突 (409)。已保留设置，请刷新版本后重试。')
      } else {
        setActionError(err instanceof Error ? err.message : '重置阶段预算失败')
      }
    } finally {
      setIsActionPending(false)
    }
  }

  // 9. 追加活动 (Activity) - 区分 COMMENT 与 INSTRUCTION，冻结 activityRequestKey
  const handleAppendActivity = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) return
    const trimmedBody = activityBody.trim()
    if (!trimmedBody) {
      setActionError('内容不能为空')
      return
    }

    setIsActionPending(true)
    setActionError(null)
    setConflictDetail(null)
    try {
      await api.appendIssueActivity(issue.id, {
        expectedVersion: issue.version,
        requestKey: activityRequestKey,
        kind: activityKind,
        body: trimmedBody,
      })
      setActivityBody('')
      setActivityRequestKey(createUuid()) // 提交成功生成新 key
      await queryClient.invalidateQueries({ queryKey: issueQueryKey })
      notifyUpdated()
    } catch (err) {
      if (isConflictError(err)) {
        setConflictDetail('发布活动遇到版本冲突 (409)。已保留您的输入草稿，请刷新版本后以相同请求键重试。')
      } else {
        setActionError(err instanceof Error ? err.message : '发布活动失败')
      }
    } finally {
      setIsActionPending(false)
    }
  }

  // 10. 删除 Issue
  const handleDeleteIssue = async () => {
    if (!issue) return
    setIsActionPending(true)
    setActionError(null)
    try {
      await api.deleteIssue(issue.id, issue.version)
      setIsDeleteModalOpen(false)
      onClose()
      notifyUpdated()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '删除 Issue 失败')
    } finally {
      setIsActionPending(false)
    }
  }

  // 11. 上传证据
  const handleFileSelected = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file || !issue) return
    try {
      validateUploadFile(file)
    } catch (err) {
      setActionError(err instanceof Error ? err.message : '文件校验不通过')
      return
    }

    setIsUploadingEvidence(true)
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
      setActionError(err instanceof Error ? err.message : '上传证据失败')
    } finally {
      setIsUploadingEvidence(false)
      if (fileInputRef.current) {
        fileInputRef.current.value = ''
      }
    }
  }

  return (
    <div
      className="modal-backdrop"
      role="presentation"
      onClick={(e) => {
        if (e.target === e.currentTarget) {
          onClose()
        }
      }}
    >
      <div
        className="modal-card issue-detail-modal-card"
        role="dialog"
        aria-modal="true"
        aria-label={`Issue #${issue?.number || ''} 详情`}
      >
        {/* 弹窗头部 */}
        <div className="modal-header">
          <div className="issue-detail-header-left">
            <span className="issue-number" style={{ fontSize: '1.1rem' }}>
              #{issue?.number || '...'}
            </span>
            <span className="badge badge-state">{issue?.state || 'INIT'}</span>

            {isBlocked && (
              <span className="badge badge-blocked" title={issue?.blockReason || '业务阻塞'}>
                <AlertCircle size={12} aria-hidden="true" />
                BLOCKED
              </span>
            )}
            {isUnknown && (
              <span className="badge badge-unknown" title="待人工核查外部副作用">
                <ShieldAlert size={12} aria-hidden="true" />
                UNKNOWN 门禁
              </span>
            )}
            {issue?.pauseReason === 'USER' && (
              <span className="badge badge-paused">PAUSED</span>
            )}
            {issue?.pauseReason === 'ERROR' && (
              <span className="badge badge-failed">ERROR</span>
            )}
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <button
              type="button"
              className="ghost-btn"
              onClick={() => void handleReloadFreshData()}
              disabled={isLoading || isActionPending}
              title="刷新数据"
            >
              <RefreshCw size={14} className={isLoading ? 'animate-spin' : ''} aria-hidden="true" />
            </button>
            <button
              type="button"
              className="ghost-btn danger"
              onClick={() => setIsDeleteModalOpen(true)}
              title="删除 Issue"
              aria-label="删除 Issue"
            >
              <Trash2 size={14} aria-hidden="true" />
            </button>
            <button
              type="button"
              className="modal-close-button"
              onClick={onClose}
              aria-label="关闭"
            >
              <X size={16} aria-hidden="true" />
            </button>
          </div>
        </div>

        {/* 顶部操作动作条 */}
        <div className="issue-actions-bar">
          <div className="issue-actions-left">
            {/* 流转目标按钮 */}
            {!isBlocked && availableNextStates.map((toState) => (
              <button
                key={toState}
                type="button"
                className="btn-action primary"
                disabled={isActionPending}
                onClick={() => handleTransition(toState)}
                title={`流转状态至 ${toState}`}
              >
                <span>流转至 {toState}</span>
                <ArrowRight size={12} aria-hidden="true" />
              </button>
            ))}

            {/* UNKNOWN 人工核查 */}
            {isUnknown && (
              <button
                type="button"
                className="btn-action danger"
                disabled={isActionPending}
                onClick={() => setIsResolveUnknownOpen(true)}
                title="核查外部副作用并解除 UNKNOWN"
              >
                <ShieldAlert size={13} aria-hidden="true" />
                <span>人工核查</span>
              </button>
            )}

            {/* 阻塞与恢复 */}
            {isBlocked ? (
              <button
                type="button"
                className="btn-action primary"
                disabled={isActionPending}
                onClick={() => handleRecover()}
                title="解除阻塞并恢复至原工作阶段"
              >
                <RotateCcw size={13} aria-hidden="true" />
                <span>恢复执行</span>
              </button>
            ) : !isDone && (
              <button
                type="button"
                className="btn-action"
                disabled={isActionPending}
                onClick={() => setIsBlockModalOpen(true)}
                title="记录原因并标记业务阻塞"
              >
                <AlertCircle size={13} aria-hidden="true" />
                <span>业务阻塞</span>
              </button>
            )}

            {/* 停止 (Stop) */}
            {!isDone && (
              <button
                type="button"
                className="btn-action"
                disabled={isActionPending}
                onClick={() => setIsStopModalOpen(true)}
                title="终止当前活动 Run 并置为暂停"
              >
                <Square size={13} aria-hidden="true" />
                <span>终止 (Stop)</span>
              </button>
            )}

            {/* 重开 (Reopen) */}
            {isDone && (
              <button
                type="button"
                className="btn-action primary"
                disabled={isActionPending}
                onClick={() => handleReopen()}
                title="重新打开已完成的 Issue 回到 INIT"
              >
                <RotateCcw size={13} aria-hidden="true" />
                <span>重新打开</span>
              </button>
            )}
          </div>
        </div>

        {/* 冲突与错误提示横幅 */}
        {conflictDetail && (
          <div
            className="form-error-banner"
            role="alert"
            style={{ margin: '8px 20px 0 20px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <AlertTriangle size={16} aria-hidden="true" />
              <span>{conflictDetail}</span>
            </div>
            <button
              type="button"
              className="ghost-btn"
              onClick={() => void handleReloadFreshData()}
              style={{ fontSize: '12px', whiteSpace: 'nowrap' }}
            >
              <RefreshCw size={12} aria-hidden="true" />
              <span>刷新并保留草稿</span>
            </button>
          </div>
        )}

        {displayError && (
          <div className="form-error-banner" role="alert" style={{ margin: '8px 20px 0 20px' }}>
            <AlertTriangle size={16} aria-hidden="true" />
            <span>{displayError}</span>
          </div>
        )}

        {/* Tab 导航 */}
        <div className="tab-nav" style={{ padding: '0 20px', borderBottom: '1px solid var(--border)' }}>
          <button
            type="button"
            className={`tab-btn ${activeTab === 'spec' ? 'active' : ''}`}
            onClick={() => setActiveTab('spec')}
          >
            <FileText size={14} aria-hidden="true" />
            <span>需求事实</span>
          </button>
          <button
            type="button"
            className={`tab-btn ${activeTab === 'activities' ? 'active' : ''}`}
            onClick={() => setActiveTab('activities')}
          >
            <Activity size={14} aria-hidden="true" />
            <span>活动时间线 ({detail?.activities?.length || 0})</span>
          </button>
          <button
            type="button"
            className={`tab-btn ${activeTab === 'runs' ? 'active' : ''}`}
            onClick={() => setActiveTab('runs')}
          >
            <ListOrdered size={14} aria-hidden="true" />
            <span>Run 报告 ({detail?.runs?.length || 0})</span>
          </button>
          <button
            type="button"
            className={`tab-btn ${activeTab === 'stageBudgets' ? 'active' : ''}`}
            onClick={() => setActiveTab('stageBudgets')}
          >
            <Coins size={14} aria-hidden="true" />
            <span>阶段预算 ({detail?.stageBudgets?.length || 0})</span>
          </button>
          <button
            type="button"
            className={`tab-btn ${activeTab === 'agentThreads' ? 'active' : ''}`}
            onClick={() => setActiveTab('agentThreads')}
          >
            <Bot size={14} aria-hidden="true" />
            <span>Agent 线程 ({detail?.agentThreads?.length || 0})</span>
          </button>
          <button
            type="button"
            className={`tab-btn ${activeTab === 'evidence' ? 'active' : ''}`}
            onClick={() => setActiveTab('evidence')}
          >
            <Paperclip size={14} aria-hidden="true" />
            <span>公开证据 ({allEvidences.length})</span>
          </button>
        </div>

        {/* Tab 内容区 */}
        <div className="modal-body issue-detail-body">
          {isLoading && !detail ? (
            <div style={{ textAlign: 'center', padding: '40px', color: 'var(--fg-muted)' }}>
              加载 Issue 详情中...
            </div>
          ) : !issue ? (
            <div style={{ textAlign: 'center', padding: '40px', color: 'var(--fg-muted)' }}>
              未找到该 Issue 详情
            </div>
          ) : (
            <>
              {/* TAB 1: 需求事实 (Spec) */}
              {activeTab === 'spec' && (
                <div className="tab-pane">
                  {isEditingSpec ? (
                    <form onSubmit={handleSaveSpec} className="spec-edit-form">
                      <div className="form-group">
                        <label className="form-label required">需求标题</label>
                        <input
                          type="text"
                          className="form-input"
                          value={draftTitle}
                          onChange={(e) => setDraftTitle(e.target.value)}
                          disabled={isActionPending}
                          autoFocus
                        />
                      </div>
                      <div className="form-group">
                        <label className="form-label">需求描述与验收标准</label>
                        <textarea
                          className="form-textarea"
                          rows={8}
                          value={draftDescription}
                          onChange={(e) => setDraftDescription(e.target.value)}
                          disabled={isActionPending}
                        />
                      </div>
                      <div style={{ display: 'flex', gap: '8px', justifyContent: 'flex-end' }}>
                        <button
                          type="button"
                          className="ghost-btn"
                          onClick={() => {
                            setIsEditingSpec(false)
                            setDraftTitle(issue.title)
                            setDraftDescription(issue.description)
                          }}
                          disabled={isActionPending}
                        >
                          取消
                        </button>
                        <button
                          type="submit"
                          className="btn-primary"
                          disabled={isActionPending}
                        >
                          {isActionPending ? '保存中...' : '保存更改'}
                        </button>
                      </div>
                    </form>
                  ) : (
                    <div>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                        <h2 className="spec-title">{issue.title}</h2>
                        <button
                          type="button"
                          className="ghost-btn"
                          onClick={() => setIsEditingSpec(true)}
                          title="编辑需求标题与描述"
                        >
                          <Pencil size={14} aria-hidden="true" />
                          <span>编辑需求</span>
                        </button>
                      </div>

                      <div className="spec-desc-block">
                        {issue.description ? (
                          <div style={{ whiteSpace: 'pre-wrap', lineHeight: 1.6 }}>{issue.description}</div>
                        ) : (
                          <span style={{ color: 'var(--fg-dim)' }}>（暂无需求描述）</span>
                        )}
                      </div>

                      <div className="spec-meta-grid">
                        <div className="meta-item">
                          <span className="meta-label">当前阶段:</span>
                          <span className="meta-value"><code>{issue.state}</code></span>
                        </div>
                        <div className="meta-item">
                          <span className="meta-label">期望版本号:</span>
                          <span className="meta-value"><code>{issue.version}</code></span>
                        </div>
                        {issue.blockedFromState && (
                          <div className="meta-item">
                            <span className="meta-label">阻塞前阶段:</span>
                            <span className="meta-value">{issue.blockedFromState}</span>
                          </div>
                        )}
                        {issue.blockReason && (
                          <div className="meta-item" style={{ gridColumn: 'span 2' }}>
                            <span className="meta-label">阻塞原因:</span>
                            <span className="meta-value" style={{ color: 'var(--danger)' }}>{issue.blockReason}</span>
                          </div>
                        )}
                        {issue.pauseDetail && (
                          <div className="meta-item" style={{ gridColumn: 'span 2' }}>
                            <span className="meta-label">暂停详情:</span>
                            <span className="meta-value">{issue.pauseDetail}</span>
                          </div>
                        )}
                      </div>
                    </div>
                  )}
                </div>
              )}

              {/* TAB 2: 活动时间线 (Activities - 区分 COMMENT 与 INSTRUCTION) */}
              {activeTab === 'activities' && (
                <div className="tab-pane">
                  {/* 发布活动表单 */}
                  <form onSubmit={handleAppendActivity} className="activity-input-card">
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
                      <div className="segmented-control">
                        <button
                          type="button"
                          className={`segmented-item ${activityKind === 'COMMENT' ? 'active' : ''}`}
                          onClick={() => setActivityKind('COMMENT')}
                        >
                          <MessageSquare size={13} aria-hidden="true" />
                          <span>普通评论 (Comment)</span>
                        </button>
                        <button
                          type="button"
                          className={`segmented-item ${activityKind === 'INSTRUCTION' ? 'active' : ''}`}
                          onClick={() => setActivityKind('INSTRUCTION')}
                        >
                          <Send size={13} aria-hidden="true" />
                          <span>下达指令 (Instruction)</span>
                        </button>
                      </div>

                      <span style={{ fontSize: '11px', color: 'var(--fg-dim)' }}>
                        {activityKind === 'INSTRUCTION' ? '投递给当前活动 Run' : '仅在时间线记录，不自动唤醒 Agent'}
                      </span>
                    </div>

                    <textarea
                      className="form-textarea"
                      rows={3}
                      placeholder={activityKind === 'INSTRUCTION' ? '输入指令内容要求当前 Agent 遵循...' : '添加一条讨论或事实备注...'}
                      value={activityBody}
                      onChange={(e) => setActivityBody(e.target.value)}
                      disabled={isActionPending}
                    />

                    <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '8px' }}>
                      <button
                        type="submit"
                        className="btn-primary"
                        disabled={isActionPending || !activityBody.trim()}
                        style={{ display: 'flex', alignItems: 'center', gap: '6px' }}
                      >
                        <Send size={12} aria-hidden="true" />
                        <span>{activityKind === 'INSTRUCTION' ? '派发指令' : '发表评论'}</span>
                      </button>
                    </div>
                  </form>

                  {/* 历史活动事实流 */}
                  <div className="activity-timeline">
                    {(detail.activities ?? []).length === 0 ? (
                      <div className="empty-tip">暂无活动记录</div>
                    ) : (
                      (detail.activities ?? []).map((act) => (
                        <div key={act.sequence} className={`activity-item kind-${act.kind.toLowerCase()}`}>
                          <div className="activity-item-header">
                            <span className="activity-badge-kind">{act.kind}</span>
                            <span className="activity-actor">
                              {act.actorType === 'AGENT' ? (
                                <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px' }}>
                                  <Bot size={12} aria-hidden="true" />
                                  <span>{act.actorAgentName || 'Agent'}</span>
                                </span>
                              ) : act.actorType === 'HUMAN' ? (
                                <span style={{ display: 'inline-flex', alignItems: 'center', gap: '4px' }}>
                                  <User size={12} aria-hidden="true" />
                                  <span>人类</span>
                                </span>
                              ) : (
                                <span>系统</span>
                              )}
                            </span>
                            <span className="activity-time">{act.createdAt}</span>
                            <span className="activity-seq">#{act.sequence}</span>
                          </div>

                          {act.body && (
                            <div className="activity-body-text">{act.body}</div>
                          )}
                        </div>
                      ))
                    )}
                  </div>
                </div>
              )}

              {/* TAB 3: Run 执行事实与区间报告 */}
              {activeTab === 'runs' && (
                <div className="tab-pane">
                  {(detail.runs ?? []).length === 0 ? (
                    <div className="empty-tip">暂无 Run 记录</div>
                  ) : (
                    <div className="runs-list">
                      {(detail.runs ?? []).map((r) => (
                        <div key={r.id} className="run-card">
                          <div className="run-card-header">
                            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                              <span className="run-ordinal">Run #{r.ordinal}</span>
                              <span className="badge badge-state">{r.state}</span>
                              {r.agentName && (
                                <span className="badge badge-agent">
                                  <Bot size={12} aria-hidden="true" />
                                  {r.agentName}
                                </span>
                              )}
                              <span className={`badge badge-run-status status-${r.status.toLowerCase()}`}>
                                {r.status}
                              </span>
                            </div>
                            <span style={{ fontSize: '12px', color: 'var(--fg-dim)' }}>
                              耗时/余量: {r.remainingExecutionMs}ms
                            </span>
                          </div>

                          <div className="run-card-details">
                            <div className="run-detail-row">
                              <span>Entry 区间:</span>
                              <code>{r.startEntryId} → {r.endEntryId || '执行中'}</code>
                            </div>
                            {r.finalAnswerEntryId && (
                              <div className="run-detail-row">
                                <span>最终报告条目:</span>
                                <code>{r.finalAnswerEntryId}</code>
                              </div>
                            )}
                            {r.nextState && (
                              <div className="run-detail-row">
                                <span>交接目标:</span>
                                <strong style={{ color: 'var(--primary)' }}>{r.nextState}</strong>
                              </div>
                            )}
                            {r.error && (
                              <div className="run-detail-row" style={{ color: 'var(--danger)' }}>
                                <span>错误信息:</span>
                                <span>{r.error}</span>
                              </div>
                            )}
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )}

              {/* TAB 4: 阶段预算 (Stage Budgets) */}
              {activeTab === 'stageBudgets' && (
                <div className="tab-pane">
                  <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: '12px' }}>
                    <button
                      type="button"
                      className="btn-primary"
                      onClick={() => {
                        setIsResetBudgetOpen(true)
                        if (!resetBudgetState && detail.stageBudgets[0]) {
                          setResetBudgetState(detail.stageBudgets[0].state)
                        }
                      }}
                      style={{ fontSize: '13px' }}
                    >
                      <Coins size={14} aria-hidden="true" />
                      <span>重置阶段预算</span>
                    </button>
                  </div>

                  {(detail.stageBudgets ?? []).length === 0 ? (
                    <div className="empty-tip">阶段预算尚未授权或暂无工作阶段预算</div>
                  ) : (
                    <div className="budget-grid">
                      {(detail.stageBudgets ?? []).map((b) => (
                        <div key={b.state} className="budget-card">
                          <div className="budget-card-title">
                            <span className="badge badge-state">{b.state}</span>
                            <span>最大额度: {b.maxRuns} 次</span>
                          </div>
                          <div className="budget-stats">
                            <div>
                              <span>已用次数:</span>
                              <strong>{b.usedRuns}</strong>
                            </div>
                            <div>
                              <span>剩余可用:</span>
                              <strong style={{ color: Number(b.remainingRuns) > 0 ? 'var(--success)' : 'var(--danger)' }}>
                                {b.remainingRuns}
                              </strong>
                            </div>
                            <div>
                              <span>高水位序号:</span>
                              <code>#{b.budgetAfterOrdinal}</code>
                            </div>
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )}

              {/* TAB 5: Agent 线程绑定 (AgentThreads) */}
              {activeTab === 'agentThreads' && (
                <div className="tab-pane">
                  {(detail.agentThreads ?? []).length === 0 ? (
                    <div className="empty-tip">当前 Issue 尚未绑定任何 Agent 线程（将在首次 Run 接受时创建）</div>
                  ) : (
                    <div className="thread-list">
                      {(detail.agentThreads ?? []).map((t) => (
                        <div key={t.threadId} className="thread-item">
                          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                            <Bot size={16} aria-hidden="true" />
                            <strong>{t.agentName}</strong>
                          </div>
                          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                            <code>{t.threadId}</code>
                            {onOpenThread && (
                              <button
                                type="button"
                                className="ghost-btn"
                                onClick={() => onOpenThread(t.threadId)}
                                title="打开此 Agent 线程视图"
                              >
                                <Eye size={14} aria-hidden="true" />
                                <span>打开</span>
                              </button>
                            )}
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              )}

              {/* TAB 6: 公开证据 (Evidence) */}
              {activeTab === 'evidence' && (
                <div className="tab-pane">
                  <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: '12px' }}>
                    <input
                      ref={fileInputRef}
                      type="file"
                      style={{ display: 'none' }}
                      onChange={handleFileSelected}
                      disabled={isUploadingEvidence}
                    />
                    <button
                      type="button"
                      className="btn-primary"
                      onClick={() => fileInputRef.current?.click()}
                      disabled={isUploadingEvidence}
                      style={{ fontSize: '13px' }}
                    >
                      <Upload size={14} aria-hidden="true" />
                      <span>{isUploadingEvidence ? '正在上传...' : '上传证据文件'}</span>
                    </button>
                  </div>

                  {allEvidences.length === 0 ? (
                    <div className="empty-tip">暂无公开证据交付物</div>
                  ) : (
                    <div className="evidence-list">
                      {allEvidences.map((ev) => (
                        <EvidenceRow
                          key={ev.blobId}
                          evidence={ev}
                          storageService={storageService}
                        />
                      ))}
                    </div>
                  )}
                </div>
              )}
            </>
          )}
        </div>

        {/* 弹窗底部 */}
        <div className="modal-footer">
          <button type="button" className="ghost-btn" onClick={onClose}>
            关闭
          </button>
        </div>

        {/* --- 子对话框区 --- */}

        {/* 1. 业务阻塞弹窗 */}
        {isBlockModalOpen && (
          <div className="modal-backdrop sub-modal" role="dialog" aria-label="标记业务阻塞">
            <div className="modal-card sub-card">
              <div className="modal-header">
                <h3>标记业务阻塞 (BLOCKED)</h3>
                <button type="button" className="modal-close-button" onClick={() => setIsBlockModalOpen(false)}>
                  <X size={16} aria-hidden="true" />
                </button>
              </div>
              <form onSubmit={handleBlockSubmit}>
                <div className="modal-body">
                  <div className="form-group">
                    <label className="form-label required">阻塞原因</label>
                    <textarea
                      className="form-textarea"
                      rows={3}
                      value={blockReason}
                      onChange={(e) => setBlockReason(e.target.value)}
                      placeholder="说明导致 Issue 无法继续执行的外部原因..."
                      autoFocus
                    />
                  </div>
                </div>
                <div className="modal-footer">
                  <button type="button" className="ghost-btn" onClick={() => setIsBlockModalOpen(false)}>
                    取消
                  </button>
                  <button type="submit" className="btn-primary danger" disabled={isActionPending}>
                    {isActionPending ? '提交中...' : '确认阻塞'}
                  </button>
                </div>
              </form>
            </div>
          </div>
        )}

        {/* 2. UNKNOWN 人工核查弹窗 */}
        {isResolveUnknownOpen && (
          <div className="modal-backdrop sub-modal" role="dialog" aria-label="人工核查 UNKNOWN">
            <div className="modal-card sub-card">
              <div className="modal-header">
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <ShieldAlert size={16} className="text-danger" aria-hidden="true" />
                  <h3>解除 UNKNOWN 门禁（人工核查）</h3>
                </div>
                <button type="button" className="modal-close-button" onClick={() => setIsResolveUnknownOpen(false)}>
                  <X size={16} aria-hidden="true" />
                </button>
              </div>
              <form onSubmit={handleResolveUnknownSubmit}>
                <div className="modal-body">
                  <p style={{ fontSize: '13px', color: 'var(--fg-dim)', margin: '0 0 12px 0' }}>
                    * 运行因崩溃或超时导致在途副作用不明。人工核对残留模型/工具调用与外部副作用后，填写处理依据以解除暂停门禁。
                  </p>
                  <div className="form-group">
                    <label className="form-label required">核查结论与说明</label>
                    <textarea
                      className="form-textarea"
                      rows={4}
                      value={verificationInput}
                      onChange={(e) => setVerificationInput(e.target.value)}
                      placeholder="说明已核实的内容与外部状态一致性保证..."
                      autoFocus
                    />
                  </div>
                </div>
                <div className="modal-footer">
                  <button type="button" className="ghost-btn" onClick={() => setIsResolveUnknownOpen(false)}>
                    取消
                  </button>
                  <button type="submit" className="btn-primary" disabled={isActionPending}>
                    {isActionPending ? '解除中...' : '确认解除门禁'}
                  </button>
                </div>
              </form>
            </div>
          </div>
        )}

        {/* 3. Stop 终止弹窗 */}
        {isStopModalOpen && (
          <div className="modal-backdrop sub-modal" role="dialog" aria-label="终止运行">
            <div className="modal-card sub-card">
              <div className="modal-header">
                <h3>终止当前运行 (Stop)</h3>
                <button type="button" className="modal-close-button" onClick={() => setIsStopModalOpen(false)}>
                  <X size={16} aria-hidden="true" />
                </button>
              </div>
              <form onSubmit={handleStopSubmit}>
                <div className="modal-body">
                  <div className="form-group">
                    <label className="form-label">终止原因 (可选)</label>
                    <textarea
                      className="form-textarea"
                      rows={3}
                      value={stopDetail}
                      onChange={(e) => setStopDetail(e.target.value)}
                      placeholder="说明人工中止执行的原因..."
                      autoFocus
                    />
                  </div>
                </div>
                <div className="modal-footer">
                  <button type="button" className="ghost-btn" onClick={() => setIsStopModalOpen(false)}>
                    取消
                  </button>
                  <button type="submit" className="btn-primary danger" disabled={isActionPending}>
                    {isActionPending ? '终止中...' : '确认终止'}
                  </button>
                </div>
              </form>
            </div>
          </div>
        )}

        {/* 4. 阶段预算重置弹窗 */}
        {isResetBudgetOpen && (
          <div className="modal-backdrop sub-modal" role="dialog" aria-label="重置阶段预算">
            <div className="modal-card sub-card">
              <div className="modal-header">
                <h3>重置阶段预算</h3>
                <button type="button" className="modal-close-button" onClick={() => setIsResetBudgetOpen(false)}>
                  <X size={16} aria-hidden="true" />
                </button>
              </div>
              <form onSubmit={handleResetBudgetSubmit}>
                <div className="modal-body">
                  <div className="form-group">
                    <label htmlFor="reset-budget-state" className="form-label required">工作阶段</label>
                    <input
                      id="reset-budget-state"
                      type="text"
                      className="form-input"
                      value={resetBudgetState}
                      onChange={(e) => setResetBudgetState(e.target.value)}
                      placeholder="例如：DESIGN"
                    />
                  </div>
                  <div className="form-group">
                    <label htmlFor="reset-budget-max-runs" className="form-label required">最大 Run 额度</label>
                    <input
                      id="reset-budget-max-runs"
                      type="number"
                      min={1}
                      className="form-input"
                      value={resetBudgetMaxRuns}
                      onChange={(e) => setResetBudgetMaxRuns(Number(e.target.value))}
                    />
                  </div>
                </div>
                <div className="modal-footer">
                  <button type="button" className="ghost-btn" onClick={() => setIsResetBudgetOpen(false)}>
                    取消
                  </button>
                  <button type="submit" className="btn-primary" disabled={isActionPending}>
                    {isActionPending ? '重置中...' : '确认重置'}
                  </button>
                </div>
              </form>
            </div>
          </div>
        )}

        {/* 5. 删除 Issue 确认弹窗 */}
        {isDeleteModalOpen && (
          <div className="modal-backdrop sub-modal" role="dialog" aria-label="删除 Issue 确认">
            <div className="modal-card sub-card">
              <div className="modal-header">
                <h3>删除 Issue #{issue?.number}</h3>
                <button type="button" className="modal-close-button" onClick={() => setIsDeleteModalOpen(false)}>
                  <X size={16} aria-hidden="true" />
                </button>
              </div>
              <div className="modal-body">
                <p style={{ margin: 0, color: 'var(--fg)' }}>
                  确定要彻底删除该 Issue 吗？此操作将清理对应的全部 Work、Activity、Evidence、Run 及关联会话，无法恢复。
                </p>
              </div>
              <div className="modal-footer">
                <button type="button" className="ghost-btn" onClick={() => setIsDeleteModalOpen(false)}>
                  取消
                </button>
                <button
                  type="button"
                  className="btn-primary danger"
                  disabled={isActionPending}
                  onClick={handleDeleteIssue}
                >
                  {isActionPending ? '删除中...' : '确认删除'}
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
