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
import { useI18n } from '@/shared/i18n'
import { Button } from '@/shared/ui/controls/Button'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { NumberInput } from '@/shared/ui/controls/NumberInput'
import { Select } from '@/shared/ui/controls/Select'
import { Tabs } from '@/shared/ui/controls/Tabs'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { Dialog } from '@/shared/ui/overlays/Dialog'
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
import { MAX_RUNS, parseMaxRuns } from '../workflow-draft'
import { createUuid } from '@/shared/lib/uuid'
import {
  clearPendingAction,
  isNetworkUnknownError,
  loadPendingAction,
  storePendingAction,
  type IssueActionKind,
  type LoadPendingActionResult,
  type PendingIssueAction,
} from '../pending-action-sidecar'
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
  const { t } = useI18n()
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
          title={t('projects.issue.previewOrDownload')}
        >
          <Download size={14} className={isLoading || isOpening ? 'animate-spin' : ''} aria-hidden="true" />
          <span>{isLoading || isOpening ? t('projects.issue.fetching') : t('projects.issue.downloadOrView')}</span>
        </a>
      </div>
    </div>
  )
}

function safeLoadPendingAction(issueId: string | null): LoadPendingActionResult {
  if (!issueId) {
    return { type: 'NONE' }
  }
  try {
    return loadPendingAction(issueId)
  } catch (err) {
    return {
      type: 'STORAGE_ERROR',
      error: err instanceof Error ? err.message : String(err),
    }
  }
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
  const { t } = useI18n()
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

  const evidenceQueryKey = queryKeys.projects.evidence(projectId, issueId ?? '')
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
  const isActionPendingRef = useRef(false)

  // 0. 未决操作持久化侧车与 unknown / 异常状态 (I07)
  const [pendingActionResult, setPendingActionResult] = useState<LoadPendingActionResult>(() => {
    return safeLoadPendingAction(issueId)
  })

  const pendingUnknownAction = pendingActionResult.type === 'VALID' ? pendingActionResult.action : null
  const corruptActionInfo =
    pendingActionResult.type === 'CORRUPT'
      ? { raw: pendingActionResult.raw, error: pendingActionResult.error }
      : null
  const storageLoadError = pendingActionResult.type === 'STORAGE_ERROR' ? pendingActionResult.error : null
  const [isDiscardConfirmOpen, setIsDiscardConfirmOpen] = useState(false)

  // 1. Spec 编辑草稿状态与 409 保护
  const [isEditingSpec, setIsEditingSpec] = useState(false)
  const [draftTitle, setDraftTitle] = useState('')
  const [draftDescription, setDraftDescription] = useState('')
  const [specVersion, setSpecVersion] = useState('0')

  // 2. Activity 发布状态（普通评论 vs 运行指令）
  const [activityKind, setActivityKind] = useState<'COMMENT' | 'INSTRUCTION'>('COMMENT')
  const [activityBody, setActivityBody] = useState('')
  const [activityRequestKey, setActivityRequestKey] = useState<string>(() => createUuid())
  const activityPayloadRef = useRef<{ kind: string; body: string } | null>(null)

  // 3. 阻塞表单
  const [isBlockModalOpen, setIsBlockModalOpen] = useState(false)
  const [blockReason, setBlockReason] = useState('')
  const [blockRequestKey, setBlockRequestKey] = useState<string>(() => createUuid())
  const blockPayloadRef = useRef<{ reason: string } | null>(null)

  // 4. UNKNOWN 人工核查表单
  const [isResolveUnknownOpen, setIsResolveUnknownOpen] = useState(false)
  const [verificationInput, setVerificationInput] = useState('')
  const [resolveUnknownRequestKey, setResolveUnknownRequestKey] = useState<string>(() => createUuid())
  const resolveUnknownPayloadRef = useRef<{ verification: string } | null>(null)

  // 5. 阶段预算重置表单
  const [isResetBudgetOpen, setIsResetBudgetOpen] = useState(false)
  const [resetBudgetState, setResetBudgetState] = useState('')
  const [resetBudgetMaxRuns, setResetBudgetMaxRuns] = useState<number>(3)
  const [budgetRequestKey, setBudgetRequestKey] = useState<string>(() => createUuid())
  const budgetPayloadRef = useRef<{ state: string; maxRuns: number } | null>(null)

  // 6. Stop 终止表单
  const [isStopModalOpen, setIsStopModalOpen] = useState(false)
  const [stopDetail, setStopDetail] = useState('')
  const [stopRequestKey, setStopRequestKey] = useState<string>(() => createUuid())
  const stopPayloadRef = useRef<{ detail: string | null } | null>(null)

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

  // 初始化草稿与版本基线
  useEffect(() => {
    if (detail?.issue) {
      if (!isEditingSpec) {
        setDraftTitle(detail.issue.title)
        setDraftDescription(detail.issue.description)
        setSpecVersion(detail.issue.version)
      }
    }
  }, [detail, isEditingSpec])

  // 挂载/打开/issueId 变化时同步加载持久化侧车未决操作或异常状态
  useEffect(() => {
    if (!issueId || !isOpen) {
      setPendingActionResult({ type: 'NONE' })
      return
    }
    setPendingActionResult(safeLoadPendingAction(issueId))
  }, [issueId, isOpen])

  // 关闭时清理临时对话框、未提交草稿与本地证据 (I09)
  useEffect(() => {
    if (!isOpen) {
      isActionPendingRef.current = false
      setIsEditingSpec(false)
      setIsBlockModalOpen(false)
      setIsResolveUnknownOpen(false)
      setIsResetBudgetOpen(false)
      setIsStopModalOpen(false)
      setIsDeleteModalOpen(false)
      setActionError(null)
      setConflictDetail(null)
      setIsDiscardConfirmOpen(false)

      setDraftTitle('')
      setDraftDescription('')
      setActivityBody('')
      setBlockReason('')
      setVerificationInput('')
      setStopDetail('')
      setLocalEvidences([])

      activityPayloadRef.current = null
      blockPayloadRef.current = null
      resolveUnknownPayloadRef.current = null
      stopPayloadRef.current = null
      budgetPayloadRef.current = null

      setActivityRequestKey(createUuid())
      setBlockRequestKey(createUuid())
      setResolveUnknownRequestKey(createUuid())
      setStopRequestKey(createUuid())
      setBudgetRequestKey(createUuid())
    }
  }, [isOpen])

  // 草稿修改检测：输入变更时旧 unknown 不能丢，修改 draft 时换用全新 requestKey 防同 key 换 payload
  const handleActivityBodyChange = (value: string) => {
    if (pendingUnknownAction && pendingUnknownAction.kind === 'ACTIVITY') {
      setActionError(t('projects.issue.pendingActivityModifyBlocked'))
      return
    }
    setActivityBody(value)
    if (activityPayloadRef.current && activityPayloadRef.current.body !== value) {
      setActivityRequestKey(createUuid())
      activityPayloadRef.current = null
    }
  }

  const handleActivityKindChange = (kind: 'COMMENT' | 'INSTRUCTION') => {
    if (pendingUnknownAction && pendingUnknownAction.kind === 'ACTIVITY') {
      setActionError(t('projects.issue.pendingActivityModifyBlocked'))
      return
    }
    setActivityKind(kind)
    if (activityPayloadRef.current && activityPayloadRef.current.kind !== kind) {
      setActivityRequestKey(createUuid())
      activityPayloadRef.current = null
    }
  }

  const handleBlockReasonChange = (value: string) => {
    if (pendingUnknownAction && pendingUnknownAction.kind === 'BLOCK') {
      setActionError(t('projects.issue.pendingBlockModifyBlocked'))
      return
    }
    setBlockReason(value)
    if (blockPayloadRef.current && blockPayloadRef.current.reason !== value) {
      setBlockRequestKey(createUuid())
      blockPayloadRef.current = null
    }
  }

  const handleVerificationInputChange = (value: string) => {
    if (pendingUnknownAction && pendingUnknownAction.kind === 'RESOLVE_UNKNOWN') {
      setActionError(t('projects.issue.pendingResolveUnknownModifyBlocked'))
      return
    }
    setVerificationInput(value)
    if (resolveUnknownPayloadRef.current && resolveUnknownPayloadRef.current.verification !== value) {
      setResolveUnknownRequestKey(createUuid())
      resolveUnknownPayloadRef.current = null
    }
  }

  const handleStopDetailChange = (value: string) => {
    if (pendingUnknownAction && pendingUnknownAction.kind === 'STOP') {
      setActionError(t('projects.issue.pendingStopModifyBlocked'))
      return
    }
    setStopDetail(value)
    if (stopPayloadRef.current && stopPayloadRef.current.detail !== (value.trim() || null)) {
      setStopRequestKey(createUuid())
      stopPayloadRef.current = null
    }
  }

  const handleBudgetStateChange = (value: string) => {
    if (pendingUnknownAction && pendingUnknownAction.kind === 'RESET_BUDGET') {
      setActionError(t('projects.issue.pendingResetBudgetModifyBlocked'))
      return
    }
    setResetBudgetState(value)
    if (budgetPayloadRef.current && budgetPayloadRef.current.state !== value) {
      setBudgetRequestKey(createUuid())
      budgetPayloadRef.current = null
    }
  }

  const handleBudgetMaxRunsChange = (value: number) => {
    if (pendingUnknownAction && pendingUnknownAction.kind === 'RESET_BUDGET') {
      setActionError(t('projects.issue.pendingResetBudgetModifyBlocked'))
      return
    }
    setResetBudgetMaxRuns(value)
    if (budgetPayloadRef.current && budgetPayloadRef.current.maxRuns !== value) {
      setBudgetRequestKey(createUuid())
      budgetPayloadRef.current = null
    }
  }

  if (!isOpen || !issueId) {
    return null
  }

  const issue = detail?.issue
  const isBlocked = issue?.state === 'BLOCKED' || Boolean(issue?.blockedFromState)
  const isUnknown = issue?.state === 'UNKNOWN' || issue?.pauseReason === 'UNKNOWN'
  const isDone = issue?.state === 'DONE'
  const isWriteBlocked = isActionPending || Boolean(pendingUnknownAction || corruptActionInfo || storageLoadError)

  // 放弃损坏的挂起记录并解锁界面
  const handleDiscardCorruptAction = () => {
    if (!issueId || isActionPendingRef.current) return
    clearPendingAction(issueId, undefined, undefined, true)
    setPendingActionResult({ type: 'NONE' })
  }

  // 从传入的 workflow 找到可转移的 next 列表
  const currentWorkflowState = workflow?.states.find((s) => s.state === issue?.state)
  const availableNextStates = currentWorkflowState?.next ?? []

  // 刷新最新数据（保留草稿，不进行无提示 rebasing）
  const handleReloadFreshData = async () => {
    setActionError(null)
    const fresh = await refetch()
    const freshIssue = fresh.data?.issue
    if (freshIssue) {
      if (!isEditingSpec) {
        setConflictDetail(null)
        setSpecVersion(freshIssue.version)
      } else if (freshIssue.version !== specVersion) {
        // 正在编辑且远端已出现新版本：绝不静默覆写 specVersion！
        // 保留原 version 直到用户显式确认或取消编辑，避免无提示 rebasing 导致并发覆盖
        setConflictDetail(
          t('projects.issue.versionConflictNotice', {
            freshVersion: freshIssue.version,
            specVersion,
          }),
        )
      }
    }
  }

  // --- 核心写操作封装：同 issue 仅一个 pending，发送前侧车落盘 (fail closed)，成功仅清自身 key，unknown 禁新 identity ---
  const executeIssueAction = async (
    kind: IssueActionKind,
    requestKey: string,
    expectedVersion: string,
    payload: Record<string, unknown>,
    runApi: () => Promise<unknown>,
    onExplicitSuccess: () => void,
  ) => {
    if (!issue) return
    if (isWriteBlocked || isActionPendingRef.current) {
      setActionError(t('projects.issue.pendingWriteBlockedNotice'))
      return
    }

    const pending = {
      issueId: issue.id,
      kind,
      requestKey,
      expectedVersion,
      payload,
      createdAt: new Date().toISOString(),
      isUnknown: true,
    } as PendingIssueAction

    // 1. 发送前落盘侧车（fail closed）
    try {
      storePendingAction(issue.id, pending)
    } catch (err) {
      setActionError(t('projects.detail.storePendingFailed', {
          reason: err instanceof Error ? err.message : String(err),
        }))
      return
    }

    isActionPendingRef.current = true
    setIsActionPending(true)
    setActionError(null)
    setConflictDetail(null)

    try {
      await runApi()
      // 2. 异步成功：只能清自己的 identity！
      clearPendingAction(issue.id, requestKey)
      setPendingActionResult({ type: 'NONE' })
      onExplicitSuccess()
      await queryClient.invalidateQueries({ queryKey: issueQueryKey })
      notifyUpdated()
    } catch (err) {
      if (isConflictError(err)) {
        // 409 确定被服务端拒绝，清理侧车并保留草稿
        clearPendingAction(issue.id, requestKey)
        setPendingActionResult({ type: 'NONE' })
        setConflictDetail(t('projects.issue.actionConflictNotice'))
      } else if (!isNetworkUnknownError(err)) {
        // 其余明确 4xx 业务错误，服务端未接受
        clearPendingAction(issue.id, requestKey)
        setPendingActionResult({ type: 'NONE' })
        setActionError(err instanceof Error ? err.message : t('projects.issue.actionFailed'))
      } else {
        // 未知网络错误（408, 429, 5xx, 网络中断）：侧车保留并在 UI 标记 unknown！
        setPendingActionResult({ type: 'VALID', action: pending })
        setActionError(err instanceof Error ? err.message : t('projects.issue.networkUnknownError'))
      }
    } finally {
      isActionPendingRef.current = false
      setIsActionPending(false)
    }
  }

  // 1. 保存 Spec
  const handleSaveSpec = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) return
    if (isWriteBlocked || isActionPendingRef.current) {
      setActionError(t('projects.issue.modifyDraftBlockedByPending'))
      return
    }
    const trimmedTitle = draftTitle.trim()
    if (!trimmedTitle) {
      setActionError(t('projects.issue.titleEmpty'))
      return
    }

    isActionPendingRef.current = true
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
        setConflictDetail(t('projects.issue.specConflictNotice'))
      } else {
        setActionError(err instanceof Error ? err.message : t('projects.issue.updateFailed'))
      }
    } finally {
      isActionPendingRef.current = false
      setIsActionPending(false)
    }
  }

  // 2. 精确重试未决未知操作（完整版本与 payload 严格从冻结 pending 读取，不可因刷新而改变）
  const handleRetryPendingAction = async () => {
    if (!pendingUnknownAction || !issue || isActionPendingRef.current) return
    isActionPendingRef.current = true
    setIsActionPending(true)
    setActionError(null)
    setConflictDetail(null)

    const pending = pendingUnknownAction
    const { kind, requestKey, expectedVersion, payload } = pending

    try {
      switch (kind) {
        case 'TRANSITION':
          await api.transitionIssue(issue.id, {
            expectedVersion,
            requestKey,
            toState: (payload as { toState: string }).toState,
          })
          break
        case 'RECOVER':
          await api.recoverIssue(issue.id, {
            expectedVersion,
            requestKey,
          })
          break
        case 'REOPEN':
          await api.reopenIssue(issue.id, {
            expectedVersion,
            requestKey,
          })
          break
        case 'BLOCK':
          await api.blockIssue(issue.id, {
            expectedVersion,
            requestKey,
            reason: (payload as { reason: string }).reason,
          })
          setIsBlockModalOpen(false)
          setBlockReason('')
          blockPayloadRef.current = null
          setBlockRequestKey(createUuid())
          break
        case 'RESOLVE_UNKNOWN':
          await api.resolveUnknown(issue.id, {
            expectedVersion,
            requestKey,
            verification: (payload as { verification: string }).verification,
          })
          setIsResolveUnknownOpen(false)
          setVerificationInput('')
          resolveUnknownPayloadRef.current = null
          setResolveUnknownRequestKey(createUuid())
          break
        case 'STOP':
          await api.stopIssue(issue.id, {
            expectedVersion,
            requestKey,
            detail: (payload as { detail: string | null }).detail,
          })
          setIsStopModalOpen(false)
          setStopDetail('')
          stopPayloadRef.current = null
          setStopRequestKey(createUuid())
          break
        case 'RESET_BUDGET':
          await api.resetStageBudget(issue.id, {
            expectedVersion,
            requestKey,
            state: (payload as { state: string }).state,
            maxRuns: (payload as { maxRuns: number }).maxRuns,
          })
          setIsResetBudgetOpen(false)
          budgetPayloadRef.current = null
          setBudgetRequestKey(createUuid())
          break
        case 'ACTIVITY':
          await api.appendIssueActivity(issue.id, {
            expectedVersion,
            requestKey,
            kind: (payload as { kind: 'COMMENT' | 'INSTRUCTION' }).kind,
            body: (payload as { body: string }).body,
          })
          setActivityBody('')
          activityPayloadRef.current = null
          setActivityRequestKey(createUuid())
          break
      }
      clearPendingAction(issue.id, requestKey)
      setPendingActionResult({ type: 'NONE' })
      await queryClient.invalidateQueries({ queryKey: issueQueryKey })
      notifyUpdated()
    } catch (err) {
      if (isConflictError(err)) {
        clearPendingAction(issue.id, requestKey)
        setPendingActionResult({ type: 'NONE' })
        setConflictDetail(t('projects.issue.retryConflictNotice'))
      } else if (!isNetworkUnknownError(err)) {
        clearPendingAction(issue.id, requestKey)
        setPendingActionResult({ type: 'NONE' })
        setActionError(err instanceof Error ? err.message : t('projects.issue.actionFailed'))
      } else {
        setActionError(err instanceof Error ? err.message : t('projects.issue.retryStillUnknownError'))
      }
    } finally {
      isActionPendingRef.current = false
      setIsActionPending(false)
    }
  }

  // 3. 明确放弃未决操作（清理侧车与 unknown 状态）
  const handleDiscardPendingAction = () => {
    if (!issueId || !pendingUnknownAction || isActionPendingRef.current) return
    clearPendingAction(issueId, pendingUnknownAction.requestKey)
    setPendingActionResult({ type: 'NONE' })
    setIsDiscardConfirmOpen(false)
  }

  // 4. 流转状态 (Transition)
  const handleTransition = async (toState: string) => {
    if (!issue) return
    const requestKey = createUuid()
    await executeIssueAction(
      'TRANSITION',
      requestKey,
      issue.version,
      { toState },
      () => api.transitionIssue(issue.id, { expectedVersion: issue.version, requestKey, toState }),
      () => {},
    )
  }

  // 5. 恢复 (Recover)
  const handleRecover = async () => {
    if (!issue) return
    const requestKey = createUuid()
    await executeIssueAction(
      'RECOVER',
      requestKey,
      issue.version,
      {},
      () => api.recoverIssue(issue.id, { expectedVersion: issue.version, requestKey }),
      () => {},
    )
  }

  // 6. 重开 (Reopen)
  const handleReopen = async () => {
    if (!issue) return
    const requestKey = createUuid()
    await executeIssueAction(
      'REOPEN',
      requestKey,
      issue.version,
      {},
      () => api.reopenIssue(issue.id, { expectedVersion: issue.version, requestKey }),
      () => {},
    )
  }

  // 7. 阻塞 (Block)
  const handleBlockSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) return
    const trimmed = blockReason.trim()
    if (!trimmed) {
      setActionError(t('projects.issue.blockReasonRequired'))
      return
    }
    blockPayloadRef.current = { reason: trimmed }
    await executeIssueAction(
      'BLOCK',
      blockRequestKey,
      issue.version,
      { reason: trimmed },
      () => api.blockIssue(issue.id, { expectedVersion: issue.version, requestKey: blockRequestKey, reason: trimmed }),
      () => {
        setIsBlockModalOpen(false)
        setBlockReason('')
        blockPayloadRef.current = null
        setBlockRequestKey(createUuid())
      },
    )
  }

  // 8. 人工核查 UNKNOWN
  const handleResolveUnknownSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) return
    const trimmed = verificationInput.trim()
    if (!trimmed) {
      setActionError(t('projects.issue.verificationRequired'))
      return
    }
    resolveUnknownPayloadRef.current = { verification: trimmed }
    await executeIssueAction(
      'RESOLVE_UNKNOWN',
      resolveUnknownRequestKey,
      issue.version,
      { verification: trimmed },
      () => api.resolveUnknown(issue.id, { expectedVersion: issue.version, requestKey: resolveUnknownRequestKey, verification: trimmed }),
      () => {
        setIsResolveUnknownOpen(false)
        setVerificationInput('')
        resolveUnknownPayloadRef.current = null
        setResolveUnknownRequestKey(createUuid())
      },
    )
  }

  // 9. Stop 终止
  const handleStopSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) return
    const trimmedDetail = stopDetail.trim() || null
    stopPayloadRef.current = { detail: trimmedDetail }
    await executeIssueAction(
      'STOP',
      stopRequestKey,
      issue.version,
      { detail: trimmedDetail },
      () => api.stopIssue(issue.id, { expectedVersion: issue.version, requestKey: stopRequestKey, detail: trimmedDetail }),
      () => {
        setIsStopModalOpen(false)
        setStopDetail('')
        stopPayloadRef.current = null
        setStopRequestKey(createUuid())
      },
    )
  }

  // 10. 重置阶段预算
  const handleResetBudgetSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) return
    if (!resetBudgetState) {
      setActionError(t('projects.issue.resetBudgetStateRequired'))
      return
    }
    const parsedMaxRuns = parseMaxRuns(String(resetBudgetMaxRuns))
    if (parsedMaxRuns === null) {
      setActionError(t('projects.edit.maxRunsInvalid', { max: MAX_RUNS }))
      return
    }
    budgetPayloadRef.current = { state: resetBudgetState, maxRuns: parsedMaxRuns }
    await executeIssueAction(
      'RESET_BUDGET',
      budgetRequestKey,
      issue.version,
      { state: resetBudgetState, maxRuns: parsedMaxRuns },
      () => api.resetStageBudget(issue.id, { expectedVersion: issue.version, requestKey: budgetRequestKey, state: resetBudgetState, maxRuns: parsedMaxRuns }),
      () => {
        setIsResetBudgetOpen(false)
        budgetPayloadRef.current = null
        setBudgetRequestKey(createUuid())
      },
    )
  }

  // 11. 追加活动 (Activity)
  const handleAppendActivity = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) return
    const trimmedBody = activityBody.trim()
    if (!trimmedBody) {
      setActionError(t('projects.issue.contentEmpty'))
      return
    }
    activityPayloadRef.current = { kind: activityKind, body: trimmedBody }
    await executeIssueAction(
      'ACTIVITY',
      activityRequestKey,
      issue.version,
      { kind: activityKind, body: trimmedBody },
      () => api.appendIssueActivity(issue.id, { expectedVersion: issue.version, requestKey: activityRequestKey, kind: activityKind, body: trimmedBody }),
      () => {
        setActivityBody('')
        activityPayloadRef.current = null
        setActivityRequestKey(createUuid())
      },
    )
  }

  // 10. 删除 Issue
  const handleDeleteIssue = async () => {
    if (!issue) return
    if (isWriteBlocked || isActionPendingRef.current) {
      setActionError(t('projects.issue.deleteBlockedByPending'))
      return
    }
    isActionPendingRef.current = true
    setIsActionPending(true)
    setActionError(null)
    try {
      await api.deleteIssue(issue.id, issue.version)
      setIsDeleteModalOpen(false)
      onClose()
      notifyUpdated()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : t('projects.issue.deleteFailed'))
    } finally {
      isActionPendingRef.current = false
      setIsActionPending(false)
    }
  }

  // 11. 上传证据
  const handleFileSelected = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
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
      setActionError(err instanceof Error ? err.message : t('projects.issue.uploadEvidenceFailed'))
    } finally {
      isActionPendingRef.current = false
      setIsUploadingEvidence(false)
      if (fileInputRef.current) {
        fileInputRef.current.value = ''
      }
    }
  }

  return (
    <Dialog
      className="issue-detail-modal-card"
      ariaLabel={t('projects.issue.modalAriaLabel', { number: issue?.number || '' })}
      onClose={onClose}
      header={
        <div className="modal-header">
          <div className="issue-detail-header-left">
            <span className="issue-number" style={{ fontSize: '1.1rem' }}>
              #{issue?.number || '...'}
            </span>
            <span className="badge badge-state">{issue?.state || 'INIT'}</span>

            {isBlocked && (
              <span className="badge badge-blocked" title={issue?.blockReason || t('projects.state.blocked')}>
                <AlertCircle size={12} aria-hidden="true" />
                BLOCKED
              </span>
            )}
            {isUnknown && (
              <span className="badge badge-unknown" title={t('projects.issue.pendingVerifyExternalSideEffects')}>
                <ShieldAlert size={12} aria-hidden="true" />
                UNKNOWN
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
            <Button
              variant="ghost"
              danger
              onClick={() => setIsDeleteModalOpen(true)}
              disabled={isWriteBlocked}
              title={t('projects.issue.delete')}
              aria-label={t('projects.issue.delete')}
            >
              <Trash2 size={14} aria-hidden="true" />
            </Button>
            <IconButton label={t('projects.close')} onClick={onClose}>
              <X size={16} aria-hidden="true" />
            </IconButton>
          </div>
        </div>
      }
    >

        {/* 顶部操作动作条 */}
        <div className="issue-actions-bar">
          <div className="issue-actions-left">
            {/* 流转目标按钮 */}
            {!isBlocked && availableNextStates.map((toState) => (
              <Button
                key={toState}
                size="compact"
                disabled={isWriteBlocked}
                onClick={() => handleTransition(toState)}
                title={t('projects.issue.transitionStateTo', { state: toState })}
              >
                <span>{t('projects.issue.transitionToState', { state: toState })}</span>
                <ArrowRight size={12} aria-hidden="true" />
              </Button>
            ))}

            {/* UNKNOWN 人工核查 */}
            {isUnknown && (
              <Button
                size="compact"
                variant="ghost"
                danger
                disabled={isWriteBlocked}
                onClick={() => setIsResolveUnknownOpen(true)}
                title={t('projects.issue.resolveUnknownActionTitle')}
              >
                <ShieldAlert size={13} aria-hidden="true" />
                <span>{t('projects.issue.actionVerify')}</span>
              </Button>
            )}

            {/* 阻塞与恢复 */}
            {isBlocked ? (
              <Button
                size="compact"
                disabled={isWriteBlocked}
                onClick={() => handleRecover()}
                title={t('projects.issue.recoverActionTitle')}
              >
                <RotateCcw size={13} aria-hidden="true" />
                <span>{t('projects.issue.recoverExecution')}</span>
              </Button>
            ) : !isDone && (
              <Button
                size="compact"
                variant="ghost"
                disabled={isWriteBlocked}
                onClick={() => setIsBlockModalOpen(true)}
                title={t('projects.issue.blockActionTitle')}
              >
                <AlertCircle size={13} aria-hidden="true" />
                <span>{t('projects.state.blocked')}</span>
              </Button>
            )}

            {/* 停止 (Stop) */}
            {!isDone && (
              <Button
                size="compact"
                variant="ghost"
                disabled={isWriteBlocked}
                onClick={() => setIsStopModalOpen(true)}
                title={t('projects.issue.stopActionTitle')}
              >
                <Square size={13} aria-hidden="true" />
                <span>{t('projects.issue.stopAction')}</span>
              </Button>
            )}

            {/* 重开 (Reopen) */}
            {isDone && (
              <Button
                size="compact"
                disabled={isWriteBlocked}
                onClick={() => handleReopen()}
                title={t('projects.issue.actionReopenTitle')}
              >
                <RotateCcw size={13} aria-hidden="true" />
                <span>{t('projects.issue.reopen')}</span>
              </Button>
            )}
          </div>
        </div>

        {/* 存储读取异常警告条 */}
        {storageLoadError && (
          <div
            className="form-error-banner"
            role="alert"
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '8px',
              margin: '8px 20px 0 20px',
              backgroundColor: 'var(--bg-danger-subtle, #330000)',
              borderColor: 'var(--border-danger, #ef4444)',
              color: 'var(--fg)',
            }}
          >
            <AlertTriangle size={16} color="#ef4444" aria-hidden="true" />
            <span>{t('projects.issue.storageLoadError', { error: storageLoadError })}</span>
          </div>
        )}

        {/* 损坏挂起记录警告条 */}
        {corruptActionInfo && (
          <div
            className="form-error-banner"
            role="alert"
            style={{
              display: 'flex',
              flexDirection: 'column',
              gap: '8px',
              margin: '8px 20px 0 20px',
              backgroundColor: 'var(--bg-warning-subtle, #2d2600)',
              borderColor: 'var(--border-warning, #eab308)',
              color: 'var(--fg)',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <AlertTriangle size={16} color="#eab308" aria-hidden="true" />
              <strong style={{ color: '#eab308' }}>{t('projects.issue.corruptActionTitle')}</strong>
            </div>
            <div style={{ fontSize: '12px', color: 'var(--fg-muted)' }}>
              {t('projects.issue.corruptActionDetail', { error: corruptActionInfo.error })}
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginTop: '4px' }}>
              <Button
                danger
                onClick={handleDiscardCorruptAction}
                disabled={isActionPending}
                style={{ fontSize: '12px', padding: '4px 12px' }}
              >
                {t('projects.issue.discardCorruptAction')}
              </Button>
            </div>
          </div>
        )}

        {/* 未决写操作未知状态警告条 (I07) */}
        {pendingUnknownAction && (
          <div
            className="form-error-banner"
            role="alert"
            style={{
              display: 'flex',
              flexDirection: 'column',
              gap: '8px',
              margin: '8px 20px 0 20px',
              backgroundColor: 'var(--bg-warning-subtle, #2d2600)',
              borderColor: 'var(--border-warning, #eab308)',
              color: 'var(--fg)',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <AlertTriangle size={16} color="#eab308" aria-hidden="true" />
              <strong style={{ color: '#eab308' }}>{t('projects.issue.unknownPending')}</strong>
            </div>
            <div style={{ fontSize: '12px', color: 'var(--fg-muted)' }}>
              {t('projects.issue.pendingActionKind')} <code>{pendingUnknownAction.kind}</code> |
              {t('projects.issue.pendingRequestKey')} <code>{pendingUnknownAction.requestKey}</code> |
              {t('projects.issue.pendingExpectedVersion')} <code>{pendingUnknownAction.expectedVersion}</code>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginTop: '4px' }}>
              <Button
                onClick={() => void handleRetryPendingAction()}
                disabled={isActionPending}
                style={{ fontSize: '12px', padding: '4px 12px' }}
              >
                {isActionPending ? t('projects.issue.retrying') : t('projects.issue.retryOriginalAction')}
              </Button>
              {!isDiscardConfirmOpen ? (
                <Button
                  variant="ghost"
                  danger
                  onClick={() => setIsDiscardConfirmOpen(true)}
                  disabled={isActionPending}
                  style={{ fontSize: '12px', padding: '4px 12px' }}
                >
                  {t('projects.issue.discardPending')}
                </Button>
              ) : (
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <span style={{ fontSize: '12px', color: 'var(--danger)' }}>
                    {t('projects.issue.discardWarning')}
                  </span>
                  <Button
                    danger
                    onClick={handleDiscardPendingAction}
                    disabled={isActionPending}
                    style={{ fontSize: '12px', padding: '2px 8px' }}
                  >
                    {t('projects.issue.confirmDiscard')}
                  </Button>
                  <Button
                    variant="ghost"
                    onClick={() => setIsDiscardConfirmOpen(false)}
                    style={{ fontSize: '12px', padding: '2px 8px' }}
                  >
                    {t('projects.cancel')}
                  </Button>
                </div>
              )}
            </div>
          </div>
        )}

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
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              {isEditingSpec && detail?.issue && detail.issue.version !== specVersion && (
                <Button
                  onClick={() => {
                    if (detail?.issue) {
                      setSpecVersion(detail.issue.version)
                      setConflictDetail(null)
                    }
                  }}
                  disabled={isWriteBlocked}
                  style={{ fontSize: '12px', whiteSpace: 'nowrap', padding: '2px 8px' }}
                  title={t('projects.issue.confirmOverwriteRiskTitle')}
                >
                  {t('projects.issue.confirmOverwriteRisk')}
                </Button>
              )}
              <Button
                variant="ghost"
                onClick={() => void handleReloadFreshData()}
                style={{ fontSize: '12px', whiteSpace: 'nowrap' }}
              >
                <RefreshCw size={12} aria-hidden="true" />
                <span>{t('projects.issue.refreshAndKeepDraft')}</span>
              </Button>
            </div>
          </div>
        )}

        {displayError && (
          <div className="form-error-banner" role="alert" style={{ margin: '8px 20px 0 20px' }}>
            <AlertTriangle size={16} aria-hidden="true" />
            <span>{displayError}</span>
          </div>
        )}

        {/* Tab 导航与内容：共享 Tabs 统一 tablist/tabpanel 语义、roving tabindex 与方向键导航 */}
        <Tabs
          className="issue-detail-tabs"
          ariaLabel={t('projects.issue.tabsAriaLabel')}
          activeId={activeTab}
          onChange={(id) => setActiveTab(id as TabKey)}
          tabs={[
            {
              id: 'spec',
              label: (
                <>
                  <FileText size={14} aria-hidden="true" />
                  <span>{t('projects.issue.tabSpec')}</span>
                </>
              ),
            },
            {
              id: 'activities',
              label: (
                <>
                  <Activity size={14} aria-hidden="true" />
                  <span>{t('projects.issue.tabActivities', { count: detail?.activities?.length || 0 })}</span>
                </>
              ),
            },
            {
              id: 'runs',
              label: (
                <>
                  <ListOrdered size={14} aria-hidden="true" />
                  <span>{t('projects.issue.tabRuns', { count: detail?.runs?.length || 0 })}</span>
                </>
              ),
            },
            {
              id: 'stageBudgets',
              label: (
                <>
                  <Coins size={14} aria-hidden="true" />
                  <span>{t('projects.issue.tabStageBudgets', { count: detail?.stageBudgets?.length || 0 })}</span>
                </>
              ),
            },
            {
              id: 'agentThreads',
              label: (
                <>
                  <Bot size={14} aria-hidden="true" />
                  <span>{t('projects.issue.tabAgentThreads', { count: detail?.agentThreads?.length || 0 })}</span>
                </>
              ),
            },
            {
              id: 'evidence',
              label: (
                <>
                  <Paperclip size={14} aria-hidden="true" />
                  <span>{t('projects.issue.tabEvidence', { count: allEvidences.length })}</span>
                </>
              ),
            },
          ]}
        >
        {/* Tab 内容区 */}
        <div className="modal-body issue-detail-body">
          {isLoading && !detail ? (
            <StateBlock title={t('projects.issue.loadingDetail')} />
          ) : !issue ? (
            <StateBlock title={t('projects.issue.notFoundDetail')} tone="danger" />
          ) : (
            <>
              {/* TAB 1: 需求事实 (Spec) */}
              {activeTab === 'spec' && (
                <div className="tab-pane">
                  {isEditingSpec ? (
                    <form onSubmit={handleSaveSpec} className="spec-edit-form">
                      <label className="edit-project-field" htmlFor="issue-spec-title">
                        <FieldLabel required>{t('projects.issue.specTitleLabel')}</FieldLabel>
                        <TextInput
                          id="issue-spec-title"
                          value={draftTitle}
                          invalid={!draftTitle.trim()}
                          onChange={(e) => setDraftTitle(e.target.value)}
                          disabled={isWriteBlocked}
                          autoFocus
                        />
                      </label>
                      <label className="edit-project-field" htmlFor="issue-spec-desc">
                        <FieldLabel>{t('projects.issue.specDescLabel')}</FieldLabel>
                        <TextArea
                          id="issue-spec-desc"
                          rows={8}
                          value={draftDescription}
                          onChange={(e) => setDraftDescription(e.target.value)}
                          disabled={isWriteBlocked}
                        />
                      </label>
                      <div style={{ display: 'flex', gap: '8px', justifyContent: 'flex-end' }}>
                        <Button
                          variant="ghost"
                          onClick={() => {
                            setIsEditingSpec(false)
                            setDraftTitle(issue.title)
                            setDraftDescription(issue.description)
                            setSpecVersion(issue.version)
                            setConflictDetail(null)
                          }}
                          disabled={isActionPending}
                        >
                          {t('projects.cancel')}
                        </Button>
                        <Button
                          type="submit"
                          disabled={isWriteBlocked}
                        >
                          {isActionPending ? t('projects.issue.saving') : t('projects.issue.saveChanges')}
                        </Button>
                      </div>
                    </form>
                  ) : (
                    <div>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                        <h2 className="spec-title">{issue.title}</h2>
                        <Button
                          variant="ghost"
                          onClick={() => setIsEditingSpec(true)}
                          disabled={isWriteBlocked}
                          title={t('projects.issue.editSpecTitle')}
                        >
                          <Pencil size={14} aria-hidden="true" />
                          <span>{t('projects.issue.editSpec')}</span>
                        </Button>
                      </div>

                      <div className="spec-desc-block">
                        {issue.description ? (
                          <div style={{ whiteSpace: 'pre-wrap', lineHeight: 1.6 }}>{issue.description}</div>
                        ) : (
                          <span style={{ color: 'var(--fg-dim)' }}>{t('projects.issue.noSpecDesc')}</span>
                        )}
                      </div>

                      <div className="spec-meta-grid">
                        <div className="meta-item">
                          <span className="meta-label">{t('projects.issue.currentStageLabel')}</span>
                          <span className="meta-value"><code>{issue.state}</code></span>
                        </div>
                        <div className="meta-item">
                          <span className="meta-label">{t('projects.issue.expectedVersionLabel')}</span>
                          <span className="meta-value"><code>{issue.version}</code></span>
                        </div>
                        {issue.blockedFromState && (
                          <div className="meta-item">
                            <span className="meta-label">{t('projects.issue.blockedFromStageLabel')}</span>
                            <span className="meta-value">{issue.blockedFromState}</span>
                          </div>
                        )}
                        {issue.blockReason && (
                          <div className="meta-item" style={{ gridColumn: 'span 2' }}>
                            <span className="meta-label">{t('projects.issue.blockReasonLabel')}</span>
                            <span className="meta-value" style={{ color: 'var(--danger)' }}>{issue.blockReason}</span>
                          </div>
                        )}
                        {issue.pauseDetail && (
                          <div className="meta-item" style={{ gridColumn: 'span 2' }}>
                            <span className="meta-label">{t('projects.issue.pauseDetailLabel')}</span>
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
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px', gap: '12px' }}>
                      <Select
                        compact
                        value={activityKind}
                        disabled={isActionPending}
                        aria-label={t('projects.issue.activityTypeAriaLabel')}
                        options={[
                          { value: 'COMMENT', label: t('projects.issue.activityCommentOption') },
                          { value: 'INSTRUCTION', label: t('projects.issue.activityInstructionOption') },
                        ]}
                        onChange={(value) => handleActivityKindChange(value as 'COMMENT' | 'INSTRUCTION')}
                      />

                      <span style={{ fontSize: '11px', color: 'var(--fg-dim)' }}>
                        {activityKind === 'INSTRUCTION'
                          ? t('projects.issue.deliverToActiveRun')
                          : t('projects.issue.timelineOnlyNoWakeup')}
                      </span>
                    </div>

                    <TextArea
                      rows={3}
                      placeholder={
                        activityKind === 'INSTRUCTION'
                          ? t('projects.issue.instructionPlaceholder')
                          : t('projects.issue.commentPlaceholder')
                      }
                      value={activityBody}
                      onChange={(e) => handleActivityBodyChange(e.target.value)}
                      disabled={isActionPending}
                    />

                    <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '8px' }}>
                      <Button
                        type="submit"
                        disabled={isWriteBlocked || !activityBody.trim()}
                        style={{ display: 'flex', alignItems: 'center', gap: '6px' }}
                      >
                        <Send size={12} aria-hidden="true" />
                        <span>
                          {activityKind === 'INSTRUCTION'
                            ? t('projects.issue.dispatchInstruction')
                            : t('projects.issue.comment')}
                        </span>
                      </Button>
                    </div>
                  </form>

                  {/* 历史活动事实流 */}
                  <div className="activity-timeline">
                    {(detail.activities ?? []).length === 0 ? (
                      <div className="empty-tip">{t('projects.issue.emptyActivities')}</div>
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
                                  <span>{t('projects.issue.actorHuman')}</span>
                                </span>
                              ) : (
                                <span>{t('projects.issue.actorSystem')}</span>
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
                    <div className="empty-tip">{t('projects.issue.emptyRuns')}</div>
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
                              {t('projects.issue.durationRemaining', { ms: r.remainingExecutionMs })}
                            </span>
                          </div>

                          <div className="run-card-details">
                            <div className="run-detail-row">
                              <span>{t('projects.issue.entryRangeLabel')}</span>
                              <code>{r.startEntryId} → {r.endEntryId || t('projects.issue.running')}</code>
                            </div>
                            {r.finalAnswerEntryId && (
                              <div className="run-detail-row">
                                <span>{t('projects.issue.finalAnswerEntryLabel')}</span>
                                <code>{r.finalAnswerEntryId}</code>
                              </div>
                            )}
                            {r.nextState && (
                              <div className="run-detail-row">
                                <span>{t('projects.issue.handoverTargetLabel')}</span>
                                <strong style={{ color: 'var(--primary)' }}>{r.nextState}</strong>
                              </div>
                            )}
                            {r.error && (
                              <div className="run-detail-row" style={{ color: 'var(--danger)' }}>
                                <span>{t('projects.issue.errorMessageLabel')}</span>
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
                    <Button
                      onClick={() => {
                        setIsResetBudgetOpen(true)
                        if (!resetBudgetState && detail.stageBudgets[0]) {
                          setResetBudgetState(detail.stageBudgets[0].state)
                        }
                      }}
                      style={{ fontSize: '13px' }}
                    >
                      <Coins size={14} aria-hidden="true" />
                      <span>{t('projects.issue.resetStageBudgetAction')}</span>
                    </Button>
                  </div>

                  {(detail.stageBudgets ?? []).length === 0 ? (
                    <div className="empty-tip">{t('projects.issue.emptyBudgets')}</div>
                  ) : (
                    <div className="budget-grid">
                      {(detail.stageBudgets ?? []).map((b) => (
                        <div key={b.state} className="budget-card">
                          <div className="budget-card-title">
                            <span className="badge badge-state">{b.state}</span>
                            <span>{t('projects.issue.maxRunsBudget', { max: b.maxRuns })}</span>
                          </div>
                          <div className="budget-stats">
                            <div>
                              <span>{t('projects.issue.usedRunsLabel')}</span>
                              <strong>{b.usedRuns}</strong>
                            </div>
                            <div>
                              <span>{t('projects.issue.remainingRunsLabel')}</span>
                              <strong style={{ color: Number(b.remainingRuns) > 0 ? 'var(--success)' : 'var(--danger)' }}>
                                {b.remainingRuns}
                              </strong>
                            </div>
                            <div>
                              <span>{t('projects.issue.budgetAfterOrdinalLabel')}</span>
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
                    <div className="empty-tip">{t('projects.issue.emptyAgentThreads')}</div>
                  ) : (
                    <div className="thread-list">
                      {(detail.agentThreads ?? []).map((thread) => (
                        <div key={thread.threadId} className="thread-item">
                          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                            <Bot size={16} aria-hidden="true" />
                            <strong>{thread.agentName}</strong>
                          </div>
                          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                            <code>{thread.threadId}</code>
                            {onOpenThread && (
                              <Button
                                variant="ghost"
                                onClick={() => onOpenThread(thread.threadId)}
                                title={t('projects.issue.openAgentThreadTitle')}
                              >
                                <Eye size={14} aria-hidden="true" />
                                <span>{t('projects.issue.openAction')}</span>
                              </Button>
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
                      disabled={isUploadingEvidence || isWriteBlocked}
                    />
                    <Button
                      onClick={() => fileInputRef.current?.click()}
                      disabled={isUploadingEvidence || isWriteBlocked}
                      style={{ fontSize: '13px' }}
                    >
                      <Upload size={14} aria-hidden="true" />
                      <span>{isUploadingEvidence ? t('projects.issue.uploading') : t('projects.issue.uploadEvidenceAction')}</span>
                    </Button>
                  </div>

                  {allEvidences.length === 0 ? (
                    <div className="empty-tip">{t('projects.issue.emptyEvidence')}</div>
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
        </Tabs>

        {/* 弹窗底部 */}
        <div className="modal-footer">
          <Button variant="ghost" onClick={onClose}>
            {t('projects.close')}
          </Button>
        </div>

        {/* --- 子对话框区 --- */}

        {/* 1. 业务阻塞弹窗 */}
        {isBlockModalOpen && (
          <Dialog
            className="sub-card"
            title={t('projects.issue.blockDialogTitle')}
            pending={isWriteBlocked}
            onClose={() => setIsBlockModalOpen(false)}
          >
              <form className="modal-card-form" onSubmit={handleBlockSubmit}>
                <div className="modal-body">
                  <label className="edit-project-field" htmlFor="issue-block-reason">
                    <FieldLabel required>{t('projects.issue.blockReasonFieldLabel')}</FieldLabel>
                    <TextArea
                      id="issue-block-reason"
                      rows={3}
                      value={blockReason}
                      onChange={(e) => handleBlockReasonChange(e.target.value)}
                      placeholder={t('projects.issue.blockReasonPlaceholder')}
                      autoFocus
                    />
                  </label>
                </div>
                <div className="modal-footer">
                  <Button variant="ghost" onClick={() => setIsBlockModalOpen(false)}>
                    {t('projects.cancel')}
                    </Button>
                  <Button
                    type="submit"
                    danger
                    disabled={isWriteBlocked}
                    >
                    {isActionPending ? t('projects.issue.submitting') : t('projects.issue.confirmBlock')}
                  </Button>
                </div>
              </form>
              </Dialog>
        )}

        {/* 2. UNKNOWN 人工核查弹窗 */}
        {isResolveUnknownOpen && (
          <Dialog
            className="sub-card"
            title={t('projects.issue.resolveUnknownDialogTitle')}
            headerIcon={<ShieldAlert size={16} className="text-danger" aria-hidden="true" />}
            pending={isWriteBlocked}
            onClose={() => setIsResolveUnknownOpen(false)}
          >
              <form className="modal-card-form" onSubmit={handleResolveUnknownSubmit}>
                <div className="modal-body">
                  <p style={{ fontSize: '13px', color: 'var(--fg-dim)', margin: '0 0 12px 0' }}>
                    {t('projects.issue.resolveUnknownDesc')}
                  </p>
                  <label className="edit-project-field" htmlFor="issue-verification">
                    <FieldLabel required>{t('projects.issue.verificationFieldLabel')}</FieldLabel>
                    <TextArea
                      id="issue-verification"
                      rows={4}
                      value={verificationInput}
                      onChange={(e) => handleVerificationInputChange(e.target.value)}
                      placeholder={t('projects.issue.verificationPlaceholder')}
                      autoFocus
                    />
                  </label>
                </div>
                <div className="modal-footer">
                  <Button variant="ghost" onClick={() => setIsResolveUnknownOpen(false)}>
                    {t('projects.cancel')}
                    </Button>
                  <Button
                    type="submit"
                    disabled={isWriteBlocked}
                    >
                    {isActionPending ? t('projects.issue.resolving') : t('projects.issue.confirmResolveUnknown')}
                  </Button>
                </div>
              </form>
              </Dialog>
        )}

        {/* 3. Stop 终止弹窗 */}
        {isStopModalOpen && (
          <Dialog
            className="sub-card"
            title={t('projects.issue.stopDialogTitle')}
            pending={isWriteBlocked}
            onClose={() => setIsStopModalOpen(false)}
          >
              <form className="modal-card-form" onSubmit={handleStopSubmit}>
                <div className="modal-body">
                  <label className="edit-project-field" htmlFor="issue-stop-detail">
                    <FieldLabel>{t('projects.issue.stopDetailFieldLabel')}</FieldLabel>
                    <TextArea
                      id="issue-stop-detail"
                      rows={3}
                      value={stopDetail}
                      onChange={(e) => handleStopDetailChange(e.target.value)}
                      placeholder={t('projects.issue.stopDetailPlaceholder')}
                      autoFocus
                    />
                  </label>
                </div>
                <div className="modal-footer">
                  <Button variant="ghost" onClick={() => setIsStopModalOpen(false)}>
                    {t('projects.cancel')}
                    </Button>
                  <Button
                    type="submit"
                    danger
                    disabled={isWriteBlocked}
                    >
                    {isActionPending ? t('projects.issue.stopping') : t('projects.issue.confirmStop')}
                  </Button>
                </div>
              </form>
              </Dialog>
        )}

        {/* 4. 阶段预算重置弹窗 */}
        {isResetBudgetOpen && (
          <Dialog
            className="sub-card"
            title={t('projects.issue.resetStageBudgetAction')}
            pending={isWriteBlocked}
            onClose={() => setIsResetBudgetOpen(false)}
          >
              <form className="modal-card-form" onSubmit={handleResetBudgetSubmit}>
                <div className="modal-body">
                  <label className="edit-project-field" htmlFor="reset-budget-state">
                    <FieldLabel required>{t('projects.issue.budgetStageFieldLabel')}</FieldLabel>
                    <TextInput
                      id="reset-budget-state"
                      value={resetBudgetState}
                      onChange={(e) => handleBudgetStateChange(e.target.value)}
                      placeholder={t('projects.issue.budgetStagePlaceholder')}
                    />
                  </label>
                  <label className="edit-project-field" htmlFor="reset-budget-max-runs">
                    <FieldLabel required>{t('projects.issue.budgetMaxRunsFieldLabel')}</FieldLabel>
                    <NumberInput
                      id="reset-budget-max-runs"
                      min={1}
                      max={MAX_RUNS}
                      value={String(resetBudgetMaxRuns)}
                      onChange={(next) => handleBudgetMaxRunsChange(next === '' ? 0 : Number(next))}
                    />
                  </label>
                </div>
                <div className="modal-footer">
                  <Button variant="ghost" onClick={() => setIsResetBudgetOpen(false)}>
                    {t('projects.cancel')}
                    </Button>
                  <Button
                    type="submit"
                    disabled={isWriteBlocked}
                    >
                    {isActionPending ? t('projects.issue.resettingBudget') : t('projects.issue.confirmResetBudget')}
                  </Button>
                </div>
              </form>
              </Dialog>
        )}

        {/* 5. 删除 Issue 确认弹窗 */}
        {isDeleteModalOpen && (
          <Dialog
            className="sub-card"
            title={t('projects.issue.deleteDialogTitle', { number: issue?.number ?? '' })}
            pending={isWriteBlocked}
            onClose={() => setIsDeleteModalOpen(false)}
          >
              <div className="modal-body">
                <p style={{ margin: 0, color: 'var(--fg)' }}>
                  {t('projects.issue.deleteConfirmDesc')}
                </p>
              </div>
              <div className="modal-footer">
                <Button variant="ghost" onClick={() => setIsDeleteModalOpen(false)}>
                  {t('projects.cancel')}
                  </Button>
                <Button
                  danger
                  disabled={isWriteBlocked}
                  onClick={handleDeleteIssue}
                  >
                  {isActionPending ? t('projects.issue.deleting') : t('projects.delete.confirm')}
                </Button>
              </div>
            </Dialog>
        )}
    </Dialog>
  )
}
