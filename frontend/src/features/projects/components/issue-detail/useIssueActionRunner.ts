import { useEffect, useRef, useState } from 'react'
import type { QueryClient, QueryKey } from '@tanstack/react-query'
import { isConflictError } from '@/shared/api/client'
import { useI18n } from '@/shared/i18n'
import {
  clearPendingAction,
  isNetworkUnknownError,
  storePendingAction,
  type IssueActionKind,
  type PendingIssueAction,
} from '../../pending-action-sidecar'
import type { ProjectsApi } from '../../projects-api'
import type { IssueDetailDTO } from '@/shared/api/generated/contracts'
import { sendRetriedAction } from './sendRetriedAction'
import { usePendingActionSidecar } from './usePendingActionSidecar'

interface IssueWriteStateInput {
  isOpen: boolean
  queryClient: QueryClient
  issueQueryKey: QueryKey
  notifyUpdated: () => void
}

/**
 * 写操作的公共执行状态：错误/冲突提示、进行中标记与提交后的刷新。
 *
 * <p>`isActionPendingRef` 与 state 并存：同一事件循环内的重复点击读不到尚未提交的 state，
 * 需要一个同步可见的闸门。
 */
function useIssueWriteState({ isOpen, queryClient, issueQueryKey, notifyUpdated }: IssueWriteStateInput) {
  const [actionError, setActionError] = useState<string | null>(null)
  const [conflictDetail, setConflictDetail] = useState<string | null>(null)
  const [isActionPending, setIsActionPending] = useState(false)
  const isActionPendingRef = useRef(false)

  // 关闭时清理执行状态与提示
  useEffect(() => {
    if (!isOpen) {
      isActionPendingRef.current = false
      setActionError(null)
      setConflictDetail(null)
    }
  }, [isOpen])

  const settle = async () => {
    await queryClient.invalidateQueries({ queryKey: issueQueryKey })
    notifyUpdated()
  }

  /** 进入一次写入：占用闸门并清掉上一次的提示。 */
  const beginWrite = () => {
    isActionPendingRef.current = true
    setIsActionPending(true)
    setActionError(null)
    setConflictDetail(null)
  }

  const endWrite = () => {
    isActionPendingRef.current = false
    setIsActionPending(false)
  }

  return {
    actionError,
    setActionError,
    conflictDetail,
    setConflictDetail,
    isActionPending,
    setIsActionPending,
    isActionPendingRef,
    settle,
    beginWrite,
    endWrite,
  }
}

interface IssueActionRunnerInput {
  isOpen: boolean
  issueId: string | null
  issue: IssueDetailDTO['issue'] | undefined
  api: ProjectsApi
  queryClient: QueryClient
  issueQueryKey: QueryKey
  notifyUpdated: () => void
  /** 精确重试成功后按操作类型清理对应表单。 */
  onRetrySucceeded: (kind: IssueActionKind) => void
}

/**
 * Issue 写操作的执行器：同一 Issue 同时只允许一个写操作，发送前把请求身份落盘到侧车（失败即不发送），明确成功或明确拒绝才清理自己的身份；网络结果未知时保留侧车并锁定写入，只能用冻结的版本与 payload 精确重试或显式放弃。
 */
export function useIssueActionRunner({
  isOpen,
  issueId,
  issue,
  api,
  queryClient,
  issueQueryKey,
  notifyUpdated,
  onRetrySucceeded,
}: IssueActionRunnerInput) {
  const { t } = useI18n()
  const write = useIssueWriteState({ isOpen, queryClient, issueQueryKey, notifyUpdated })
  const {
    setActionError,
    setConflictDetail,
    isActionPendingRef,
    settle,
    beginWrite,
    endWrite,
  } = write
  const sidecar = usePendingActionSidecar({ isOpen, issueId })
  const { pendingUnknownAction, corruptActionError, storageLoadError } = sidecar

  const isWriteBlocked = write.isActionPending
    || Boolean(pendingUnknownAction || sidecar.pendingActionResult.type === 'CORRUPT' || storageLoadError)

  /** 服务端对某个请求身份给出确定答复（409 或其它明确业务错误）：清理侧车，保留草稿。 */
  const rejectIdentity = (requestKey: string, err: unknown, conflictNotice: string): boolean => {
    if (!issue) {
      return false
    }
    if (isConflictError(err)) {
      clearPendingAction(issue.id, requestKey)
      sidecar.setPendingActionResult({ type: 'NONE' })
      setConflictDetail(conflictNotice)
      return true
    }
    if (!isNetworkUnknownError(err)) {
      clearPendingAction(issue.id, requestKey)
      sidecar.setPendingActionResult({ type: 'NONE' })
      setActionError(err instanceof Error ? err.message : t('projects.issue.actionFailed'))
      return true
    }
    return false
  }

  /** 先落盘再发送：侧车写入失败时不发请求，避免出现无法重试的未知结果。 */
  const storeIdentity = (pending: PendingIssueAction): boolean => {
    try {
      storePendingAction(pending.issueId, pending)
      return true
    } catch (err) {
      setActionError(t('projects.detail.storePendingFailed', {
        reason: err instanceof Error ? err.message : String(err),
      }))
      return false
    }
  }

  const executeIssueAction = async (
    kind: IssueActionKind,
    requestKey: string,
    payload: PendingIssueAction['payload'],
    runApi: (expectedVersion: string) => Promise<unknown>,
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
      expectedVersion: issue.version,
      payload,
      createdAt: new Date().toISOString(),
      isUnknown: true,
    } as PendingIssueAction

    if (!storeIdentity(pending)) {
      return
    }

    beginWrite()
    try {
      await runApi(issue.version)
      // 异步成功只能清自己的 identity，不能清掉期间出现的其它未决记录。
      clearPendingAction(issue.id, requestKey)
      sidecar.setPendingActionResult({ type: 'NONE' })
      onExplicitSuccess()
      await settle()
    } catch (err) {
      if (!rejectIdentity(requestKey, err, t('projects.issue.actionConflictNotice'))) {
        // 未知网络错误（408, 429, 5xx, 网络中断）：侧车保留并在 UI 标记 unknown。
        sidecar.setPendingActionResult({ type: 'VALID', action: pending })
        setActionError(err instanceof Error ? err.message : t('projects.issue.networkUnknownError'))
      }
    } finally {
      endWrite()
    }
  }

  /** 精确重试未决未知操作：版本与 payload 严格取自冻结的侧车记录，不因刷新而改变。 */
  const retryPendingAction = async () => {
    if (!pendingUnknownAction || !issue || isActionPendingRef.current) return
    beginWrite()
    const { kind, requestKey } = pendingUnknownAction
    try {
      await sendRetriedAction(api, issue.id, pendingUnknownAction)
      onRetrySucceeded(kind)
      clearPendingAction(issue.id, requestKey)
      sidecar.setPendingActionResult({ type: 'NONE' })
      await settle()
    } catch (err) {
      if (!rejectIdentity(requestKey, err, t('projects.issue.retryConflictNotice'))) {
        setActionError(err instanceof Error ? err.message : t('projects.issue.retryStillUnknownError'))
      }
    } finally {
      endWrite()
    }
  }

  /** 明确放弃未决操作（清理侧车与 unknown 状态）。 */
  const discardPendingAction = () => {
    if (!issueId || !pendingUnknownAction || isActionPendingRef.current) return
    clearPendingAction(issueId, pendingUnknownAction.requestKey)
    sidecar.setPendingActionResult({ type: 'NONE' })
  }

  /** 放弃损坏的挂起记录并解锁界面。 */
  const discardCorruptAction = () => {
    if (!issueId || isActionPendingRef.current) return
    clearPendingAction(issueId, undefined, undefined, true)
    sidecar.setPendingActionResult({ type: 'NONE' })
  }

  return {
    actionError: write.actionError,
    setActionError,
    conflictDetail: write.conflictDetail,
    setConflictDetail,
    isActionPending: write.isActionPending,
    setIsActionPending: write.setIsActionPending,
    isActionPendingRef,
    pendingUnknownAction,
    corruptActionError,
    storageLoadError,
    isWriteBlocked,
    executeIssueAction,
    retryPendingAction,
    discardPendingAction,
    discardCorruptAction,
  }
}
