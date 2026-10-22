import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Activity,
  AlertCircle,
  ArrowRight,
  Bot,
  Coins,
  FileText,
  ListOrdered,
  Paperclip,
  RotateCcw,
  Send,
  ShieldAlert,
  Square,
  Trash2,
  Upload,
  X,
} from 'lucide-react'
import { validateUploadFile, type HashFile } from '@/features/chat/composer'
import { isConflictError } from '@/shared/api/client'
import {
  storageService as defaultStorageService,
  type StorageService,
} from '@/shared/api/storage-service'
import { useI18n } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { createUuid } from '@/shared/lib/uuid'
import { Button } from '@/shared/ui/controls/Button'
import { FieldLabel } from '@/shared/ui/controls/FieldLabel'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { Select } from '@/shared/ui/controls/Select'
import { Tabs } from '@/shared/ui/controls/Tabs'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { TextInput } from '@/shared/ui/controls/TextInput'
import { Badge } from '@/shared/ui/data/Badge'
import { EmptyState } from '@/shared/ui/feedback/EmptyState'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import type { IssueActionKind } from '../pending-action-sidecar'
import type { ProjectsApi } from '../projects-api'
import { projectsApi } from '../projects-api'
import { MAX_RUNS, parseMaxRuns } from '../workflow-draft'
import { DeleteIssueDialog, ResetBudgetDialog, TextActionDialog } from './issue-detail/IssueActionDialogs'
import { IssueAlerts } from './issue-detail/IssueAlerts'
import {
  IssueActivityTimeline,
  IssueAgentThreadsPane,
  IssueRunsPane,
  IssueSpecView,
  IssueStageBudgetList,
} from './issue-detail/IssueTabPanes'
import { EvidenceRow } from './issue-detail/EvidenceRow'
import { calculateFileSha256, mergeEvidences } from './issue-detail/evidence-files'
import { useIssueActionRunner } from './issue-detail/useIssueActionRunner'
import { useKeyedRequest } from './issue-detail/useKeyedRequest'
import './issue-detail-alerts.css'
import type { IssueEvidenceDTO, ProjectWorkflowDTO } from '@/shared/api/generated/contracts'

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

/**
 * Issue 详情：Spec 编辑、活动、Run、阶段预算、Agent 线程与证据六个 tab，以及流转、阻塞/恢复、终止、重开、人工核查、预算重置与删除等写操作。
 * 带幂等身份的写操作统一经 {@link useIssueActionRunner} 执行。
 */
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

  // 1. Spec 编辑草稿状态与 409 保护
  const [isEditingSpec, setIsEditingSpec] = useState(false)
  const [draftTitle, setDraftTitle] = useState('')
  const [draftDescription, setDraftDescription] = useState('')
  const [specVersion, setSpecVersion] = useState('0')

  // 2. 各写操作表单：值 + 幂等请求身份
  const [activityKind, setActivityKind] = useState<'COMMENT' | 'INSTRUCTION'>('COMMENT')
  const [activityBody, setActivityBody] = useState('')
  const activityRequest = useKeyedRequest<{ kind: string; body: string }>()
  const [isBlockModalOpen, setIsBlockModalOpen] = useState(false)
  const [blockReason, setBlockReason] = useState('')
  const blockRequest = useKeyedRequest<{ reason: string }>()
  const [isResolveUnknownOpen, setIsResolveUnknownOpen] = useState(false)
  const [verificationInput, setVerificationInput] = useState('')
  const resolveUnknownRequest = useKeyedRequest<{ verification: string }>()
  const [isResetBudgetOpen, setIsResetBudgetOpen] = useState(false)
  const [resetBudgetState, setResetBudgetState] = useState('')
  const [resetBudgetMaxRuns, setResetBudgetMaxRuns] = useState<number>(3)
  const budgetRequest = useKeyedRequest<{ state: string; maxRuns: number }>()
  const [isStopModalOpen, setIsStopModalOpen] = useState(false)
  const [stopDetail, setStopDetail] = useState('')
  const stopRequest = useKeyedRequest<{ detail: string | null }>()
  const [isDeleteModalOpen, setIsDeleteModalOpen] = useState(false)

  // 3. Evidence 状态
  const [localEvidences, setLocalEvidences] = useState<IssueEvidenceDTO[]>([])
  const [isUploadingEvidence, setIsUploadingEvidence] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const allEvidences = useMemo(
    () => mergeEvidences(localEvidences, serverEvidences),
    [localEvidences, serverEvidences],
  )

  const resetBlockForm = () => {
    setIsBlockModalOpen(false)
    setBlockReason('')
    blockRequest.reset()
  }
  const resetResolveUnknownForm = () => {
    setIsResolveUnknownOpen(false)
    setVerificationInput('')
    resolveUnknownRequest.reset()
  }
  const resetStopForm = () => {
    setIsStopModalOpen(false)
    setStopDetail('')
    stopRequest.reset()
  }
  const resetBudgetForm = () => {
    setIsResetBudgetOpen(false)
    budgetRequest.reset()
  }
  const resetActivityForm = () => {
    setActivityBody('')
    activityRequest.reset()
  }

  const runner = useIssueActionRunner({
    isOpen,
    issueId,
    issue: detail?.issue,
    api,
    queryClient,
    issueQueryKey,
    notifyUpdated,
    onRetrySucceeded: (kind) => {
      if (kind === 'BLOCK') {
        resetBlockForm()
      } else if (kind === 'RESOLVE_UNKNOWN') {
        resetResolveUnknownForm()
      } else if (kind === 'STOP') {
        resetStopForm()
      } else if (kind === 'RESET_BUDGET') {
        resetBudgetForm()
      } else if (kind === 'ACTIVITY') {
        resetActivityForm()
      }
    },
  })
  const {
    actionError,
    setActionError,
    conflictDetail,
    setConflictDetail,
    isActionPending,
    setIsActionPending,
    isActionPendingRef,
    pendingUnknownAction,
    isWriteBlocked,
    executeIssueAction,
  } = runner
  const displayError = actionError || (queryError instanceof Error ? queryError.message : null)

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

  // 关闭时清理临时对话框、未提交草稿与本地证据 (I09)
  useEffect(() => {
    if (!isOpen) {
      setIsEditingSpec(false)
      setIsBlockModalOpen(false)
      setIsResolveUnknownOpen(false)
      setIsResetBudgetOpen(false)
      setIsStopModalOpen(false)
      setIsDeleteModalOpen(false)
      setDraftTitle('')
      setDraftDescription('')
      setActivityBody('')
      setBlockReason('')
      setVerificationInput('')
      setStopDetail('')
      setLocalEvidences([])
      activityRequest.reset()
      blockRequest.reset()
      resolveUnknownRequest.reset()
      stopRequest.reset()
      budgetRequest.reset()
    }
    // 只在开合时重置；各 reset 函数引用稳定。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen])

  /** 未决未知操作属于该表单时拒绝修改；否则更新值，偏离冻结 payload 即换新请求身份。 */
  const guardedChange = (blockedKind: IssueActionKind, blockedMessage: string, apply: () => void) => {
    if (pendingUnknownAction && pendingUnknownAction.kind === blockedKind) {
      setActionError(t(blockedMessage))
      return
    }
    apply()
  }

  const handleActivityBodyChange = (value: string) => guardedChange('ACTIVITY', 'projects.issue.pendingActivityModifyBlocked', () => {
    setActivityBody(value)
    activityRequest.invalidateIfChanged((frozen) => frozen.body === value)
  })
  const handleActivityKindChange = (kind: 'COMMENT' | 'INSTRUCTION') => guardedChange('ACTIVITY', 'projects.issue.pendingActivityModifyBlocked', () => {
    setActivityKind(kind)
    activityRequest.invalidateIfChanged((frozen) => frozen.kind === kind)
  })
  const handleBlockReasonChange = (value: string) => guardedChange('BLOCK', 'projects.issue.pendingBlockModifyBlocked', () => {
    setBlockReason(value)
    blockRequest.invalidateIfChanged((frozen) => frozen.reason === value)
  })
  const handleVerificationInputChange = (value: string) => guardedChange('RESOLVE_UNKNOWN', 'projects.issue.pendingResolveUnknownModifyBlocked', () => {
    setVerificationInput(value)
    resolveUnknownRequest.invalidateIfChanged((frozen) => frozen.verification === value)
  })
  const handleStopDetailChange = (value: string) => guardedChange('STOP', 'projects.issue.pendingStopModifyBlocked', () => {
    setStopDetail(value)
    stopRequest.invalidateIfChanged((frozen) => frozen.detail === (value.trim() || null))
  })
  const handleBudgetStateChange = (value: string) => guardedChange('RESET_BUDGET', 'projects.issue.pendingResetBudgetModifyBlocked', () => {
    setResetBudgetState(value)
    budgetRequest.invalidateIfChanged((frozen) => frozen.state === value)
  })
  const handleBudgetMaxRunsChange = (value: number) => guardedChange('RESET_BUDGET', 'projects.issue.pendingResetBudgetModifyBlocked', () => {
    setResetBudgetMaxRuns(value)
    budgetRequest.invalidateIfChanged((frozen) => frozen.maxRuns === value)
  })

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

  // 保存 Spec
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

  const handleTransition = async (toState: string) => {
    if (!issue) return
    const requestKey = createUuid()
    await executeIssueAction(
      'TRANSITION',
      requestKey,
      { toState },
      (expectedVersion) => api.transitionIssue(issue.id, { expectedVersion, requestKey, toState }),
      () => {},
    )
  }

  const handleRecover = async () => {
    if (!issue) return
    const requestKey = createUuid()
    await executeIssueAction(
      'RECOVER',
      requestKey,
      {},
      (expectedVersion) => api.recoverIssue(issue.id, { expectedVersion, requestKey }),
      () => {},
    )
  }

  const handleReopen = async () => {
    if (!issue) return
    const requestKey = createUuid()
    await executeIssueAction(
      'REOPEN',
      requestKey,
      {},
      (expectedVersion) => api.reopenIssue(issue.id, { expectedVersion, requestKey }),
      () => {},
    )
  }

  const handleBlockSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) return
    const reason = blockReason.trim()
    if (!reason) {
      setActionError(t('projects.issue.blockReasonRequired'))
      return
    }
    const requestKey = blockRequest.requestKey
    blockRequest.freeze({ reason })
    await executeIssueAction(
      'BLOCK',
      requestKey,
      { reason },
      (expectedVersion) => api.blockIssue(issue.id, { expectedVersion, requestKey, reason }),
      resetBlockForm,
    )
  }

  const handleResolveUnknownSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) return
    const verification = verificationInput.trim()
    if (!verification) {
      setActionError(t('projects.issue.verificationRequired'))
      return
    }
    const requestKey = resolveUnknownRequest.requestKey
    resolveUnknownRequest.freeze({ verification })
    await executeIssueAction(
      'RESOLVE_UNKNOWN',
      requestKey,
      { verification },
      (expectedVersion) => api.resolveUnknown(issue.id, { expectedVersion, requestKey, verification }),
      resetResolveUnknownForm,
    )
  }

  const handleStopSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) return
    const detailText = stopDetail.trim() || null
    const requestKey = stopRequest.requestKey
    stopRequest.freeze({ detail: detailText })
    await executeIssueAction(
      'STOP',
      requestKey,
      { detail: detailText },
      (expectedVersion) => api.stopIssue(issue.id, { expectedVersion, requestKey, detail: detailText }),
      resetStopForm,
    )
  }

  const handleResetBudgetSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) return
    if (!resetBudgetState) {
      setActionError(t('projects.issue.resetBudgetStateRequired'))
      return
    }
    const maxRuns = parseMaxRuns(String(resetBudgetMaxRuns))
    if (maxRuns === null) {
      setActionError(t('projects.edit.maxRunsInvalid', { max: MAX_RUNS }))
      return
    }
    const state = resetBudgetState
    const requestKey = budgetRequest.requestKey
    budgetRequest.freeze({ state, maxRuns })
    await executeIssueAction(
      'RESET_BUDGET',
      requestKey,
      { state, maxRuns },
      (expectedVersion) => api.resetStageBudget(issue.id, { expectedVersion, requestKey, state, maxRuns }),
      resetBudgetForm,
    )
  }

  const handleAppendActivity = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!issue) return
    const body = activityBody.trim()
    if (!body) {
      setActionError(t('projects.issue.contentEmpty'))
      return
    }
    const kind = activityKind
    const requestKey = activityRequest.requestKey
    activityRequest.freeze({ kind, body })
    await executeIssueAction(
      'ACTIVITY',
      requestKey,
      { kind, body },
      (expectedVersion) => api.appendIssueActivity(issue.id, { expectedVersion, requestKey, kind, body }),
      resetActivityForm,
    )
  }

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
            <span className="issue-number" style={{ fontSize: 'var(--font-size-md)' }}>
              #{issue?.number || '...'}
            </span>
            <Badge tone="info" mono>{issue?.state || 'INIT'}</Badge>

            {isBlocked && (
              <Badge tone="danger" title={issue?.blockReason || t('projects.state.blocked')}>
                <AlertCircle size={12} aria-hidden="true" />
                BLOCKED
              </Badge>
            )}
            {isUnknown && (
              <Badge tone="info" title={t('projects.issue.pendingVerifyExternalSideEffects')}>
                <ShieldAlert size={12} aria-hidden="true" />
                UNKNOWN
              </Badge>
            )}
            {issue?.pauseReason === 'USER' && (
              <Badge tone="warning">PAUSED</Badge>
            )}
            {issue?.pauseReason === 'ERROR' && (
              <Badge tone="danger">ERROR</Badge>
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

        <IssueAlerts
          storageLoadError={runner.storageLoadError}
          corruptActionError={runner.corruptActionError}
          pendingUnknownAction={runner.pendingUnknownAction}
          conflictDetail={conflictDetail}
          displayError={displayError}
          isActionPending={isActionPending}
          onConfirmOverwrite={
            isEditingSpec && detail?.issue && detail.issue.version !== specVersion
              ? () => {
                  if (detail?.issue) {
                    setSpecVersion(detail.issue.version)
                    setConflictDetail(null)
                  }
                }
              : null
          }
          confirmOverwriteDisabled={isWriteBlocked}
          onDiscardCorruptAction={runner.discardCorruptAction}
          onRetryPendingAction={() => void runner.retryPendingAction()}
          onDiscardPendingAction={runner.discardPendingAction}
          onReloadFreshData={() => void handleReloadFreshData()}
        />

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
                    <IssueSpecView
                      issue={issue}
                      editDisabled={isWriteBlocked}
                      onEdit={() => setIsEditingSpec(true)}
                    />
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

                      <span style={{ fontSize: 'var(--font-size-xs)', color: 'var(--fg-muted)' }}>
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
                  <IssueActivityTimeline activities={detail.activities ?? []} />
                </div>
              )}

              {/* TAB 3: Run 执行事实与区间报告 */}
              {activeTab === 'runs' && (
                <div className="tab-pane">
                  <IssueRunsPane runs={detail.runs ?? []} />
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
                      style={{ fontSize: 'var(--font-size-sm)' }}
                    >
                      <Coins size={14} aria-hidden="true" />
                      <span>{t('projects.issue.resetStageBudgetAction')}</span>
                    </Button>
                  </div>

                  <IssueStageBudgetList stageBudgets={detail.stageBudgets ?? []} />
                </div>
              )}

              {/* TAB 5: Agent 线程绑定 (AgentThreads) */}
              {activeTab === 'agentThreads' && (
                <div className="tab-pane">
                  <IssueAgentThreadsPane agentThreads={detail.agentThreads ?? []} onOpenThread={onOpenThread} />
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
                      style={{ fontSize: 'var(--font-size-sm)' }}
                    >
                      <Upload size={14} aria-hidden="true" />
                      <span>{isUploadingEvidence ? t('projects.issue.uploading') : t('projects.issue.uploadEvidenceAction')}</span>
                    </Button>
                  </div>

                  {allEvidences.length === 0 ? (
                    <EmptyState title={t('projects.issue.emptyEvidence')} />
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

        {isBlockModalOpen && (
          <TextActionDialog
            title={t('projects.issue.blockDialogTitle')}
            fieldId="issue-block-reason"
            fieldLabel={t('projects.issue.blockReasonFieldLabel')}
            required
            rows={3}
            value={blockReason}
            placeholder={t('projects.issue.blockReasonPlaceholder')}
            onChange={handleBlockReasonChange}
            submitLabel={t('projects.issue.confirmBlock')}
            pendingLabel={t('projects.issue.submitting')}
            danger
            isPending={isActionPending}
            isBlocked={isWriteBlocked}
            onSubmit={handleBlockSubmit}
            onClose={() => setIsBlockModalOpen(false)}
          />
        )}

        {isResolveUnknownOpen && (
          <TextActionDialog
            title={t('projects.issue.resolveUnknownDialogTitle')}
            headerIcon={<ShieldAlert size={16} className="text-danger" aria-hidden="true" />}
            description={t('projects.issue.resolveUnknownDesc')}
            fieldId="issue-verification"
            fieldLabel={t('projects.issue.verificationFieldLabel')}
            required
            rows={4}
            value={verificationInput}
            placeholder={t('projects.issue.verificationPlaceholder')}
            onChange={handleVerificationInputChange}
            submitLabel={t('projects.issue.confirmResolveUnknown')}
            pendingLabel={t('projects.issue.resolving')}
            isPending={isActionPending}
            isBlocked={isWriteBlocked}
            onSubmit={handleResolveUnknownSubmit}
            onClose={() => setIsResolveUnknownOpen(false)}
          />
        )}

        {isStopModalOpen && (
          <TextActionDialog
            title={t('projects.issue.stopDialogTitle')}
            fieldId="issue-stop-detail"
            fieldLabel={t('projects.issue.stopDetailFieldLabel')}
            rows={3}
            value={stopDetail}
            placeholder={t('projects.issue.stopDetailPlaceholder')}
            onChange={handleStopDetailChange}
            submitLabel={t('projects.issue.confirmStop')}
            pendingLabel={t('projects.issue.stopping')}
            danger
            isPending={isActionPending}
            isBlocked={isWriteBlocked}
            onSubmit={handleStopSubmit}
            onClose={() => setIsStopModalOpen(false)}
          />
        )}

        {isResetBudgetOpen && (
          <ResetBudgetDialog
            state={resetBudgetState}
            maxRuns={resetBudgetMaxRuns}
            onStateChange={handleBudgetStateChange}
            onMaxRunsChange={handleBudgetMaxRunsChange}
            isPending={isActionPending}
            isBlocked={isWriteBlocked}
            onSubmit={handleResetBudgetSubmit}
            onClose={() => setIsResetBudgetOpen(false)}
          />
        )}

        {isDeleteModalOpen && (
          <DeleteIssueDialog
            issueNumber={issue?.number ?? ''}
            isPending={isActionPending}
            isBlocked={isWriteBlocked}
            onConfirm={handleDeleteIssue}
            onClose={() => setIsDeleteModalOpen(false)}
          />
        )}
    </Dialog>
  )
}
