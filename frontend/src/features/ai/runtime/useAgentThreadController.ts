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
import {
  applyStopReceipt,
  loadThreadDraft,
  restoreThreadDraftParts,
  restoreThreadGoalText,
  saveThreadDraftParts,
  saveThreadGoalText,
  type DraftSaveOutcome,
  type ThreadDraftRecord,
} from '@/features/ai/runtime/thread-draft-store'
import {
  mergeCancelledGoalTexts,
  prependCancelledMessages,
} from '@/features/ai/runtime/cancelled-message-parts'
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
import { isConflictError, isConflictReason } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import type {
  HarnessCommandCreateDTO,
  HarnessThreadSnapshotDTO,
  HarnessStoppedThreadReceiptDTO,
  ThreadCommandBatchRequestDTO,
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
    | ThreadCommandBatchRequestDTO
    | { request: ThreadCommandBatchRequestDTO; targetDraft?: BranchDraft },
  snapshot: HarnessThreadSnapshotDTO,
): boolean {
  const request: ThreadCommandBatchRequestDTO = 'request' in planOrRequest ? planOrRequest.request : planOrRequest
  if (
    request.commands.length === 0
    || request.commands.some((command: HarnessCommandCreateDTO) => command.type !== 'USER_MESSAGE')
  ) {
    return false
  }
  if (
    snapshot.thread.headEntryId === request.expectedHeadEntryId
    && snapshot.thread.nextCommandSequence === request.expectedNextCommandSequence
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
  return snapshot.thread.headEntryId === request.expectedHeadEntryId
    || snapshot.entries.some((entry) => entry.entryId === request.expectedHeadEntryId)
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

  // composer 草稿与 Goal 编辑区文本都以该 Thread 的持久记录为权威；记录同时保存
  // 已合并的 Stop 回执身份，保证 Stop 响应与 snapshot 两条通道不会重复回填。
  const [draft, setDraftState] = useState<ComposerPart[]>(() => initialParts)
  const draftRef = useRef<ComposerPart[]>(draft)
  const [goalDraft, setGoalDraftState] = useState<string | null>(null)
  const goalDraftRef = useRef<string | null>(null)
  // 每次用户编辑自增：回执合并完成时据此判断编辑区是否已被更新的输入取代。
  const draftRevisionRef = useRef(0)
  // 挂载时的初始草稿（面板当前一律传空）；重绑时用它清空，避免上一 Thread 的草稿泄漏。
  const initialPartsRef = useRef(initialParts)
  // 合并失败、尚未恢复的回执；仅由可见的手动重试再次投递，不做自动无限重试。
  const failedReceiptsRef = useRef<HarnessStoppedThreadReceiptDTO[]>([])
  // 本标签页已经写进编辑区的回执身份：同一回执经响应与快照两个通道再次投递时不重复追加、
  // 也不回退用户随后的编辑。
  const appliedReceiptKeysRef = useRef<Set<string>>(new Set())
  // 本 UI 已观察到的恢复 generation（记录中已合并的 Stop 回执），只由 loadThreadDraft 与
  // applyStopReceipt 返回的 record 更新。所有整份草稿写入都携带它，存储层据此拒绝陈旧覆盖写。
  const observedStopRequestIdsRef = useRef<readonly string[]>([])
  // 每个字段各自的「最新写入 token + 是否未落盘」：旧 completion（乱序、旧 Thread、旧版本）
  // 既不得把新编辑标成已保存，也不得清掉尚未落盘的编辑。两字段互不影响。
  const partsWriteRef = useRef({ token: 0, dirty: false })
  const goalWriteRef = useRef({ token: 0, dirty: false })
  const attemptedReceiptSignatureRef = useRef<string | null>(null)
  const [draftRestoreError, setDraftRestoreError] = useState<string | null>(null)
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
    stopReceipts,
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
    initializedReplayThreadRef.current = threadId

    // 重绑必须丢弃上一 Thread 的全部本地状态：草稿、Goal 文本、编辑修订与回执重试记录。
    draftRef.current = initialPartsRef.current
    setDraftState(initialPartsRef.current)
    goalDraftRef.current = null
    setGoalDraftState(null)
    draftRevisionRef.current = 0
    failedReceiptsRef.current = []
    appliedReceiptKeysRef.current = new Set()
    observedStopRequestIdsRef.current = []
    partsWriteRef.current = { token: 0, dirty: false }
    goalWriteRef.current = { token: 0, dirty: false }
    attemptedReceiptSignatureRef.current = null
    setDraftRestoreError(null)

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
    decisionIdByInvocation.current.clear()

    // 草稿（composer + Goal 编辑区）以该 Thread 的持久记录为权威；记录尚未到达前
    // 保留初始值，且绝不覆盖用户已经开始编辑的内容。
    let active = true
    void loadThreadDraft(threadId)
      .then((record) => {
        if (!active || boundThreadIdRef.current !== threadId || record == null) {
          return
        }
        // 记录是权威：编辑区为空才采纳内容；用户先编辑过就保留用户输入。
        const adoptParts = !hasMessageContent(draftRef.current)
        const adoptGoal = (goalDraftRef.current ?? '').length === 0
        if (adoptParts) {
          draftRef.current = record.parts
          setDraftState(record.parts)
        }
        if (adoptGoal) {
          goalDraftRef.current = record.goalText
          setGoalDraftState(record.goalText)
        }
        // generation 只有真的采纳了记录内容（或记录里该字段本就没有内容可丢、内容已与编辑区一致）
        // 时才推进：只推进 generation 而不采纳内容，会让后续整份写入把已恢复文本永久覆盖掉。
        const partsAccounted = adoptParts
          || !hasMessageContent(record.parts)
          || partsKey(record.parts) === partsKey(draftRef.current)
        const goalAccounted = adoptGoal
          || (record.goalText ?? '').length === 0
          || record.goalText === goalDraftRef.current
        if (partsAccounted && goalAccounted) {
          observedStopRequestIdsRef.current = record.appliedStopRequestIds
          return
        }
        if (!partsAccounted) {
          partsWriteRef.current.dirty = true
        }
        if (!goalAccounted) {
          goalWriteRef.current.dirty = true
        }
        if (record.appliedStopRequestIds.length > 0) {
          // 有已恢复内容没被采纳：保留原 generation 并提示，等待既有回执通道合并进编辑区。
          setDraftRestoreError(translate('ai.runtime.action.draftWriteConflict'))
        }
      })
      .catch(() => {
        if (active && boundThreadIdRef.current === threadId) {
          // 存储不可用必须可见：编辑区当前内容未必是该 Thread 的最新草稿。
          setActionError(translate('ai.runtime.action.draftLoadFailed'))
        }
      })
    return () => {
      active = false
    }
  }, [threadId])

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

  /**
   * 草稿写入口。`edit` 落盘到该 Thread 的持久记录；`history`（上下键浏览）与
   * `restore`（Stop 回执已原子落盘）只更新内存，避免重复写与重复回填。
   */
  /** 编辑区是否存在未落盘的输入：存在时绝不拿记录覆盖编辑区。 */
  function hasUnsavedDraft(): boolean {
    return partsWriteRef.current.dirty || goalWriteRef.current.dirty
  }

  /**
   * 整份字段写入的公共收尾：写入携带已观察的恢复 generation，完成回调先过
   * originThreadId + epoch 栅栏（旧 Thread 的晚期完成绝不改新 Thread 的 refs / 提示）与
   * 字段内 token 栅栏（旧 completion 绝不把新编辑标成已保存），再决定收尾。
   */
  function persistDraftField(
    field: { token: number; dirty: boolean },
    save: () => Promise<DraftSaveOutcome>,
  ): void {
    const originThreadId = threadId
    const originEpoch = bindingEpochRef.current
    const token = field.token + 1
    field.token = token
    // 该字段最新一次写入的完成才代表编辑区内容已落盘；写入在途时仍按记录权威（同链 FIFO
    // 保证在途写入先于任何后续合并落盘，记录不会缺它的内容）。
    void save()
      .then((outcome) => {
        if (!isStillBound(originThreadId, originEpoch) || field.token !== token) {
          return
        }
        if (outcome.status === 'STALE') {
          // 被拒：编辑区内容确定不在记录里，标记未落盘。
          field.dirty = true
          handleDraftWriteConflict()
          return
        }
        field.dirty = false
      })
      .catch(() => {
        if (!isStillBound(originThreadId, originEpoch) || field.token !== token) {
          return
        }
        field.dirty = true
        setActionError(t('ai.runtime.action.draftSaveFailed'))
      })
  }

  function persistParts(parts: ComposerPart[]): void {
    persistDraftField(partsWriteRef.current, () =>
      saveThreadDraftParts(threadId, parts, observedStopRequestIdsRef.current))
  }

  function persistGoalText(goalText: string | null): void {
    persistDraftField(goalWriteRef.current, () =>
      saveThreadGoalText(threadId, goalText, observedStopRequestIdsRef.current))
  }

  function setDraft(
    next: ComposerPart[],
    source: 'edit' | 'history' | 'restore' = 'edit',
  ) {
    draftRevisionRef.current += 1
    if (source === 'edit') {
      persistParts(next)
    }
    draftRef.current = next
    setDraftState(next)
  }

  function setGoalDraft(next: string | null, source: 'edit' | 'restore' = 'edit') {
    draftRevisionRef.current += 1
    if (source === 'edit') {
      persistGoalText(next)
    }
    goalDraftRef.current = next
    setGoalDraftState(next)
  }

  /**
   * Stop 或确定失败后把未消费的本地草稿恢复到对应编辑区。已有内容的编辑器绝不覆盖；
   * 非当前绑定的 Thread 只写它自己的持久记录，子 Thread 草稿不会进入父输入框。
   */
  async function restorePendingDraft(
    originThreadId: string,
    originEpoch: number,
    pending: BoundPendingMessage,
  ): Promise<void> {
    try {
      if (pending.kind === 'MESSAGE') {
        const restored = await restoreThreadDraftParts(originThreadId, pending.localDraft)
        // await 之后重新校验身份：原 Thread 的失败消息绝不回填进新 Thread 的编辑区。
        if (restored && isStillBound(originThreadId, originEpoch) && !hasMessageContent(draftRef.current)) {
          draftRef.current = pending.localDraft
          setDraftState(pending.localDraft)
        }
        return
      }
      const goalText = pending.goalText
      if (goalText == null) {
        return
      }
      const restored = await restoreThreadGoalText(originThreadId, goalText)
      if (restored && isStillBound(originThreadId, originEpoch) && (goalDraftRef.current ?? '').length === 0) {
        goalDraftRef.current = goalText
        setGoalDraftState(goalText)
      }
    } catch {
      if (isStillBound(originThreadId, originEpoch)) {
        // 未消费的输入没能回到编辑区：明确告知用户，而不是当作已恢复。
        setActionError(t('ai.runtime.action.draftRestoreFailed'))
      }
    }
  }

  async function executePendingMessage(initialPending: BoundPendingMessage): Promise<void> {
    const originThreadId = threadId
    const originEpoch = bindingEpochRef.current
    const originBindingKey = `${originThreadId}:${originEpoch}`
    let currentPending = initialPending
    let currentRequest = initialPending.request

    inFlightBindingsRef.current.add(originBindingKey)
    setInFlightSubmissions((count) => count + 1)

    const isCurrentBound = () => isStillBound(originThreadId, originEpoch)

    try {
      for (let retryCount = 0; ; retryCount += 1) {
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
                expectedHeadEntryId: latest.thread.headEntryId,
                expectedNextCommandSequence: latest.thread.nextCommandSequence,
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
    persistParts([])
    draftRef.current = []
    setDraftState([])

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

  /** 用权威记录对齐编辑区；attachment 不落盘，因此保留当前页面仍持有的附件。 */
  function applyRecordToEditor(record: ThreadDraftRecord): void {
    const attachments = draftRef.current.filter((part) => part.type === 'attachment')
    const parts = attachments.length === 0 ? record.parts : [...record.parts, ...attachments]
    if (partsKey(parts) !== partsKey(draftRef.current)) {
      draftRef.current = parts
      setDraftState(parts)
    }
    if (record.goalText !== goalDraftRef.current) {
      goalDraftRef.current = record.goalText
      setGoalDraftState(record.goalText)
    }
  }

  /**
   * 按 (threadId, stopRequestId) 原子幂等合并一批 Stop 回执。
   *
   * 只有当前绑定 Thread（且 epoch 未变）的编辑区会被更新；后代 Thread 的回执写它们各自的
   * 持久记录，绝不进入父输入框。合并失败绝不静默：原回执保留下来供用户手动重试，并明确
   * 区分「Stop 已完成」与「草稿未恢复」。返回是否全部合并成功。
   */
  async function applyStopReceipts(
    receipts: readonly HarnessStoppedThreadReceiptDTO[],
    boundThreadId: string,
    epoch: number,
  ): Promise<boolean> {
    const failed: HarnessStoppedThreadReceiptDTO[] = []
    for (const receipt of receipts) {
      const revisionBefore = draftRevisionRef.current
      const key = `${receipt.threadId}:${receipt.stopRequestId}`
      const appliedInThisTab = appliedReceiptKeysRef.current.has(key)
      try {
        const record = await applyStopReceipt(receipt)
        // await 之后重新校验绑定身份：旧 Thread 的合并结果绝不写进新 Thread 的编辑区。
        if (receipt.threadId !== boundThreadId
          || boundThreadIdRef.current !== boundThreadId
          || bindingEpochRef.current !== epoch) {
          continue
        }
        appliedReceiptKeysRef.current.add(key)
        // 恢复内容已进入记录：本 UI 由此观察到这条回执的 generation。
        observedStopRequestIdsRef.current = record.appliedStopRequestIds
        if (appliedInThisTab) {
          // 本标签页此前已把这条回执写进编辑区：再次投递既不重复追加，也不回退用户随后的编辑。
          continue
        }
        if (!hasUnsavedDraft() && draftRevisionRef.current === revisionBefore) {
          // 编辑区没有未落盘的输入：无论是本次事务合并还是别的标签页已合并，记录都是权威结果。
          applyRecordToEditor(record)
          partsWriteRef.current.dirty = false
          goalWriteRef.current.dirty = false
          continue
        }
        // 合并期间用户又编辑过：不拿迟到结果覆盖用户输入，而是把回执内容与当前输入
        // 合并后沿同一条链落盘，让记录与界面一起收敛。
        const cancelledInputs = receipt.cancelledInputs ?? []
        setDraft(prependCancelledMessages(cancelledInputs, draftRef.current))
        const mergedGoal = mergeCancelledGoalTexts(cancelledInputs, goalDraftRef.current)
        if (mergedGoal !== goalDraftRef.current) {
          setGoalDraft(mergedGoal)
        }
      } catch {
        failed.push(receipt)
      }
    }
    if (!isStillBound(boundThreadId, epoch)) {
      // 旧 Thread 晚到的回执收尾不得污染新 Thread 的重试状态与提示。
      return failed.length === 0
    }
    if (failed.length > 0) {
      // 未恢复的回执不视为已处理：保留原回执，明确提示，等待可见的手动重试。
      failedReceiptsRef.current = failed
      setDraftRestoreError(t('ai.runtime.action.draftRestoreFailed'))
      return false
    }
    failedReceiptsRef.current = []
    if (receipts.length > 0) {
      // 真正处理过回执才清除提示：没有可投递回执时保留提示，避免把未同步状态显示成已恢复。
      setDraftRestoreError(null)
    }
    return true
  }

  /**
   * 整份写入被存储层判定为陈旧（记录里已有本 UI 尚未观察到的恢复内容）时的收尾：
   * 不清空、不回退用户输入，明确提示，并通过已有的 Stop 回执通道把恢复内容合并进编辑区；
   * 合并与随后的写入都会补齐已观察 generation。没有可用回执时保留提示等待快照带来回执，
   * 绝不静默丢弃编辑，也不覆盖记录里已恢复的内容。
   */
  function handleDraftWriteConflict(): void {
    setDraftRestoreError(t('ai.runtime.action.draftWriteConflict'))
    const unobserved = stopReceipts.filter(
      (receipt) => !observedStopRequestIdsRef.current.includes(receipt.stopRequestId),
    )
    if (unobserved.length === 0) {
      return
    }
    void applyStopReceipts(unobserved, threadId, bindingEpochRef.current)
  }

  /**
   * 可见的手动重试：重新加载原 Stop 回执再应用一次。优先失败过的回执，否则用当前快照回执；
   * 不做自动无限重试。
   */
  function retryDraftRestore(): void {
    const receipts = failedReceiptsRef.current.length > 0
      ? failedReceiptsRef.current
      : stopReceipts
    if (receipts.length === 0) {
      // 没有可重投的回执：保留提示，等待快照带来回执后再重试。
      return
    }
    void applyStopReceipts(receipts, threadId, bindingEpochRef.current)
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
        pendingStopRef.current = null
        setStopReplayPending(false)
        clearPendingStop(operationThreadId)
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

  // 快照通道补偿：刷新、多标签或 Stop 响应丢失后，用该 Thread 自己的持久回执恢复草稿。
  // 与 Stop 响应共用同一次原子幂等合并，因此同一回执不会重复回填。
  const stopReceiptSignature = stopReceipts
    .map((receipt) => `${receipt.threadId}:${receipt.stopRequestId}`)
    .join('|')
  useEffect(() => {
    if (!threadId || stopReceiptSignature.length === 0) {
      return
    }
    if (attemptedReceiptSignatureRef.current === stopReceiptSignature) {
      return
    }
    // 每个回执集合只自动尝试一次：失败不自动重试，改为保留原回执并提示用户手动重试。
    attemptedReceiptSignatureRef.current = stopReceiptSignature
    void applyStopReceipts(stopReceipts, threadId, bindingEpochRef.current)
    // 只按回执身份补偿；applyStopReceipts 每次渲染都是新引用。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stopReceiptSignature, threadId])

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
    goalDraft,
    draftRestoreError,
    retryDraftRestore,
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
