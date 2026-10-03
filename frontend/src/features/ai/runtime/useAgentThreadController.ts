import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  extractContextWindow,
  type AgentModelView,
} from '@/features/ai/catalog'
import { buildThreadTimeline, isThreadWorking } from '@/features/ai/runtime/thread-timeline'
import { buildThreadEventTimeline } from '@/features/ai/runtime/thread-events'
import { aggregateBranchUsage } from '@/features/ai/runtime/thread-timeline/turn-usage'
import type { ThreadCommand } from '@/features/ai/runtime'
import { useAgentThreadQueries } from '@/features/ai/runtime/useAgentThreadQueries'
import { useHarnessThreadRealtime } from '@/features/ai/runtime/useHarnessThreadRealtime'
import { prependCancelledMessages } from '@/features/ai/runtime/cancelled-message-parts'
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
import { isDefiniteAcceptanceFailure } from '@/features/ai/runtime/agent-pane/agent-pane-pipeline'
import {
  createDecisionId,
  createStopRequestId,
  type CommandBatchPlan,
} from '@/features/ai/chat/command-batch-plan'
import {
  branchDraftFromThread,
  branchDraftsEqual,
  projectPendingTarget,
  type BranchDraft,
} from '@/features/ai/chat/branch-draft'
import {
  hasMessageContent,
  partsKey,
  slashQueryOf,
  trimMessageParts,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'
import {
  clearStoredComposerDraft,
  restoreComposerDraft,
  storeComposerDraft,
  type ComposerDraftChangeSource,
} from '@/features/ai/composer/composer-draft'
import { isConflictError, isConflictReason } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import type {
  AgentCommandBatchRequestDTO,
  HarnessCommandCreateDTO,
  HarnessThreadSnapshotDTO,
} from '@/shared/api/contracts/ai-runtime'
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

const MAX_STALE_MESSAGE_CURSOR_RETRIES = 2

/**
 * 只有纯消息 batch 才能在 cursor 冲突后原样换用新 cursor；SET_* 必须重新计算 diff。
 * 旧 head 仍位于最新 root-to-head 路径时，变化只是同一分支向前推进。
 */
export function canRetryStaleMessageBatch(
  planOrRequest:
    | CommandBatchPlan
    | AgentCommandBatchRequestDTO
    | { request: AgentCommandBatchRequestDTO; targetDraft?: BranchDraft },
  snapshot: HarnessThreadSnapshotDTO,
): boolean {
  const request: AgentCommandBatchRequestDTO = 'request' in planOrRequest ? planOrRequest.request : planOrRequest
  const target = request.target
  if (
    target.type !== 'THREAD'
    || request.commands.length === 0
    || request.commands.some((command: HarnessCommandCreateDTO) => command.type !== 'USER_MESSAGE')
  ) {
    return false
  }
  if (
    snapshot.thread.headEntryId === target.expectedHeadEntryId
    && snapshot.thread.nextCommandSequence === target.expectedNextCommandSequence
  ) {
    return false
  }
  if ('targetDraft' in planOrRequest && planOrRequest.targetDraft != null) {
    const latestTarget = projectPendingTarget(
      branchDraftFromThread(snapshot.thread),
      snapshot.queuedCommands,
    )
    if (!branchDraftsEqual(latestTarget, planOrRequest.targetDraft)) {
      return false
    }
  }
  return snapshot.thread.headEntryId === target.expectedHeadEntryId
    || snapshot.entries.some((entry) => entry.entryId === target.expectedHeadEntryId)
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

  const draftStorageScope = `thread:${threadId}`
  const [draft, setDraftState] = useState<ComposerPart[]>(
    () => restoreComposerDraft(draftStorageScope, initialParts),
  )
  const draftRef = useRef<ComposerPart[]>(draft)
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
  const appliedStopRequestIdsRef = useRef(new Set<string>())
  // 当含混的 Stop 操作尚待重试或失效时为 true：切换 Thread 时面板
  // 绝不能悄悄丢弃这次精确重试。
  const [stopReplayPending, setStopReplayPending] = useState(false)
  const decisionIdByInvocation = useRef(new Map<string, string>())
  const bodyRef = useRef<HTMLDivElement>(null)
  const queryClient = useQueryClient()

  const {
    agents,
    models,
    thread,
    sessionId,
    entries,
    queuedCommands,
    modelInvocation,
    toolInvocations,
    modelAttemptFailures,
    manualCompaction,
    snapshotQuery,
  } = useAgentThreadQueries(threadId)
  const bound = Boolean(thread)
  const realtime = useHarnessThreadRealtime(
    threadId,
    Boolean(threadId) && snapshotQuery.isSuccess,
    thread?.version,
    modelInvocation,
    toolInvocations,
    modelAttemptFailures,
  )
  const timeline = buildThreadTimeline(
    entries,
    queuedCommands,
    toolInvocations,
    realtime.modelStream,
    realtime.toolStreams,
    modelAttemptFailures,
    modelInvocation,
  )
  const branchUsage = aggregateBranchUsage(timeline.messages)
  // Event 投影独立于 DialogueMessage：durable Entry 全类型 + 活跃 model/tool overlay。
  // useMemo 保证快照未变化时 events 引用稳定（Pane 的 selected/active 跟随 effect 依赖它）。
  const events = useMemo(
    () => buildThreadEventTimeline({
      entries,
      modelInvocation,
      toolInvocations,
      modelAttemptFailures,
      modelStream: realtime.modelStream,
      toolStreams: realtime.toolStreams,
    }),
    [entries, modelAttemptFailures, modelInvocation, realtime.modelStream, realtime.toolStreams, toolInvocations],
  )
  const working = isThreadWorking(thread, timeline)
  const runtimeLabels = resolveRuntimeLabels(thread, models)

  useEffect(() => {
    if (initializedReplayThreadRef.current === threadId) {
      return
    }
    const restoredDraft = restoreComposerDraft(draftStorageScope, initialParts)
    setDraftState(restoredDraft)
    draftRef.current = restoredDraft
    storeComposerDraft(draftStorageScope, restoredDraft)

    const restoredPending = threadId ? loadBoundPendingMessage(threadId) : null
    pendingMessageRef.current = restoredPending
    setPendingMessage(restoredPending)

    initializedReplayThreadRef.current = threadId
    setInFlightSubmissions(0)
    setActionError(null)
    setConflict(null)
    // Pending Stop 按 Thread 持久化；重新绑定时只加载当前 Thread 的精确 identity。
    const restoredStop = loadPendingStop(threadId)
    pendingStopRef.current = restoredStop
    setStopReplayPending(restoredStop != null)
    appliedStopRequestIdsRef.current.clear()
    decisionIdByInvocation.current.clear()
  }, [draftStorageScope, initialParts, threadId])

  const approvalMutation = useMutation({
    mutationFn: ({
      targetThreadId,
      invocationId,
      decision,
      decisionId,
      operationBindingThreadId: _bindingThreadId,
      operationEpoch: _operationEpoch,
    }: {
      targetThreadId: string
      invocationId: string
      decision: 'ALLOW' | 'DENY'
      decisionId: string
      operationBindingThreadId: string
      operationEpoch: number
    }) =>
      harnessService.decideApproval(targetThreadId, invocationId, {
        decision,
        decisionId,
        reason: null,
      }),
    onSuccess: async (_result, variables) => {
      if (isStillBound(variables.operationBindingThreadId, variables.operationEpoch)) {
        setConflict(null)
      }
      const key = `${variables.targetThreadId}:${variables.invocationId}:${variables.decision}`
      if (decisionIdByInvocation.current.get(key) === variables.decisionId) {
        decisionIdByInvocation.current.delete(key)
      }
      await Promise.all([
        // 决策落在哪个 Thread 就刷新哪个 Thread 的 snapshot（子 Thread 审批
        // 会投影到其自己的 ToolInvocation）；Chat 列表总需要刷新。
        queryClient.invalidateQueries({
          queryKey: queryKeys.threads.snapshot(variables.targetThreadId),
        }),
        queryClient.invalidateQueries({ queryKey: queryKeys.chats.all }),
      ])
    },
  })

  /**
   * 审批/拒绝待决的 ToolInvocation。decision id 在重试「同一个」决策期间保持稳定；
   * 切换决策（ALLOW -> DENY）会铸造新 id——当之前的决策已经落地时服务器会返回 409，
   * 刷新后即可呈现该结果。
   *
   * targetThreadId 默认指向本 controller 绑定的 Thread（Bound transcript 的既有审批）；
   * 子 Thread 的审批（Task widget）必须显式传入 status.threadId。
   */
  function decideApproval(
    invocationId: string,
    decision: 'ALLOW' | 'DENY',
    targetThreadId: string = threadId,
  ): Promise<void> {
    setActionError(null)
    setConflict(null)
    const operationBindingThreadId = threadId
    const operationEpoch = bindingEpochRef.current
    // 子 Thread 的审批独立寻址，未知结果重试复用同一个 decisionId。
    const key = `${targetThreadId}:${invocationId}:${decision}`
    const decisionId = decisionIdByInvocation.current.get(key) ?? createDecisionId()
    decisionIdByInvocation.current.set(key, decisionId)
    return approvalMutation
      .mutateAsync({
        targetThreadId,
        invocationId,
        decision,
        decisionId,
        operationBindingThreadId,
        operationEpoch,
      })
      .then(() => undefined)
      .catch((error: unknown) => {
        if (isStillBound(operationBindingThreadId, operationEpoch)) {
          reportMutationError(error, 'ai.runtime.action.approvalFailed', targetThreadId)
        }
      })
  }

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

  function setDraft(
    next: ComposerPart[],
    source: ComposerDraftChangeSource = 'edit',
  ) {
    if (source === 'edit') {
      storeComposerDraft(draftStorageScope, next)
    }
    draftRef.current = next
    setDraftState(next)
  }

  async function executePendingMessage(initialPending: BoundPendingMessage): Promise<void> {
    const originThreadId = threadId
    const originEpoch = bindingEpochRef.current
    const originBindingKey = `${originThreadId}:${originEpoch}`
    const originScope = `thread:${originThreadId}`
    let currentPending = initialPending
    let currentRequest = initialPending.request

    inFlightBindingsRef.current.add(originBindingKey)
    setInFlightSubmissions((count) => count + 1)

    const isCurrentBound = () => isStillBound(originThreadId, originEpoch)

    try {
      for (let retryCount = 0; ; retryCount += 1) {
        try {
          await harnessService.acceptCommandBatch(currentRequest)
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
        } catch (error) {
          if (
            retryCount < MAX_STALE_MESSAGE_CURSOR_RETRIES
            && isConflictReason(error, 'STALE_COMMAND_CURSOR')
          ) {
            // retry stale cursor 每次 await 后也 guard 当前 binding，避免跨 binding 自动重发
            if (!isCurrentBound()) {
              throw error
            }
            let latest: HarnessThreadSnapshotDTO
            try {
              latest = await harnessService.getThreadSnapshot(originThreadId)
            } catch {
              throw error
            }
            if (!isCurrentBound()) {
              throw error
            }
            if (
              latest.thread.threadId === originThreadId
              && canRetryStaleMessageBatch(
                { request: currentRequest, targetDraft: currentPending.targetDraft },
                latest,
              )
            ) {
              queryClient.setQueryData(queryKeys.threads.snapshot(originThreadId), latest)
              currentRequest = {
                ...currentRequest,
                target: {
                  type: 'THREAD',
                  threadId: originThreadId,
                  expectedHeadEntryId: latest.thread.headEntryId,
                  expectedNextCommandSequence: latest.thread.nextCommandSequence,
                },
              }
              const updatedPending: BoundPendingMessage = {
                ...currentPending,
                request: currentRequest,
              }
              // stale 恢复 save 失败不能 best-effort 继续 POST，必须 fail-closed 抛错
              saveBoundPendingMessage(originThreadId, updatedPending)
              currentPending = updatedPending
              if (isCurrentBound()) {
                pendingMessageRef.current = updatedPending
                setPendingMessage(updatedPending)
              }
              continue
            }
          }
          throw error
        }
      }
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
          if (!hasMessageContent(draftRef.current)) {
            storeComposerDraft(originScope, currentPending.localDraft)
            draftRef.current = currentPending.localDraft
            setDraftState(currentPending.localDraft)
          }
        } else {
          // 不在当前 binding：绝不可修改当前 UI/ref，原 Thread 失败草稿安全持久化
          const originDraft = restoreComposerDraft(originScope, [])
          if (!hasMessageContent(originDraft)) {
            storeComposerDraft(originScope, currentPending.localDraft)
          }
        }
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
        if (stillBound) {
          if (existingStored == null || sameBatchRequestIdentity(existingStored.request, currentRequest)) {
            pendingMessageRef.current = unknownPending
            setPendingMessage(unknownPending)
          }
          if (!hasMessageContent(draftRef.current)) {
            storeComposerDraft(originScope, currentPending.localDraft)
            draftRef.current = currentPending.localDraft
            setDraftState(currentPending.localDraft)
          }
        } else {
          const originDraft = restoreComposerDraft(originScope, [])
          if (!hasMessageContent(originDraft)) {
            storeComposerDraft(originScope, currentPending.localDraft)
          }
        }
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
    clearStoredComposerDraft(draftStorageScope)
    draftRef.current = []
    setDraftState([])

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
          // 让用户返回后用同一 id replay 并把 cancelled messages 恢复到正确 Composer。
          await Promise.all([
            queryClient.invalidateQueries({
              queryKey: queryKeys.threads.snapshot(operationThreadId),
            }),
            queryClient.invalidateQueries({ queryKey: queryKeys.chats.all }),
          ])
          return
        }
        // Stop 成功（或服务器重放了完全相同的先前 Stop）：操作已了结，
        // 下一次 stop 会铸造全新 id。
        if (!appliedStopRequestIdsRef.current.has(operation.stopRequestId)) {
          const restored = prependCancelledMessages(
            result.cancelledUserMessages,
            draftRef.current,
          )
          if (partsKey(restored) !== partsKey(draftRef.current)) {
            setDraft(restored)
          }
          appliedStopRequestIdsRef.current.add(operation.stopRequestId)
        }
        pendingStopRef.current = null
        setStopReplayPending(false)
        clearPendingStop(operationThreadId)
        if (isCurrentBound() && pendingMessageRef.current != null) {
          clearBoundPendingMessage(operationThreadId, pendingMessageRef.current.request)
          pendingMessageRef.current = null
          setPendingMessage(null)
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
          // 已知 409：操作未被接受（version 过期 / 未静默 /
          // 终态 apply 待处理 / id 被复用）。清除它；下一次 stop 会针对
          // 刷新的 snapshot 铸造新操作。
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
    sessionId,
    agents,
    models,
    thread,
    bound,
    title: thread?.threadId || t('ai.chat.chatLabel'),
    timeline,
    events,
    runtimeLabels,
    branchUsage,
    working,
    entries,
    messagesLoading: snapshotQuery.isLoading,
    messagesError: snapshotQuery.error,
    bodyRef,
    draft,
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
    manualCompaction,
    runCommand,
    decideApproval,
    approvalPending: approvalMutation.isPending,
    stopPending: stopMutation.isPending,
    // 等待精确重试的含混 Stop 操作会阻塞面板切换。
    stopReplayPending,
    replayPending,
    pendingMessage,
    retryPendingMessage,
    abandonPendingMessage,
  }
}

function resolveRuntimeLabels(
  thread: ReturnType<typeof useAgentThreadQueries>['thread'],
  models: AgentModelView[],
) {
  const settings = thread?.branchSettings
  const model = models.find(
    (item) =>
      item.providerName === settings?.model.providerName
      && item.name === settings?.model.modelName,
  )
  const contextWindow = extractContextWindow(model)
  const environmentName = settings?.environmentName ?? null
  return {
    environmentName,
    contextWindow,
  }
}
