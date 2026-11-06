import { useEffect, useState } from 'react'
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
  Play,
  RotateCcw,
  Send,
  Square,
  Trash2,
  X,
} from 'lucide-react'
import type { HashFile } from '@/shared/lib/upload-file'
import { isConflictError } from '@/shared/api/client'
import {
  storageService as defaultStorageService,
  type StorageService,
} from '@/shared/api/storage-service'
import { useI18n, type TranslationKey } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { createUuid } from '@/shared/lib/uuid'
import { Button } from '@/shared/ui/controls/Button'
import { IconButton } from '@/shared/ui/controls/IconButton'
import { Select } from '@/shared/ui/controls/Select'
import { Tabs } from '@/shared/ui/controls/Tabs'
import { TextArea } from '@/shared/ui/controls/TextArea'
import { Badge } from '@/shared/ui/data/Badge'
import { StateBlock } from '@/shared/ui/feedback/StateBlock'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import type { IssueActionKind } from '../pending-action-sidecar'
import type { ProjectsApi } from '../projects-api'
import { projectsApi } from '../projects-api'
import { MAX_RUNS, parseMaxRuns } from '../workflow-draft'
import { DeleteIssueDialog, ResetBudgetDialog, ResumeIssueDialog, TextActionDialog } from './issue-detail/IssueActionDialogs'
import { IssueAlerts } from './issue-detail/IssueAlerts'
import { IssueEvidencePane } from './issue-detail/IssueEvidencePane'
import { IssueSpecPane } from './issue-detail/IssueSpecPane'
import {
  IssueActivityTimeline,
  IssueAgentThreadsPane,
  IssueRunsPane,
  IssueStageBudgetList,
} from './issue-detail/IssueTabPanes'
import { useIssueActionRunner } from './issue-detail/useIssueActionRunner'
import { useIssueEvidence } from './issue-detail/useIssueEvidence'
import { useKeyedRequest } from './issue-detail/useKeyedRequest'
import './issue-detail-alerts.css'
import {
  ISSUE_ACTIVITY_REQUEST_KIND_VALUES,
  type IssueActivityRequestKind,
  type ProjectWorkflowDTO,
} from '@/shared/api/generated/contracts'

const isIssueActivityRequestKind = (value: string): value is IssueActivityRequestKind =>
  ISSUE_ACTIVITY_REQUEST_KIND_VALUES.some((kind) => kind === value)

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
 * Issue 详情：Spec 编辑、活动、Run、阶段预算、Agent 线程与证据六个 tab，以及流转、阻塞/恢复、继续、终止、重开、预算重置与删除等写操作。
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

  const [activeTab, setActiveTab] = useState<TabKey>('spec')

  // 1. Spec 编辑草稿状态与 409 保护
  const [isEditingSpec, setIsEditingSpec] = useState(false)
  const [draftTitle, setDraftTitle] = useState('')
  const [draftDescription, setDraftDescription] = useState('')
  const [specVersion, setSpecVersion] = useState('0')

  // 2. 各写操作表单：值 + 幂等请求身份
  const [activityKind, setActivityKind] = useState<IssueActivityRequestKind>('COMMENT')
  const [activityBody, setActivityBody] = useState('')
  const activityRequest = useKeyedRequest<{ kind: IssueActivityRequestKind; body: string }>()
  const [isBlockModalOpen, setIsBlockModalOpen] = useState(false)
  const [blockReason, setBlockReason] = useState('')
  const blockRequest = useKeyedRequest<{ reason: string }>()
  const [isResetBudgetOpen, setIsResetBudgetOpen] = useState(false)
  const [resetBudgetState, setResetBudgetState] = useState('')
  const [resetBudgetMaxRuns, setResetBudgetMaxRuns] = useState<number>(3)
  const budgetRequest = useKeyedRequest<{ state: string; maxRuns: number }>()
  const [isStopModalOpen, setIsStopModalOpen] = useState(false)
  const [stopDetail, setStopDetail] = useState('')
  const stopRequest = useKeyedRequest<{ detail: string | null }>()
  const [resumeConfirmation, setResumeConfirmation] = useState<{
    issueId: string
    version: string
    pauseDetail: string | null
  } | null>(null)
  const [isDeleteModalOpen, setIsDeleteModalOpen] = useState(false)

  const resetBlockForm = () => {
    setIsBlockModalOpen(false)
    setBlockReason('')
    blockRequest.reset()
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

  // 3. Evidence：服务端事实 + 本地上传由 useIssueEvidence 统一持有
  const {
    evidences: allEvidences,
    isUploading: isUploadingEvidence,
    fileInputRef,
    handleFileSelected,
  } = useIssueEvidence({
    isOpen,
    issueId,
    projectId,
    issue: detail?.issue,
    api,
    storageService,
    hashFile,
    isWriteBlocked,
    isActionPendingRef,
    setActionError,
    issueQueryKey,
  })

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

  // 关闭时清理临时对话框与未提交草稿 (I09)
  useEffect(() => {
    if (!isOpen) {
      setIsEditingSpec(false)
      setIsBlockModalOpen(false)
      setIsResetBudgetOpen(false)
      setIsStopModalOpen(false)
      setResumeConfirmation(null)
      setIsDeleteModalOpen(false)
      setDraftTitle('')
      setDraftDescription('')
      setActivityBody('')
      setBlockReason('')
      setStopDetail('')
      activityRequest.reset()
      blockRequest.reset()
      stopRequest.reset()
      budgetRequest.reset()
    }
    // 只在开合时重置；各 reset 函数引用稳定。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen])

  /** 未决未知操作属于该表单时拒绝修改；否则更新值，偏离冻结 payload 即换新请求身份。 */
  const guardedChange = (blockedKind: IssueActionKind, blockedMessage: TranslationKey, apply: () => void) => {
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
  const handleActivityKindChange = (kind: IssueActivityRequestKind) => guardedChange('ACTIVITY', 'projects.issue.pendingActivityModifyBlocked', () => {
    setActivityKind(kind)
    activityRequest.invalidateIfChanged((frozen) => frozen.kind === kind)
  })
  const handleBlockReasonChange = (value: string) => guardedChange('BLOCK', 'projects.issue.pendingBlockModifyBlocked', () => {
    setBlockReason(value)
    blockRequest.invalidateIfChanged((frozen) => frozen.reason === value)
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

  const handleResume = async () => {
    if (!issue) return
    const requestKey = createUuid()
    await executeIssueAction(
      'RESUME',
      requestKey,
      {},
      (expectedVersion) => api.resumeIssue(issue.id, { expectedVersion, requestKey }),
      () => {},
    )
  }

  /** USER 暂停可直接继续；ERROR 暂停先核查副作用并确认。 */
  const handleResumeClick = () => {
    if (!issue) return
    if (issue.pauseReason === 'ERROR') {
      setResumeConfirmation({
        issueId: issue.id,
        version: issue.version,
        pauseDetail: issue.pauseDetail,
      })
      return
    }
    void handleResume()
  }

  const handleConfirmResume = () => {
    if (!resumeConfirmation) return
    // 先关闭确认框，使冲突、一般错误和未知结果的重试入口不被遮挡。
    setResumeConfirmation(null)
    if (
      !issue
      || issue.id !== issueId
      || issue.id !== resumeConfirmation.issueId
      || issue.version !== resumeConfirmation.version
      || issue.pauseReason !== 'ERROR'
    ) {
      setConflictDetail(t('projects.issue.resumeChangedNotice'))
      return
    }
    void handleResume()
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

  return (
    <Dialog
      className="issue-detail-modal-card modal-touch-target"
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
            {/* 暂停中：继续执行（USER 直接执行，ERROR 先确认） */}
            {issue?.pauseReason && (
              <Button
                size="compact"
                disabled={isWriteBlocked}
                onClick={handleResumeClick}
              >
                <Play size={13} aria-hidden="true" />
                <span>{t('projects.issue.resumeAction')}</span>
              </Button>
            )}

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
                <IssueSpecPane
                  issue={issue}
                  isEditing={isEditingSpec}
                  draftTitle={draftTitle}
                  draftDescription={draftDescription}
                  isWriteBlocked={isWriteBlocked}
                  isActionPending={isActionPending}
                  onStartEdit={() => setIsEditingSpec(true)}
                  onCancel={() => {
                    setIsEditingSpec(false)
                    setDraftTitle(issue.title)
                    setDraftDescription(issue.description)
                    setSpecVersion(issue.version)
                    setConflictDetail(null)
                  }}
                  onTitleChange={setDraftTitle}
                  onDescriptionChange={setDraftDescription}
                  onSubmit={handleSaveSpec}
                />
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
                        onChange={(value) => {
                          if (isIssueActivityRequestKind(value)) {
                            handleActivityKindChange(value)
                          }
                        }}
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
                <IssueEvidencePane
                  evidences={allEvidences}
                  storageService={storageService}
                  isUploading={isUploadingEvidence}
                  uploadDisabled={isWriteBlocked}
                  fileInputRef={fileInputRef}
                  onFileSelected={handleFileSelected}
                />
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

        {resumeConfirmation && (
          <ResumeIssueDialog
            pauseDetail={resumeConfirmation.pauseDetail}
            isBlocked={isWriteBlocked}
            onConfirm={handleConfirmResume}
            onClose={() => setResumeConfirmation(null)}
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
            isBlocked={isWriteBlocked}
            onConfirm={handleDeleteIssue}
            onClose={() => setIsDeleteModalOpen(false)}
          />
        )}
    </Dialog>
  )
}
