import { useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useI18n } from '@/shared/i18n'
import { createUuid } from '@/shared/lib/uuid'
import { queryKeys } from '@/shared/lib/query-keys'
import {
  clearPendingAction,
  isNetworkUnknownError,
  isPayloadEqual,
  loadPendingAction,
  storePendingAction,
  type PendingIssueAction,
} from './pending-action-sidecar'
import type { IssueDetailIntent } from './types'
import type { ProjectsApi } from './projects-api'

type BoardIssueActionKind = Extract<
  PendingIssueAction['kind'],
  'TRANSITION' | 'RECOVER' | 'REOPEN'
>

interface UseBoardIssueActionsInput {
  projectId: string
  api: ProjectsApi
  /** 打开详情；intent 决定详情打开后是否直接展开某个表单（侧车异常时为不指定）。 */
  onSelectIssue: (issueId: string, intent?: IssueDetailIntent) => void
}

/**
 * 看板上的 Issue 写操作（流转、恢复、重开）：发送前把请求身份落盘到侧车，失败即不发送。
 *
 * <p>同一 issue 同时只允许一个在途动作；已存在同 kind 同 payload 的未决记录时按冻结身份精确重试，
 * 存在不同记录时阻止覆盖并引导用户到详情页处理。只有网络结果未知时才保留侧车。
 */
export function useBoardIssueActions({ projectId, api, onSelectIssue }: UseBoardIssueActionsInput) {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const [actionError, setActionError] = useState<string | null>(null)
  const inflightIssuesRef = useRef<Set<string>>(new Set())

  const invalidateSnapshot = async () => {
    await queryClient.invalidateQueries({
      queryKey: queryKeys.projects.snapshot(projectId),
      exact: true,
    })
  }

  const invalidateIssue = async (issueId: string) => {
    await queryClient.invalidateQueries({
      queryKey: queryKeys.projects.issue(projectId, issueId),
    })
  }

  /** 发送并收敛一次已落盘的请求；明确成功清理侧车，未知网络结果保留。 */
  const sendWithSidecar = async (
    issueId: string,
    requestKey: string,
    expectedVersion: string,
    runApi: (requestKey: string, expectedVersion: string) => Promise<unknown>,
    fallbackError: string,
  ): Promise<boolean> => {
    inflightIssuesRef.current.add(issueId)
    try {
      setActionError(null)
      await runApi(requestKey, expectedVersion)
      clearPendingAction(issueId, requestKey)
      await invalidateSnapshot()
      await invalidateIssue(issueId)
      return true
    } catch (err) {
      if (!isNetworkUnknownError(err)) {
        clearPendingAction(issueId, requestKey)
      }
      setActionError(err instanceof Error ? err.message : fallbackError)
      return false
    } finally {
      inflightIssuesRef.current.delete(issueId)
    }
  }

  const execute = async (
    issueId: string,
    expectedVersion: string,
    action: PendingIssueAction,
    runApi: (requestKey: string, expectedVersion: string) => Promise<unknown>,
    failureLabel: string,
  ): Promise<boolean> => {
    // 阻断连点：同一 issue 若正在执行中，忽略重复触发。
    if (inflightIssuesRef.current.has(issueId)) {
      return false
    }

    const loadResult = loadPendingAction(issueId)
    if (loadResult.type === 'STORAGE_ERROR') {
      setActionError(t('projects.detail.storageUnavailable', { reason: loadResult.error }))
      return false
    }
    if (loadResult.type === 'CORRUPT') {
      setActionError(t('projects.detail.corruptPending'))
      onSelectIssue(issueId)
      return false
    }

    if (loadResult.type === 'VALID') {
      const existing = loadResult.action
      const isSameAction =
        existing.kind === action.kind && isPayloadEqual(existing.payload, action.payload)
      if (!isSameAction) {
        setActionError(t('projects.detail.pendingWriteBlocked'))
        onSelectIssue(issueId)
        return false
      }
      return sendWithSidecar(
        issueId,
        existing.requestKey,
        existing.expectedVersion,
        runApi,
        t('projects.detail.retryFailed', { label: failureLabel }),
      )
    }

    // 新请求：发送前落盘到侧车（fail closed）。
    try {
      storePendingAction(issueId, action)
    } catch (err) {
      setActionError(
        t('projects.detail.storePendingFailed', {
          reason: err instanceof Error ? err.message : String(err),
        }),
      )
      return false
    }

    return sendWithSidecar(
      issueId,
      action.requestKey,
      expectedVersion,
      runApi,
      t('projects.detail.actionFailed', { label: failureLabel }),
    )
  }

  const buildAction = (
    issueId: string,
    kind: BoardIssueActionKind,
    expectedVersion: string,
    payload: PendingIssueAction['payload'],
  ): PendingIssueAction => ({
    issueId,
    kind,
    requestKey: createUuid(),
    expectedVersion,
    payload,
    createdAt: new Date().toISOString(),
    isUnknown: true,
  } as PendingIssueAction)

  const transitionIssue = async (issueId: string, expectedVersion: string, toState: string) =>
    execute(
      issueId,
      expectedVersion,
      buildAction(issueId, 'TRANSITION', expectedVersion, { toState }),
      (requestKey, version) => api.transitionIssue(issueId, { expectedVersion: version, requestKey, toState }),
      t('projects.detail.action.transition'),
    )

  const recoverIssue = async (issueId: string, expectedVersion: string) =>
    execute(
      issueId,
      expectedVersion,
      buildAction(issueId, 'RECOVER', expectedVersion, {}),
      (requestKey, version) => api.recoverIssue(issueId, { expectedVersion: version, requestKey }),
      t('projects.detail.action.recover'),
    )

  const reopenIssue = async (issueId: string, expectedVersion: string) =>
    execute(
      issueId,
      expectedVersion,
      buildAction(issueId, 'REOPEN', expectedVersion, {}),
      (requestKey, version) => api.reopenIssue(issueId, { expectedVersion: version, requestKey }),
      t('projects.detail.action.reopen'),
    )

  /** 阻塞需要填写原因：看板把用户带到详情弹窗，并直接展开那里的原因表单。 */
  const openBlockDialog = (issueId: string, _expectedVersion: string) =>
    onSelectIssue(issueId, 'block')

  return {
    actionError,
    setActionError,
    transitionIssue,
    recoverIssue,
    reopenIssue,
    openBlockDialog,
  }
}
