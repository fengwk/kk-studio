import { useEffect, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import type { ThreadCommand } from '@/features/ai/runtime'
import {
  useThreadProjection,
  type ThreadProjection,
} from '@/features/ai/runtime/useThreadProjection'
import { useThreadApprovals } from '@/features/ai/runtime/thread-draft/useThreadApprovals'
import { useThreadDraftRecord } from '@/features/ai/runtime/thread-draft/useThreadDraftRecord'
import {
  clearPendingStop,
  loadPendingStop,
  storePendingStop,
  type PendingStopOperation,
} from '@/features/ai/runtime/pending-stop-sidecar'
import {
  clearBoundPendingMessage,
  loadBoundPendingMessage,
  saveBoundPendingMessage,
  sameBatchRequestIdentity,
  type BoundPendingMessage,
} from '@/features/ai/runtime/agent-pane/pane-target'
import {
  isDefiniteAcceptanceFailure,
  normalizeGoalText,
} from '@/features/ai/runtime/agent-pane/agent-pane-pipeline'
import {
  createStopRequestId,
  type CommandBatchPlan,
} from '@/features/ai/chat/command-batch-plan'
import {
  hasMessageContent,
  partsKey,
  slashQueryOf,
  trimMessageParts,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'
import { isConflictError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { translate, useI18n } from '@/shared/i18n'
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

/**
 * 一次失败发送的回放身份：精确的 command batch（保持相同的 ID/负载/顺序
 * 以及原始 expected cursor），加上恢复后的 ordered composer parts。
 */
export interface CommandBatchReplay {
  plan: CommandBatchPlan
  parts: ComposerPart[]
}

/**
 * 由 stopThread（在每次复用前同步执行）与被动清理 effect 共享的 basis 栅栏：
 * 当且仅当权威 snapshot 证明 pending 操作的 basis 已变化（head 或 version 已
 * 前进 => 旧 Turn 已结束或 Thread 已前进）时，该操作会被失效。stopThread
 * 内部的同步失效可以关闭 snapshot 已前进但 effect 尚未 flush 的窗口——
 * 在新 Turn 上的下一次 Stop 必须派生全新 id，绝不能把旧 id 复用给更新后的 version。
 */
export function retireStaleStopPending(
  pending: PendingStopOperation | null,
  thread: { headEntryId: string; version: string } | null,
): PendingStopOperation | null {
  if (pending == null || thread == null) {
    return pending
  }
  if (
    thread.headEntryId !== pending.requestHeadEntryId
    || thread.version !== pending.basisVersion
  ) {
    return null
  }
  return pending
}

export function useAgentThreadController(
  threadId: string,
  initialParts: ComposerPart[] = [],
  buildBatch: ((parts: ComposerPart[]) => CommandBatchPlan | null) | null = null,
  buildGoalBatch: ((goalText: string | null) => CommandBatchPlan | null) | null = null,
  injectedProjection?: ThreadProjection,
) {
  const { t } = useI18n()
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
  const pendingStopRef = useRef<PendingStopOperation | null>(null)
  // 当含混的 Stop 操作尚待重试或失效时为 true：切换 Thread 时面板
  // 绝不能悄悄丢弃这次精确重试。
  const [stopReplayPending, setStopReplayPending] = useState(false)
  const queryClient = useQueryClient()

  // 只读投影是唯一所有者：控制面只叠加编辑草稿、待决提交与人工执行 mutation。
  // 根面板已经把该 Thread 的投影（快照、实时流、timeline、usage）交给调用方时直接复用，
  // 不再自建第二个订阅；未注入时（例如草稿面板）才自建。
  const ownProjection = useThreadProjection(injectedProjection ? '' : threadId)
  const projection = injectedProjection ?? ownProjection
  const { thread, queuedCommands, stopReceipts, manualCompaction, snapshotQuery } = projection
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
    setGoalDraft,
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
    // Pending Stop 按 Thread 持久化；重新绑定时只加载当前 Thread 的精确 identity。
    const restoredStop = loadPendingStop(threadId)
    pendingStopRef.current = restoredStop
    setStopReplayPending(restoredStop != null)
  }, [threadId])

  const stopMutation = useMutation({
    mutationFn: ({
      operationThreadId,
      body,
    }: {
      operationThreadId: string
      body: { stopRequestId: string; expectedVersion: string }
    }) => harnessService.stopThread(operationThreadId, body),
  })

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
      await queryClient.invalidateQueries({
        queryKey: queryKeys.threads.snapshot(variables.operationThreadId),
      })
    },
  })

  /**
   * 409 意味着本地 version 已过期或 Thread 未处于静默状态。给出明确提示并重新拉取
   * 目标 Thread，使下一次尝试携带当前的 version。
   */
  function reportMutationError(error: unknown, fallbackKey: string, targetThreadId = threadId) {
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
      void queryClient.invalidateQueries({
        queryKey: queryKeys.threads.snapshot(targetThreadId),
      })
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
        queryClient.invalidateQueries({
          queryKey: queryKeys.threads.snapshot(originThreadId),
        }),
        queryClient.invalidateQueries({ queryKey: queryKeys.chats.all }),
      ])
      return
    } catch (error: unknown) {
      // 确切已发 request / currentPending 贯穿错误处理，绝不使用旧闭包覆盖
      const stillBound = isCurrentBound()
      if (stillBound) {
        reportMutationError(error, 'ai.runtime.action.sendFailed', originThreadId)
      } else {
        void queryClient.invalidateQueries({
          queryKey: queryKeys.threads.snapshot(originThreadId),
        })
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
      kind: 'MESSAGE',
      localDraft,
      goalText: null,
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

  /**
   * 既有 Thread 的 Goal 写入：与普通消息共用同一 thread command batch 与未决重试通道，
   * 但提交后清空 Goal 编辑区，未知结果或 Stop 回执则把目标文本恢复到该编辑区。
   */
  function submitGoal(rawGoalText: string | null): Promise<void> {
    if (isCurrentBindingInFlight()) {
      return Promise.resolve()
    }
    if (!bound || buildGoalBatch == null) {
      setActionError(t('ai.runtime.action.threadNotLoaded'))
      return Promise.resolve()
    }
    let goalText: string | null
    try {
      goalText = normalizeGoalText(rawGoalText)
    } catch (error) {
      setActionError(errorMessage(error))
      return Promise.resolve()
    }
    const plan = buildGoalBatch(goalText)
    if (plan == null) {
      return Promise.resolve()
    }
    if (pendingMessageRef.current?.unknownOutcome) {
      setActionError(t('ai.runtime.action.operationPending'))
      return Promise.resolve()
    }
    setActionError(null)
    setConflict(null)

    const boundPending: BoundPendingMessage = {
      threadId,
      request: plan.request,
      targetDraft: plan.targetDraft,
      kind: 'GOAL',
      localDraft: [],
      goalText,
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
    setGoalDraft(null)

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
  function requireBoundThread(actionKey: string): boolean {
    if (bound) {
      return true
    }
    setActionError(t('ai.runtime.action.threadNotLoaded', { action: t(actionKey) }))
    return false
  }

  function goalCommandKey(pending: BoundPendingMessage): string | null {
    const goalCommand = pending.request.commands.find((command) => command.type === 'GOAL')
    return goalCommand?.idempotencyKey ?? null
  }

  function stopThread(): Promise<void> {
    if (!thread || !requireBoundThread('ai.runtime.action.stopFailed')) {
      return Promise.resolve()
    }
    setActionError(null)
    setConflict(null)
    const operationThreadId = threadId
    const operationEpoch = bindingEpochRef.current
    const isCurrentBound = () => isStillBound(operationThreadId, operationEpoch)
    // 同步 basis 栅栏：绝不复用 basis 已不再匹配「当前」渲染 snapshot 的待决操作
    //（head/version 已移动 => 旧 Turn 已结束或 Thread 已前进）。在这里——而不仅仅在
    // 被动清理 effect 中——退役，可以关闭「snapshot 已前进但 effect 尚未 flush」时
    // 调用 Stop 的窗口。
    const pending = retireStaleStopPending(pendingStopRef.current, thread)
    if (pending !== pendingStopRef.current) {
      pendingStopRef.current = pending
      setStopReplayPending(false)
      clearPendingStop(threadId)
    }
    // 含混重试：复用「精确」的先前操作（相同的 stopRequestId + 原始
    // expectedVersion + basis）。全新的 Stop 会针对当前 snapshot 铸造新操作；
    // 原始 expectedVersion 绝不会从更新的 snapshot 重新推导。
    const operation = pending ?? {
      stopRequestId: createStopRequestId(),
      expectedVersion: thread.version,
      requestHeadEntryId: thread.headEntryId,
      basisVersion: thread.version,
    }
    if (pending == null) {
      pendingStopRef.current = operation
      setStopReplayPending(true)
      storePendingStop(operationThreadId, operation)
    }
    return stopMutation
      .mutateAsync({
        operationThreadId,
        body: {
          stopRequestId: operation.stopRequestId,
          expectedVersion: operation.expectedVersion,
        },
      })
      .then(async (result) => {
        if (!isCurrentBound()) {
          // 导航门控正常情况下不会发生；若宿主强制重绑，保留旧 Thread sidecar，
          // 让用户返回后用同一 id replay，并从快照回执恢复草稿。
          await Promise.all([
            queryClient.invalidateQueries({
              queryKey: queryKeys.threads.snapshot(operationThreadId),
            }),
            queryClient.invalidateQueries({ queryKey: queryKeys.chats.all }),
          ])
          return
        }
        // Stop 成功（或服务器重放了完全相同的先前 Stop）：完整受影响集合的持久回执
        // 按 (threadId, stopRequestId) 原子幂等合并；子 Thread 草稿只写它自己的记录。
        const receipts = result.stoppedThreads ?? []
        const cancelledKeys = new Set(receipts.flatMap((receipt) =>
          (receipt.cancelledInputs ?? []).map((input) => input.idempotencyKey)))
        // Stop 已成功：即便回执合并失败也只报「草稿未恢复」，绝不把它当成 Stop 失败。
        await applyStopReceipts(receipts, operationThreadId, operationEpoch)
        // await 期间宿主可能已强制重绑：A 的 late completion 只能清理 A 自己的 sidecar，
        // 绝不改动当前绑定的待决标记（那可能已经是 B 刚发起、结果未知的 Stop）。
        clearPendingStop(operationThreadId)
        if (isCurrentBound()) {
          pendingStopRef.current = null
          setStopReplayPending(false)
        }
        if (isCurrentBound() && pendingMessageRef.current != null) {
          const cleared = pendingMessageRef.current
          clearBoundPendingMessage(operationThreadId, cleared.request)
          pendingMessageRef.current = null
          setPendingMessage(null)
          // 未确认落地且未被本次回执取消的 Goal 目标文本不能丢：写回该 Thread 的编辑区记录。
          const clearedKey = goalCommandKey(cleared)
          if (cleared.kind === 'GOAL' && (clearedKey == null || !cancelledKeys.has(clearedKey))) {
            await restorePendingDraft(operationThreadId, operationEpoch, cleared)
          }
        }
        await Promise.all([
          queryClient.invalidateQueries({
            queryKey: queryKeys.threads.snapshot(operationThreadId),
          }),
          queryClient.invalidateQueries({ queryKey: queryKeys.chats.all }),
        ])
      })
      .catch((error: unknown) => {
        const stillBound = isCurrentBound()
        if (isConflictError(error)) {
          // 已知 409：操作未被接受（version 过期 / 未静默 / id 被复用）。
          // 清除它；下一次 stop 会针对刷新的 snapshot 铸造新操作。
          clearPendingStop(operationThreadId)
          if (stillBound) {
            pendingStopRef.current = null
            setStopReplayPending(false)
          }
        }
        // 网络/不确定的失败会保留精确操作，用于逐字节重试。
        if (stillBound) {
          reportMutationError(error, 'ai.runtime.action.stopFailed', operationThreadId)
        }
      })
  }

  function compactThread(): Promise<void> {
    if (!thread || !requireBoundThread('ai.runtime.action.compactFailed')) {
      return Promise.resolve()
    }
    if (!manualCompaction.available) {
      setActionError(
        manualCompaction.disabledReason
        || t('ai.runtime.action.compactUnavailable'),
      )
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

  // 权威 snapshot 的 basis 协调：当存在含混的 Stop 操作时，一旦 snapshot 证明其 basis
  // 已变化（head 或 version 已移动 => 旧 Turn 已结束或 Thread 已前进），就退役该操作，
  // 使在新 Turn 上发起的 Stop 始终使用全新 id。该 effect 只在确有操作待决时起作用——
  // hook 初始挂载从不清理任何东西。stopThread 本身在每次复用前同步执行同一栅栏。
  useEffect(() => {
    const pending = retireStaleStopPending(pendingStopRef.current, thread ?? null)
    if (pending !== pendingStopRef.current) {
      pendingStopRef.current = pending
      setStopReplayPending(false)
      clearPendingStop(threadId)
    }
    // thread 每次 refetch 都是新的 snapshot 对象；只有其身份字段参与栅栏判定。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [thread?.headEntryId, thread?.version, thread?.threadId])

  return {
    ...projection,
    messagesLoading: snapshotQuery.isLoading,
    messagesError: snapshotQuery.error,
    draft: draftRecord.draft,
    goalDraft: draftRecord.goalDraft,
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
    setGoalDraft,
    submitMessage,
    submitGoal,
    stopThread,
    compactThread,
    compactPending: compactMutation.isPending,
    manualCompaction,
    runCommand,
    decideApproval,
    approvalPending,
    stopPending: stopMutation.isPending,
    // 等待精确重试的含混 Stop 操作会阻塞面板切换。
    stopReplayPending,
    replayPending,
    pendingMessage,
    retryPendingMessage,
    abandonPendingMessage,
  }
}
