import { useEffect, useState } from 'react'
import { useI18n } from '@/shared/i18n'
import { createUuid } from '@/shared/lib/uuid'
import type { IssueDetailDTO } from '@/shared/api/generated/contracts'
import type { IssueActionKind, PendingIssueAction } from '../../pending-action-sidecar'
import type { ProjectsApi } from '../../projects-api'

type Issue = IssueDetailDTO['issue']

interface ResumeConfirmation {
  issueId: string
  version: string
  pauseDetail: string | null
}

interface IssueLifecycleActionsInput {
  isOpen: boolean
  issueId: string | null
  issue: Issue | undefined
  api: ProjectsApi
  isWriteBlocked: boolean
  isActionPendingRef: React.RefObject<boolean>
  setIsActionPending: (pending: boolean) => void
  setActionError: (message: string | null) => void
  setConflictDetail: (message: string | null) => void
  notifyUpdated: () => void
  close: () => void
  executeIssueAction: (
    kind: IssueActionKind,
    requestKey: string,
    payload: PendingIssueAction['payload'],
    runApi: (expectedVersion: string) => Promise<unknown>,
    onExplicitSuccess: () => void,
  ) => Promise<void>
}

/**
 * Issue 的生命周期写操作：状态流转、阻塞恢复、重开、继续与删除。
 *
 * <p>USER 暂停可直接继续；ERROR 暂停先核查副作用并弹确认框，确认时重新校验版本与暂停原因，
 * 期间数据已变化的只提示冲突、不发送 resume。
 */
export function useIssueLifecycleActions({
  isOpen,
  issueId,
  issue,
  api,
  isWriteBlocked,
  isActionPendingRef,
  setIsActionPending,
  setActionError,
  setConflictDetail,
  notifyUpdated,
  close,
  executeIssueAction,
}: IssueLifecycleActionsInput) {
  const { t } = useI18n()
  const [resumeConfirmation, setResumeConfirmation] = useState<ResumeConfirmation | null>(null)
  const [isDeleteOpen, setIsDeleteOpen] = useState(false)

  // 关闭时收起尚未确认的子对话框，避免重新打开时带着上一次的确认。
  useEffect(() => {
    if (!isOpen) {
      setResumeConfirmation(null)
      setIsDeleteOpen(false)
    }
  }, [isOpen])

  const transition = async (toState: string) => {
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

  const recover = async () => {
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

  const reopen = async () => {
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

  const resume = async () => {
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

  const requestResume = () => {
    if (!issue) return
    if (issue.pauseReason === 'ERROR') {
      setResumeConfirmation({
        issueId: issue.id,
        version: issue.version,
        pauseDetail: issue.pauseDetail,
      })
      return
    }
    void resume()
  }

  const confirmResume = () => {
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
    void resume()
  }

  const deleteIssue = async () => {
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
      setIsDeleteOpen(false)
      close()
      notifyUpdated()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : t('projects.issue.deleteFailed'))
    } finally {
      isActionPendingRef.current = false
      setIsActionPending(false)
    }
  }

  return {
    transition,
    recover,
    reopen,
    requestResume,
    confirmResume,
    resumeConfirmation,
    closeResumeConfirmation: () => setResumeConfirmation(null),
    deleteIssue,
    isDeleteOpen,
    openDelete: () => setIsDeleteOpen(true),
    closeDelete: () => setIsDeleteOpen(false),
  }
}
