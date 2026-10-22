import { useEffect, useRef, useState } from 'react'
import type { QueryClient, QueryKey } from '@tanstack/react-query'
import { isConflictError } from '@/shared/api/client'
import { useI18n } from '@/shared/i18n'
import {
  clearPendingAction,
  isNetworkUnknownError,
  loadPendingAction,
  storePendingAction,
  type IssueActionKind,
  type LoadPendingActionResult,
  type PendingIssueAction,
} from '../../pending-action-sidecar'
import type { ProjectsApi } from '../../projects-api'
import type { IssueDetailDTO } from '@/shared/api/generated/contracts'

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
  const [actionError, setActionError] = useState<string | null>(null)
  const [conflictDetail, setConflictDetail] = useState<string | null>(null)
  const [isActionPending, setIsActionPending] = useState(false)
  const isActionPendingRef = useRef(false)
  const [pendingActionResult, setPendingActionResult] = useState<LoadPendingActionResult>(
    () => safeLoadPendingAction(issueId),
  )

  // 挂载/打开/issueId 变化时同步加载持久化侧车未决操作或异常状态
  useEffect(() => {
    if (!issueId || !isOpen) {
      setPendingActionResult({ type: 'NONE' })
      return
    }
    setPendingActionResult(safeLoadPendingAction(issueId))
  }, [issueId, isOpen])

  // 关闭时清理执行状态与提示 (I09)
  useEffect(() => {
    if (!isOpen) {
      isActionPendingRef.current = false
      setActionError(null)
      setConflictDetail(null)
    }
  }, [isOpen])

  const pendingUnknownAction = pendingActionResult.type === 'VALID' ? pendingActionResult.action : null
  const corruptActionError = pendingActionResult.type === 'CORRUPT' ? pendingActionResult.error : null
  const storageLoadError = pendingActionResult.type === 'STORAGE_ERROR' ? pendingActionResult.error : null
  const isWriteBlocked = isActionPending
    || Boolean(pendingUnknownAction || pendingActionResult.type === 'CORRUPT' || storageLoadError)

  const settle = async () => {
    await queryClient.invalidateQueries({ queryKey: issueQueryKey })
    notifyUpdated()
  }

  /** 服务端对某个请求身份给出确定答复（409 或其它明确业务错误）：清理侧车，保留草稿。 */
  const rejectIdentity = (requestKey: string, err: unknown, conflictNotice: string): boolean => {
    if (!issue) {
      return false
    }
    if (isConflictError(err)) {
      clearPendingAction(issue.id, requestKey)
      setPendingActionResult({ type: 'NONE' })
      setConflictDetail(conflictNotice)
      return true
    }
    if (!isNetworkUnknownError(err)) {
      clearPendingAction(issue.id, requestKey)
      setPendingActionResult({ type: 'NONE' })
      setActionError(err instanceof Error ? err.message : t('projects.issue.actionFailed'))
      return true
    }
    return false
  }

  const executeIssueAction = async (
    kind: IssueActionKind,
    requestKey: string,
    payload: Record<string, unknown>,
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
      await runApi(issue.version)
      // 2. 异步成功：只能清自己的 identity！
      clearPendingAction(issue.id, requestKey)
      setPendingActionResult({ type: 'NONE' })
      onExplicitSuccess()
      await settle()
    } catch (err) {
      if (!rejectIdentity(requestKey, err, t('projects.issue.actionConflictNotice'))) {
        // 未知网络错误（408, 429, 5xx, 网络中断）：侧车保留并在 UI 标记 unknown！
        setPendingActionResult({ type: 'VALID', action: pending })
        setActionError(err instanceof Error ? err.message : t('projects.issue.networkUnknownError'))
      }
    } finally {
      isActionPendingRef.current = false
      setIsActionPending(false)
    }
  }

  /** 精确重试未决未知操作：版本与 payload 严格取自冻结的侧车记录，不因刷新而改变。 */
  const retryPendingAction = async () => {
    if (!pendingUnknownAction || !issue || isActionPendingRef.current) return
    isActionPendingRef.current = true
    setIsActionPending(true)
    setActionError(null)
    setConflictDetail(null)

    const { kind, requestKey, expectedVersion, payload } = pendingUnknownAction
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
          await api.recoverIssue(issue.id, { expectedVersion, requestKey })
          break
        case 'REOPEN':
          await api.reopenIssue(issue.id, { expectedVersion, requestKey })
          break
        case 'BLOCK':
          await api.blockIssue(issue.id, {
            expectedVersion,
            requestKey,
            reason: (payload as { reason: string }).reason,
          })
          break
        case 'RESOLVE_UNKNOWN':
          await api.resolveUnknown(issue.id, {
            expectedVersion,
            requestKey,
            verification: (payload as { verification: string }).verification,
          })
          break
        case 'STOP':
          await api.stopIssue(issue.id, {
            expectedVersion,
            requestKey,
            detail: (payload as { detail: string | null }).detail,
          })
          break
        case 'RESET_BUDGET':
          await api.resetStageBudget(issue.id, {
            expectedVersion,
            requestKey,
            state: (payload as { state: string }).state,
            maxRuns: (payload as { maxRuns: number }).maxRuns,
          })
          break
        case 'ACTIVITY':
          await api.appendIssueActivity(issue.id, {
            expectedVersion,
            requestKey,
            kind: (payload as { kind: 'COMMENT' | 'INSTRUCTION' }).kind,
            body: (payload as { body: string }).body,
          })
          break
      }
      onRetrySucceeded(kind)
      clearPendingAction(issue.id, requestKey)
      setPendingActionResult({ type: 'NONE' })
      await settle()
    } catch (err) {
      if (!rejectIdentity(requestKey, err, t('projects.issue.retryConflictNotice'))) {
        setActionError(err instanceof Error ? err.message : t('projects.issue.retryStillUnknownError'))
      }
    } finally {
      isActionPendingRef.current = false
      setIsActionPending(false)
    }
  }

  /** 明确放弃未决操作（清理侧车与 unknown 状态）。 */
  const discardPendingAction = () => {
    if (!issueId || !pendingUnknownAction || isActionPendingRef.current) return
    clearPendingAction(issueId, pendingUnknownAction.requestKey)
    setPendingActionResult({ type: 'NONE' })
  }

  /** 放弃损坏的挂起记录并解锁界面。 */
  const discardCorruptAction = () => {
    if (!issueId || isActionPendingRef.current) return
    clearPendingAction(issueId, undefined, undefined, true)
    setPendingActionResult({ type: 'NONE' })
  }

  return {
    actionError,
    setActionError,
    conflictDetail,
    setConflictDetail,
    isActionPending,
    setIsActionPending,
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
