import { useEffect, useRef, useState } from 'react'
import { useApplicationEvents } from '@/shared/app-events'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { ThreadCommand } from '@/features/thread/runtime/thread-panel'
import type { ThreadProjection } from '@/features/thread/runtime/useThreadProjection'
import { useThreadApprovals } from '@/features/thread/runtime/thread-draft/useThreadApprovals'
import { useThreadStopOperation } from '@/features/thread/runtime/thread-draft/useThreadStopOperation'
import { useThreadDraftRecord } from '@/features/thread/runtime/thread-draft/useThreadDraftRecord'
import {
  clearBoundPendingMessage,
  loadBoundPendingMessage,
  saveBoundPendingMessage,
  sameBatchRequestIdentity,
  type BoundPendingMessage,
} from '@/features/thread/runtime/state/pending-request-store'
import { isDefiniteAcceptanceFailure } from '@/features/thread/runtime/agent-pane/agent-pane-pipeline'
import type { CommandBatchPlan } from '@/features/thread/command-batch-plan'
import {
  hasMessageContent,
  partsKey,
  slashQueryOf,
  trimMessageParts,
  type ComposerPart,
} from '@/features/thread/composer/composer-parts'
import { isConflictError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { requestThreadSnapshotRefresh } from '@/features/thread/runtime/state/thread-snapshot-cache'
import { translate, useI18n, type TranslationKey } from '@/shared/i18n'
import {
  presentConflict,
  type ConflictPresentation,
} from '@/shared/conflict/conflict-presenter'

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message.trim()) {
    return error.message
  }
  if (typeof error === 'string' && error.trim()) {
    return error
  }
  return translate('ai.runtime.action.requestFailed')
}

export function useAgentThreadController(
  projection: ThreadProjection,
  initialParts: ComposerPart[] = [],
  buildBatch: ((parts: ComposerPart[]) => CommandBatchPlan | null) | null = null,
) {
  const { t } = useI18n()
  const applicationEvents = useApplicationEvents()
  const threadId = projection.threadId
  const boundThreadIdRef = useRef(threadId)
  const bindingEpochRef = useRef(0)
  if (boundThreadIdRef.current !== threadId) {
    boundThreadIdRef.current = threadId
    bindingEpochRef.current += 1
  }
  const isStillBound = (originThreadId: string, originEpoch: number) =>
    boundThreadIdRef.current === originThreadId && bindingEpochRef.current === originEpoch

  const [actionError, setActionError] = useState<string | null>(null)
  const [conflict, setConflict] = useState<ConflictPresentation | null>(null)
  // 维护局部的 in-flight 计数与按绑定隔离的在途标识，保证跨 Thread 与重叠调用下状态准确。
  const [inFlightSubmissions, setInFlightSubmissions] = useState(0)
  const inFlightBindingsRef = useRef(new Set<string>())
  function isCurrentBindingInFlight(): boolean {
    return inFlightBindingsRef.current.has(`${boundThreadIdRef.current}:${bindingEpochRef.current}`)
  }

  // Bound thread 持久化 pending 消息：发送前先写入 storage，网络未知结果后保留，
  // reload/remount 后恢复，支持逐字节 retry 与明确放弃。
  const [pendingMessage, setPendingMessage] = useState<BoundPendingMessage | null>(
    () => (threadId ? loadBoundPendingMessage(threadId) : null),
  )
  const pendingMessageRef = useRef<BoundPendingMessage | null>(pendingMessage)
  useEffect(() => {
    pendingMessageRef.current = pendingMessage
  }, [pendingMessage])
  const replayPending = pendingMessage != null

  const initializedReplayThreadRef = useRef<string | null>(null)
  const queryClient = useQueryClient()

  // 命令与控制面仅消费 required 投影。
  const { thread, queuedCommands, stopReceipts, snapshotQuery } = projection
  const bound = projection.bound


  const draftRecord = useThreadDraftRecord({
    threadId,
    initialParts,
    boundThreadIdRef,
    bindingEpochRef,
    isStillBound,
    stopReceipts,
    setActionError,
  })
  const {
    draftRef,
    setDraft,
    clearSubmittedParts,
    restorePendingDraft,
    applyStopReceipts,
  } = draftRecord

  useEffect(() => {
    if (initializedReplayThreadRef.current === threadId) {
      return
    }
    initializedReplayThreadRef.current = threadId

    const restoredPending = threadId ? loadBoundPendingMessage(threadId) : null
    pendingMessageRef.current = restoredPending
    setPendingMessage(restoredPending)

    setInFlightSubmissions(0)
    setActionError(null)
    setConflict(null)
  }, [threadId])

  const compactMutation = useMutation({
    mutationFn: ({
      operationThreadId,
      expectedVersion,
      operationEpoch: _operationEpoch,
    }: {
      operationThreadId: string
      expectedVersion: string
      operationEpoch: number
    }) => harnessService.compactThread(operationThreadId, { expectedVersion }),
    onSuccess: async (_result, variables) => {
      if (isStillBound(variables.operationThreadId, variables.operationEpoch)) {
        setConflict(null)
      }
      await requestThreadSnapshotRefresh(queryClient, applicationEvents, variables.operationThreadId)
    },
  })

  /**
   * 409 意味着本地 version 已过期或 Thread 未处于静默状态。给出明确提示并重新拉取
   * 目标 Thread，使下一次尝试携带当前的 version。
   */
  function reportMutationError(error: unknown, fallbackKey: TranslationKey, targetThreadId = threadId) {
    if (isConflictError(error)) {
      const presented = presentConflict(error)
      if (presented != null) {
        setConflict(presented)
      } else {
        setActionError(
          t('ai.runtime.action.threadStateChanged', {
            action: t(fallbackKey),
            error: errorMessage(error),
          }),
        )
      }
      void requestThreadSnapshotRefresh(queryClient, applicationEvents, targetThreadId)
      return
    }
    setConflict(null)
    setActionError(errorMessage(error))
  }

  const { decideApproval, approvalPending } = useThreadApprovals({
    threadId,
    bindingEpochRef,
    isStillBound,
    setActionError,
    clearConflict: () => setConflict(null),
    reportMutationError,
  })

  async function executePendingMessage(initialPending: BoundPendingMessage): Promise<void> {
    const originThreadId = threadId
    const originEpoch = bindingEpochRef.current
    const originBindingKey = `${originThreadId}:${originEpoch}`
    const currentPending = initialPending
    const currentRequest = initialPending.request

    inFlightBindingsRef.current.add(originBindingKey)
    setInFlightSubmissions((count) => count + 1)

    const isCurrentBound = () => isStillBound(originThreadId, originEpoch)

    try {
      await harnessService.acceptThreadCommandBatch(originThreadId, currentRequest)
      clearBoundPendingMessage(originThreadId, currentRequest)
      if (isCurrentBound()) {
        const existingStored = loadBoundPendingMessage(originThreadId)
        if (existingStored == null || sameBatchRequestIdentity(existingStored.request, currentRequest)) {
          setConflict(null)
          pendingMessageRef.current = null
          setPendingMessage(null)
        }
      }
      // 成功后绝不再清空 draft，保留用户飞行期间输入的下一条草稿内容
      await Promise.all([
        requestThreadSnapshotRefresh(queryClient, applicationEvents, originThreadId),
        queryClient.invalidateQueries({ queryKey: queryKeys.chats.all }),
      ])
      return
    } catch (error: unknown) {
      // 确切已发 request / currentPending 贯穿错误处理，绝不使用旧闭包覆盖
      const stillBound = isCurrentBound()
      if (stillBound) {
        reportMutationError(error, 'ai.runtime.action.sendFailed', originThreadId)
      } else {
        void requestThreadSnapshotRefresh(queryClient, applicationEvents, originThreadId)
      }
      if (isDefiniteAcceptanceFailure(error)) {
        clearBoundPendingMessage(originThreadId, currentRequest)
        if (stillBound) {
          const existingStored = loadBoundPendingMessage(originThreadId)
          if (existingStored == null || sameBatchRequestIdentity(existingStored.request, currentRequest)) {
            pendingMessageRef.current = null
            setPendingMessage(null)
          }
        }
        // 失败草稿写回该 Thread 自己的持久记录：非当前绑定的 Thread 也不会污染当前输入框。
        await restorePendingDraft(originThreadId, originEpoch, currentPending)
      } else {
        const unknownPending: BoundPendingMessage = {
          ...currentPending,
          unknownOutcome: true,
        }
        // 关键防护：仅当 storage 中的 pending 仍匹配本次 request（或为空）时，才持久化 unknown sidecar
        // 绝不覆盖同 Thread 后续新发起的 sidecar
        const existingStored = loadBoundPendingMessage(originThreadId)
        if (existingStored == null || sameBatchRequestIdentity(existingStored.request, currentRequest)) {
          try {
            saveBoundPendingMessage(originThreadId, unknownPending)
          } catch {
            // best-effort writeback for unknown mark
          }
        }
        if (stillBound && (existingStored == null || sameBatchRequestIdentity(existingStored.request, currentRequest))) {
          pendingMessageRef.current = unknownPending
          setPendingMessage(unknownPending)
        }
        await restorePendingDraft(originThreadId, originEpoch, currentPending)
      }
    } finally {
      inFlightBindingsRef.current.delete(originBindingKey)
      if (isCurrentBound()) {
        setInFlightSubmissions((count) => Math.max(0, count - 1))
      }
    }
  }

  function submitMessage(payloadParts?: ComposerPart[], localDraftParts?: ComposerPart[]): Promise<void> {
    if (isCurrentBindingInFlight()) {
      return Promise.resolve()
    }
    const trimmed = trimMessageParts(payloadParts ?? draftRef.current)
    const localDraft = trimMessageParts(localDraftParts ?? draftRef.current)
    if (!hasMessageContent(trimmed) || slashQueryOf(trimmed) != null || !thread) {
      return Promise.resolve()
    }
    if (!bound || buildBatch == null) {
      setActionError(t('ai.runtime.action.threadNotLoaded'))
      return Promise.resolve()
    }
    const plan = buildBatch(trimmed)
    if (plan == null) {
      return Promise.resolve()
    }
    if (pendingMessageRef.current?.unknownOutcome) {
      if (partsKey(trimmed) === partsKey(pendingMessageRef.current.localDraft)) {
        return retryPendingMessage()
      }
      setActionError(t('ai.runtime.action.operationPending'))
      return Promise.resolve()
    }
    setActionError(null)
    setConflict(null)

    const boundPending: BoundPendingMessage = {
      threadId,
      request: plan.request,
      targetDraft: plan.targetDraft,
      localDraft,
      unknownOutcome: false,
    }
    try {
      saveBoundPendingMessage(threadId, boundPending)
    } catch {
      setActionError(t('ai.runtime.action.storageFailed'))
      return Promise.resolve()
    }
    pendingMessageRef.current = boundPending
    setPendingMessage(boundPending)
    clearSubmittedParts()

    return executePendingMessage(boundPending)
  }

  function retryPendingMessage(): Promise<void> {
    if (isCurrentBindingInFlight()) {
      return Promise.resolve()
    }
    const pending = pendingMessageRef.current ?? (threadId ? loadBoundPendingMessage(threadId) : null)
    if (pending == null) {
      return Promise.resolve()
    }
    setActionError(null)
    setConflict(null)
    const retryingPending: BoundPendingMessage = {
      ...pending,
      unknownOutcome: false,
    }
    try {
      saveBoundPendingMessage(threadId, retryingPending)
    } catch {
      setActionError(t('ai.runtime.action.storageFailed'))
      return Promise.resolve()
    }
    pendingMessageRef.current = retryingPending
    setPendingMessage(retryingPending)

    return executePendingMessage(retryingPending)
  }

  function abandonPendingMessage(): void {
    if (isCurrentBindingInFlight()) {
      return
    }
    const current = pendingMessageRef.current
    if (current == null) {
      return
    }
    const success = clearBoundPendingMessage(threadId, current.request)
    if (!success) {
      setActionError(t('ai.runtime.action.storageClearFailed'))
      return
    }
    pendingMessageRef.current = null
    setPendingMessage(null)
    setActionError(t('ai.runtime.action.abandonedPendingNotice'))
    setConflict(null)
  }

  function runCommand(command: ThreadCommand) {
    setActionError(null)
    switch (command.id) {
      case 'stop':
        void stopThread()
        return
      case 'compact':
        void compactThread()
        return
      default:
        setActionError(t('ai.runtime.action.unknownCommand', { command: command.id }))
    }
  }

  /** 在持久的 Thread snapshot 加载完成前，拦截 mailbox 操作。 */
  function requireBoundThread(actionKey: TranslationKey): boolean {
    if (bound) {
      return true
    }
    setActionError(t('ai.runtime.action.threadNotLoaded', { action: t(actionKey) }))
    return false
  }

  const {
    stopThread,
    stopPending,
    stopReplayPending,
  } = useThreadStopOperation({
    threadId,
    thread: thread ?? null,
    bindingEpochRef,
    isStillBound,
    setActionError,
    setConflict: () => setConflict(null),
    requireBoundThread,
    reportMutationError,
    applyStopReceipts,
    discardPendingMessage: () => {
      const cleared = pendingMessageRef.current
      if (cleared != null) {
        clearBoundPendingMessage(threadId, cleared.request)
        pendingMessageRef.current = null
        setPendingMessage(null)
      }
    },
  })

  function compactThread(): Promise<void> {
    if (!thread || !requireBoundThread('ai.runtime.action.compactFailed')) {
      return Promise.resolve()
    }
    setActionError(null)
    setConflict(null)
    const operationThreadId = threadId
    const operationEpoch = bindingEpochRef.current
    return compactMutation
      .mutateAsync({
        operationThreadId,
        expectedVersion: thread.version,
        operationEpoch,
      })
      .then(() => undefined)
      .catch((error: unknown) => {
        if (isStillBound(operationThreadId, operationEpoch)) {
          reportMutationError(error, 'ai.runtime.action.compactFailed', operationThreadId)
        }
      })
  }

  return {
    ...projection,
    messagesLoading: snapshotQuery.isLoading,
    messagesError: snapshotQuery.error ?? projection.messagesError,
    draft: draftRecord.draft,
    draftRestoreError: draftRecord.draftRestoreError,
    retryDraftRestore: draftRecord.retryDraftRestore,
    queuedCommands,
    // 重叠提交通过本地计数保持 pending 准确，而不是仅依赖 mutation observer。
    pending: inFlightSubmissions > 0,
    // Thread 必须先加载完成才能接受消息。
    disabled: !bound,
    actionError,
    dismissActionError: () => setActionError(null),
    conflict,
    dismissConflict: () => setConflict(null),
    setDraft,
    submitMessage,
    stopThread,
    compactThread,
    compactPending: compactMutation.isPending,
    runCommand,
    decideApproval,
    approvalPending,
    stopPending,
    // 等待精确重试的含混 Stop 操作会阻塞面板切换。
    stopReplayPending,
    replayPending,
    pendingMessage,
    retryPendingMessage,
    abandonPendingMessage,
  }
}
