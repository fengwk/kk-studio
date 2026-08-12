import { useEffect, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  extractContextWindow,
  formatModelRef,
  modelRef,
  type AgentModelView,
} from '@/features/ai/catalog'
import { buildThreadTimeline, isThreadWorking } from '@/features/ai/runtime/thread-timeline'
import type { ThreadCommand } from '@/features/ai/runtime'
import { useAgentThreadQueries } from '@/features/ai/runtime/useAgentThreadQueries'
import { useChatTranscriptAutoScroll } from '@/features/ai/runtime/useChatTranscriptAutoScroll'
import { useHarnessThreadRealtime } from '@/features/ai/runtime/useHarnessThreadRealtime'
import { useThreadCommandBatchMutation } from '@/features/ai/runtime/useThreadCommandBatchMutation'
import {
  createDecisionId,
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
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import { queryKeys } from '@/shared/lib/query-keys'
import { translate, useI18n } from '@/shared/i18n'

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
 * 一次含混的 Stop 操作。重试必须发送完全相同的原始 body（相同的
 * stopRequestId + 原始 expectedRevision）；basis 标识了派生该操作的 snapshot，
 * 因此一旦权威 snapshot 证明 basis 已变化（旧 Turn 结束 / head 或 revision
 * 已前进），该操作会被自动失效——在新 Turn 上的下一次 Stop 派生全新的 id。
 * Java 的 StopControl 在 revision CAS 之前按持久键回放，因此原始
 * expectedRevision 在精确重试中仍然有效。
 */
export interface PendingStopOperation {
  stopRequestId: string
  expectedRevision: string
  basisHeadEntryId: string
  basisRevision: string
}

/**
 * 由 stopThread（在每次复用前同步执行）与被动清理 effect 共享的 basis 栅栏：
 * 当且仅当权威 snapshot 证明 pending 操作的 basis 已变化（head 或 revision 已
 * 前进 => 旧 Turn 已结束或 Thread 已前进）时，该操作会被失效。stopThread
 * 内部的同步失效可以关闭 snapshot 已前进但 effect 尚未 flush 的窗口——
 * 在新 Turn 上的下一次 Stop 必须派生全新 id，绝不能把旧 id 复用给更新后的 revision。
 */
export function retireStaleStopPending(
  pending: PendingStopOperation | null,
  thread: { headEntryId: string; revision: string } | null,
): PendingStopOperation | null {
  if (pending == null || thread == null) {
    return pending
  }
  if (
    thread.headEntryId !== pending.basisHeadEntryId
    || thread.revision !== pending.basisRevision
  ) {
    return null
  }
  return pending
}

export function useAgentThreadController(
  threadId: string,
  initialParts: ComposerPart[] = [],
  initialReplay?: CommandBatchReplay,
  buildBatch: ((parts: ComposerPart[]) => CommandBatchPlan | null) | null = null,
  environmentReadyByName: ReadonlyMap<string, boolean> | null = null,
) {
  const { t } = useI18n()
  const [draft, setDraftState] = useState<ComposerPart[]>(initialParts)
  const draftRef = useRef<ComposerPart[]>(initialParts)
  const [actionError, setActionError] = useState<string | null>(null)
  // 维护局部的 in-flight 计数，保证重叠 mutateAsync 调用下 pending 状态依旧准确。
  const [inFlightSubmissions, setInFlightSubmissions] = useState(0)
  // 未决的精确 batch（处于 in-flight 或在不确定的网络错误后被保留）会阻塞面板
  // 切换：切换 Thread 时绝不能丢失逐字节复用的回放机会。
  const [replayPending, setReplayPending] = useState(Boolean(initialReplay))
  const replayRef = useRef<CommandBatchReplay | null>(null)
  const initializedReplayThreadRef = useRef<string | null>(null)
  const pendingStopRef = useRef<PendingStopOperation | null>(null)
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
    snapshotQuery,
  } = useAgentThreadQueries(threadId)
  const bound = Boolean(thread)
  const batchMutation = useThreadCommandBatchMutation(threadId)
  const realtime = useHarnessThreadRealtime(
    threadId,
    Boolean(threadId) && snapshotQuery.isSuccess,
    thread?.revision,
    modelInvocation,
    toolInvocations,
  )
  const timeline = buildThreadTimeline(
    entries,
    queuedCommands,
    toolInvocations,
    realtime.modelStream,
    realtime.toolStreams,
  )
  const working = isThreadWorking(thread, timeline)
  const runtimeLabels = resolveRuntimeLabels(thread, agents, models, environmentReadyByName)

  useChatTranscriptAutoScroll(
    bodyRef,
    timeline.messages.length,
    entries.length + queuedCommands.length,
  )

  useEffect(() => {
    if (initializedReplayThreadRef.current === threadId) {
      return
    }
    setDraftState(initialParts)
    draftRef.current = initialParts
    replayRef.current = initialReplay ?? null
    setReplayPending(initialReplay != null)
    initializedReplayThreadRef.current = threadId
    // 重新绑定到另一个 Thread 时，绝不能泄漏 stop/decision 的回放身份。
    pendingStopRef.current = null
    setStopReplayPending(false)
    decisionIdByInvocation.current.clear()
  }, [initialParts, initialReplay, threadId])

  const approvalMutation = useMutation({
    mutationFn: ({
      targetThreadId,
      invocationId,
      decision,
      decisionId,
    }: {
      targetThreadId: string
      invocationId: string
      decision: 'ALLOW' | 'DENY'
      decisionId: string
    }) =>
      harnessService.decideApproval(targetThreadId, invocationId, {
        decision,
        decisionId,
        actor: 'web',
        reason: null,
      }),
    onSuccess: async (_result, variables) => {
      decisionIdByInvocation.current.delete(
        `${variables.targetThreadId}:${variables.invocationId}:${variables.decision}`,
      )
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
    // 回放键必须包含 targetThreadId：不同子 Thread 可能复用相同的 invocationId，
    // 绝不能把 A 子 Thread 的幂等键复用给 B 子 Thread。
    const key = `${targetThreadId}:${invocationId}:${decision}`
    const decisionId = decisionIdByInvocation.current.get(key) ?? createDecisionId()
    decisionIdByInvocation.current.set(key, decisionId)
    return approvalMutation
      .mutateAsync({ targetThreadId, invocationId, decision, decisionId })
      .then(() => undefined)
      .catch((error: unknown) => {
        reportMutationError(error, 'ai.runtime.action.approvalFailed', targetThreadId)
      })
  }

  const stopMutation = useMutation({
    mutationFn: (body: { stopRequestId: string; expectedRevision: string }) =>
      harnessService.stopThread(threadId, body),
  })

  /**
   * 409 意味着本地 revision 已过期或 Thread 未处于静默状态。给出明确提示并重新拉取
   * 目标 Thread，使下一次尝试携带当前的 revision。
   */
  function reportMutationError(error: unknown, fallbackKey: string, targetThreadId = threadId) {
    if (isConflictError(error)) {
      setActionError(
        t('ai.runtime.action.threadStateChanged', {
          action: t(fallbackKey),
          error: errorMessage(error),
        }),
      )
      void queryClient.invalidateQueries({
        queryKey: queryKeys.threads.snapshot(targetThreadId),
      })
      return
    }
    setActionError(errorMessage(error))
  }

  function setDraft(next: ComposerPart[]) {
    // 把恢复的 draft 编辑为不同内容会重置请求回放身份。
    if (replayRef.current != null && partsKey(next) !== partsKey(replayRef.current.parts)) {
      replayRef.current = null
      setReplayPending(false)
    }
    draftRef.current = next
    setDraftState(next)
  }

  /**
   * 提交消息。payload 用于构建 command batch；localDraft（客户端 localId 快照）
   * 用于 replay 与失败恢复——两者分开，绝不把服务端 uploadId 回填进草稿。
   */
  function submitMessage(payloadParts?: ComposerPart[], localDraftParts?: ComposerPart[]): Promise<void> {
    const trimmed = trimMessageParts(payloadParts ?? draft)
    const localDraft = trimMessageParts(localDraftParts ?? draft)
    if (!hasMessageContent(trimmed) || slashQueryOf(trimmed) != null || !thread) {
      return Promise.resolve()
    }
    if (!bound) {
      setActionError(t('ai.runtime.action.threadNotLoaded'))
      return Promise.resolve()
    }
    if (buildBatch == null) {
      setActionError(t('ai.runtime.action.threadNotLoaded'))
      return Promise.resolve()
    }
    setActionError(null)
    const plan = buildBatch(trimmed)
    if (plan == null) {
      return Promise.resolve()
    }
    // 重试逐字节复用精确的先前 batch（相同的 command ID/payload/顺序和原始
    // expected cursor：服务器对 command 集合的有序回放会绕过已移动的 cursor）。
    const previous = replayRef.current
    const reused = previous != null && previous.plan.identity === plan.identity
      ? previous.plan
      : plan
    replayRef.current = { plan: reused, parts: localDraft }
    setReplayPending(true)
    // 先捕获 parts + id，随后立即清空 draft，以便输入下一条消息。
    draftRef.current = []
    setDraftState([])
    setInFlightSubmissions((count) => count + 1)
    return batchMutation
      .mutateAsync(reused.batch)
      .then(() => {
        // 仅当身份仍属于这个正在完成的请求时才清除它。
        if (replayRef.current?.plan === reused) {
          replayRef.current = null
          setReplayPending(false)
        }
      })
      .catch((error: unknown) => {
        reportMutationError(error, 'ai.runtime.action.sendFailed')
        // 仅在 composer 为空时恢复，以免破坏正在输入中的下一条 draft。
        // （Ref 变更必须在 state updater 之外进行：React 会延迟执行 updater 函数。）
        if (!hasMessageContent(draftRef.current)) {
          if (isConflictError(error)) {
            // 409 = batch 未被接受：精确回放已过期。下一次发送会基于刷新的
            // head/nextSequence 用全新的 command id 重新构建。
            replayRef.current = null
            setReplayPending(false)
          } else {
            // 网络/不确定的失败会保留精确 batch，用于逐字节回放。
            replayRef.current = { plan: reused, parts: localDraft }
            setReplayPending(true)
          }
          // 恢复本地草稿（客户端 localId），与提交 payload 分开。
          draftRef.current = localDraft
          setDraftState(localDraft)
        }
      })
      .finally(() => {
        setInFlightSubmissions((count) => Math.max(0, count - 1))
      })
  }

  function runCommand(command: ThreadCommand) {
    setActionError(null)
    switch (command.id) {
      case 'stop':
        void stopThread()
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
    // 同步 basis 栅栏：绝不复用 basis 已不再匹配「当前」渲染 snapshot 的待决操作
    //（head/revision 已移动 => 旧 Turn 已结束或 Thread 已前进）。在这里——而不仅仅在
    // 被动清理 effect 中——退役，可以关闭「snapshot 已前进但 effect 尚未 flush」时
    // 调用 Stop 的窗口。
    const pending = retireStaleStopPending(pendingStopRef.current, thread)
    if (pending !== pendingStopRef.current) {
      pendingStopRef.current = pending
      setStopReplayPending(false)
    }
    // 含混重试：复用「精确」的先前操作（相同的 stopRequestId + 原始
    // expectedRevision + basis）。全新的 Stop 会针对当前 snapshot 铸造新操作；
    // 原始 expectedRevision 绝不会从更新的 snapshot 重新推导。
    const operation = pending ?? {
      stopRequestId: createStopRequestId(),
      expectedRevision: thread.revision,
      basisHeadEntryId: thread.headEntryId,
      basisRevision: thread.revision,
    }
    if (pending == null) {
      pendingStopRef.current = operation
      setStopReplayPending(true)
    }
    return stopMutation
      .mutateAsync({
        stopRequestId: operation.stopRequestId,
        expectedRevision: operation.expectedRevision,
      })
      .then(async () => {
        // Stop 成功（或服务器重放了完全相同的先前 Stop）：操作已了结，
        // 下一次 stop 会铸造全新 id。
        pendingStopRef.current = null
        setStopReplayPending(false)
        replayRef.current = null
        setReplayPending(false)
        await Promise.all([
          queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) }),
          queryClient.invalidateQueries({ queryKey: queryKeys.chats.all }),
        ])
      })
      .catch((error: unknown) => {
        if (isConflictError(error)) {
          // 已知 409：操作未被接受（revision 过期 / 未静默 /
          // 终态 apply 待处理 / id 被复用）。清除它；下一次 stop 会针对
          // 刷新的 snapshot 铸造新操作。
          pendingStopRef.current = null
          setStopReplayPending(false)
        }
        // 网络/不确定的失败会保留精确操作，用于逐字节重试。
        reportMutationError(error, 'ai.runtime.action.stopFailed')
      })
  }

  // 权威 snapshot 的 basis 协调：当存在含混的 Stop 操作时，一旦 snapshot 证明其 basis
  // 已变化（head 或 revision 已移动 => 旧 Turn 已结束或 Thread 已前进），就退役该操作，
  // 使在新 Turn 上发起的 Stop 始终使用全新 id。该 effect 只在确有操作待决时起作用——
  // hook 初始挂载从不清理任何东西。stopThread 本身在每次复用前同步执行同一栅栏。
  useEffect(() => {
    const pending = retireStaleStopPending(pendingStopRef.current, thread ?? null)
    if (pending !== pendingStopRef.current) {
      pendingStopRef.current = pending
      setStopReplayPending(false)
    }
    // thread 每次 refetch 都是新的 snapshot 对象；只有其身份字段参与栅栏判定。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [thread?.headEntryId, thread?.revision, thread?.threadId])

  return {
    sessionId,
    agents,
    models,
    thread,
    bound,
    title: thread?.threadId || t('ai.chat.chatLabel'),
    timeline,
    runtimeLabels,
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
    setDraft,
    submitMessage,
    stopThread,
    runCommand,
    decideApproval,
    approvalPending: approvalMutation.isPending,
    stopPending: stopMutation.isPending,
    // 等待精确重试的含混 Stop 操作会阻塞面板切换。
    stopReplayPending,
    replayPending,
  }
}

function resolveRuntimeLabels(
  thread: ReturnType<typeof useAgentThreadQueries>['thread'],
  agents: AgentDefinitionDTO[],
  models: AgentModelView[],
  environmentReadyByName: ReadonlyMap<string, boolean> | null,
) {
  const settings = thread?.branchSettings
  const agent = agents.find((item) => item.name === settings?.agentName)
  const model = models.find((item) => modelRef(item) === agent?.model)
  const contextWindow = extractContextWindow(model)
  const environmentName = settings?.environmentName ?? null
  return {
    agentName: settings?.agentName || translate('ai.runtime.action.blankAgent'),
    providerName: settings?.model.providerName || undefined,
    // 规范的展示身份是 provider/model。
    modelName: formatModelRef(settings?.model.providerName, settings?.model.modelName),
    variantName: settings?.model.variant || undefined,
    // canonical 名称即展示身份；ready 标记来自 live 列表，缺失/未知 => 不可用。
    environmentName,
    environmentReady: environmentName == null
      ? undefined
      : (environmentReadyByName?.get(environmentName) ?? false),
    contextWindow,
  }
}
