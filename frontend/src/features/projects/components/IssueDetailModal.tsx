import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useRef } from 'react'
import type { HashFile } from '@/shared/lib/upload-file'
import { storageService as defaultStorageService, type StorageService } from '@/shared/api/storage-service'
import { useI18n } from '@/shared/i18n'
import { queryKeys } from '@/shared/lib/query-keys'
import { Button } from '@/shared/ui/controls/Button'
import { Dialog } from '@/shared/ui/overlays/Dialog'
import type { ProjectsApi } from '../projects-api'
import { projectsApi } from '../projects-api'
import { IssueActionBar } from './issue-detail/IssueActionBar'
import { IssueActionDialogs } from './issue-detail/IssueActionDialogsCluster'
import { IssueAlerts } from './issue-detail/IssueAlerts'
import { IssueDetailHeader } from './issue-detail/IssueDetailHeader'
import { IssueDetailTabPanels } from './issue-detail/IssueDetailTabPanels'
import { IssueDetailTabs } from './issue-detail/IssueDetailTabs'
import { useIssueDetail } from './issue-detail/useIssueDetail'
import { toStateNames } from '../issue-labels'
import './issue-detail-alerts.css'
import type { IssueDetailIntent } from '../types'
import type { ProjectWorkflowDTO } from '@/shared/api/generated/contracts'

export interface IssueDetailModalProps {
  isOpen: boolean
  issueId: string | null
  projectId?: string
  workflow?: ProjectWorkflowDTO
  /** 打开意图：卡片上的“阻塞”动作要求详情直接展开阻塞原因表单。 */
  initialAction?: IssueDetailIntent | null
  onClose: () => void
  onUpdated?: () => void
  onIssueUpdated?: () => void
  api?: ProjectsApi
  storageService?: StorageService
  hashFile?: HashFile
  onOpenThread?: (threadId: string) => void
}

/**
 * Issue 详情：Spec 编辑、活动、Run、阶段预算、Agent 线程与证据六个 tab，以及流转、阻塞/恢复、继续、终止、重开、预算重置与删除等写操作。
 *
 * <p>本组件只做编排：数据与写操作装配在 {@link useIssueDetail}，界面片段交给同目录的展示组件。
 */
export function IssueDetailModal({
  isOpen,
  issueId,
  projectId = '',
  workflow,
  initialAction = null,
  onClose,
  onUpdated,
  onIssueUpdated,
  api = projectsApi,
  storageService = defaultStorageService,
  hashFile,
  onOpenThread,
}: IssueDetailModalProps) {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const stateNames = useMemo(() => toStateNames(workflow?.states), [workflow])
  const state = useIssueDetail({
    isOpen,
    issueId,
    projectId,
    api,
    storageService,
    hashFile,
    onClose,
    notifyUpdated: () => {
      onUpdated?.()
      onIssueUpdated?.()
    },
    queryClient,
    issueQueryKey: queryKeys.projects.issue(projectId, issueId ?? ''),
  })

  // 打开意图只消费一次：用户关掉表单后不再自动重开，关闭详情后同一意图仍可再次生效。
  const openBlockForm = state.forms.block.open
  const appliedIntentRef = useRef<IssueDetailIntent | null>(null)
  useEffect(() => {
    if (!isOpen) {
      appliedIntentRef.current = null
      return
    }
    if (!initialAction || appliedIntentRef.current === initialAction) {
      return
    }
    appliedIntentRef.current = initialAction
    openBlockForm()
  }, [isOpen, initialAction, openBlockForm])

  if (!isOpen || !issueId) {
    return null
  }

  const { detail, issue, isLoading, activityFeed, activeTab, setActiveTab, displayedError, runner, spec, forms, lifecycle, evidence } = state
  const isBlocked = issue?.state === 'BLOCKED' || Boolean(issue?.blockedFromState)
  const isDone = issue?.state === 'DONE'
  const availableNextStates = workflow?.states.find((s) => s.state === issue?.state)?.next ?? []

  return (
    <Dialog
      className="issue-detail-modal-card modal-touch-target"
      ariaLabel={t('projects.issue.modalAriaLabel', { number: issue?.number || '' })}
      onClose={onClose}
      header={
        <IssueDetailHeader
          issueNumber={issue?.number || '...'}
          state={issue?.state ?? null}
          stateNames={stateNames}
          isBlocked={isBlocked}
          blockedReason={issue?.blockReason ?? null}
          pauseReason={issue?.pauseReason ?? null}
          deleteDisabled={runner.isWriteBlocked}
          onDelete={lifecycle.openDelete}
          onClose={onClose}
        />
      }
    >
      <IssueActionBar
        isBlocked={isBlocked}
        isDone={isDone}
        isPaused={Boolean(issue?.pauseReason)}
        isWriteBlocked={runner.isWriteBlocked}
        stateNames={stateNames}
        availableNextStates={availableNextStates}
        onResume={lifecycle.requestResume}
        onTransition={(toState) => void lifecycle.transition(toState)}
        onRecover={() => void lifecycle.recover()}
        onOpenBlock={forms.block.open}
        onOpenStop={forms.stop.open}
        onReopen={() => void lifecycle.reopen()}
      />

      <IssueAlerts
        storageLoadError={runner.storageLoadError}
        corruptActionError={runner.corruptActionError}
        pendingUnknownAction={runner.pendingUnknownAction}
        conflictDetail={runner.conflictDetail}
        displayError={displayedError}
        isActionPending={runner.isActionPending}
        onConfirmOverwrite={spec.confirmOverwrite}
        confirmOverwriteDisabled={runner.isWriteBlocked}
        onDiscardCorruptAction={runner.discardCorruptAction}
        onRetryPendingAction={() => void runner.retryPendingAction()}
        onDiscardPendingAction={runner.discardPendingAction}
        onReloadFreshData={() => void spec.reloadFreshData()}
      />

      <IssueDetailTabs
        activeTab={activeTab}
        onChange={setActiveTab}
        counts={{
          activities: activityFeed.activities.length,
          activityHasMore: activityFeed.hasMore,
          runs: detail?.runs?.length ?? 0,
          stageBudgets: detail?.stageBudgets?.length ?? 0,
          agentThreads: detail?.agentThreads?.length ?? 0,
          evidence: evidence.evidenceCount,
        }}
      >
        <IssueDetailTabPanels
          activeTab={activeTab}
          detail={detail}
          issue={issue}
          stateNames={stateNames}
          isLoading={isLoading}
          isWriteBlocked={runner.isWriteBlocked}
          isActionPending={runner.isActionPending}
          spec={spec}
          activity={forms.activity}
          activityFeed={activityFeed}
          evidence={evidence}
          storageService={storageService}
          openResetBudget={() => forms.budget.open(detail?.stageBudgets[0]?.state)}
          onOpenThread={onOpenThread}
        />
      </IssueDetailTabs>

      <div className="modal-footer">
        {/* 打开详情时的初始焦点落在关闭动作上：头部第一个可聚焦控件是删除，不能作为落点。 */}
        <Button variant="ghost" data-autofocus onClick={onClose}>
          {t('projects.close')}
        </Button>
      </div>

      <IssueActionDialogs
        isActionPending={runner.isActionPending}
        isWriteBlocked={runner.isWriteBlocked}
        block={forms.block}
        stop={forms.stop}
        budget={forms.budget}
        resume={{
          isOpen: lifecycle.resumeConfirmation !== null,
          pauseDetail: lifecycle.resumeConfirmation?.pauseDetail ?? null,
          onConfirm: lifecycle.confirmResume,
          onClose: lifecycle.closeResumeConfirmation,
        }}
        deleteIssue={{
          isOpen: lifecycle.isDeleteOpen,
          issueNumber: issue?.number ?? '',
          onConfirm: lifecycle.deleteIssue,
          onClose: lifecycle.closeDelete,
        }}
      />
    </Dialog>
  )
}
