import { useEffect, useState } from 'react'
import { useQueryClient, type QueryClient } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import { presentConflict, type ConflictPresentation } from '@/shared/conflict/conflict-presenter'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'
import type { AgentRuntimeOwnerDTO } from '@/shared/api/contracts/ai-runtime'
import type { BranchDraft } from '@/features/chat/branch-draft'
import {
  clearStoredComposerDraft,
  storeComposerDraft,
} from '@/features/chat/composer/composer-draft'
import {
  hasMessageContent,
  slashQueryOf,
  trimMessageParts,
  type ComposerPart,
} from '@/features/chat/composer/composer-parts'
import {
  acceptanceCompletionApplies,
  buildAcceptanceRequest,
  buildGoalAcceptanceRequest,
  isDefiniteAcceptanceFailure,
  prependFrozenComposerParts,
  preserveCurrentBranchDraft,
  shouldRefreshAfterAcceptanceFailure,
  type FrozenCommandBatchRequest,
} from '@/features/chat/runtime/agent-pane/agent-pane-pipeline'
import {
  clearPendingAcceptance,
  inspectPendingAcceptanceSlot,
  isBoundTarget,
  isDraftHistoryTarget,
  isNewThreadTarget,
  samePaneTarget,
  savePendingAcceptance,
  type PaneTarget,
  type PendingAcceptance,
} from '@/features/chat/runtime/agent-pane'
import {
  errorMessage,
  type AgentPaneCapabilities,
  type PaneInteraction,
  type RenameKind,
} from '@/features/chat/runtime/root-thread/types'
import type {
  HarnessAcceptedCommandsDTO,
  HarnessSessionEntryDTO,
  HarnessThreadSnapshotDTO,
} from '@/shared/api/generated/contracts'

type Ref<T> = { current: T }

interface PaneAcceptanceOptions {
  owner: AgentRuntimeOwnerDTO | undefined
  ownerKey: string | null
  paneId: string
  target: PaneTarget
  targetRef: Ref<PaneTarget>
  setTarget: (next: PaneTarget) => void
  setFocusIntent: (next: PaneTarget | null) => void
  capabilities?: AgentPaneCapabilities
  onFocus?: () => void
  generationRef: Ref<number>
  /** 验收在途栅栏：由 root 持有，使 hasPendingOperation 在 state commit 前也能看到。 */
  pendingAcceptanceRef: Ref<PendingAcceptance | null>
  composerScope: string
  parts: ComposerPart[]
  partsRef: Ref<ComposerPart[]>
  setParts: (next: ComposerPart[]) => void
  setPartsState: (next: ComposerPart[]) => void
  localDraftRef: Ref<BranchDraft | null>
  setLocalDraft: (draft: BranchDraft | null) => void
  activeDraft: BranchDraft | null
  entryBaseDraft: BranchDraft | null
  draftHistoryReady: boolean
  draftHistoryEntries: HarnessSessionEntryDTO[]
  /** 已绑定 Thread 的提交走通用 command batch（controller 持有）。 */
  boundSubmit: {
    submitMessage: (payloadParts?: ComposerPart[], localDraftParts?: ComposerPart[]) => Promise<unknown> | unknown
    submitGoal: (goalText: string | null) => Promise<unknown> | unknown
  }
  hasPendingOperation: () => boolean
  setActionError: (message: string | null) => void
  setConflict: (conflict: ConflictPresentation | null) => void
  setInteraction: (next: PaneInteraction) => void
  openRename: (kind: RenameKind, id: string, name: string | null) => void
  setRenameError: (message: string | null) => void
}

interface PendingSlotView {
  pending: PendingAcceptance | null
  blocked: boolean
}

/** 只把与当前展示目标相同的 pending 恢复为可操作记录；其余占用只阻塞槽位。 */
function pendingSlotView(
  owner: AgentRuntimeOwnerDTO | undefined,
  paneId: string,
  target: PaneTarget,
): PendingSlotView {
  if (!owner) {
    return { pending: null, blocked: false }
  }
  const slot = inspectPendingAcceptanceSlot(owner, paneId)
  if (slot.status === 'occupied' && samePaneTarget(slot.pending.target, target)) {
    return {
      pending: { ...slot.pending, unknownOutcome: true },
      blocked: false,
    }
  }
  return { pending: null, blocked: slot.status === 'invalid' || slot.status === 'occupied' }
}

/**
 * 未绑定 Thread 的首次发送（NEW_SESSION / NEW_THREAD / NEW_FORKED_SESSION 草稿）：
 * 冻结请求、原子提交、未知结果恢复与确定失败回滚。成功后先种权威快照再切目标。
 */
export function usePaneAcceptance(options: PaneAcceptanceOptions) {
  const {
    owner, ownerKey, paneId, target, targetRef, setTarget, setFocusIntent, capabilities, onFocus,
    generationRef, pendingAcceptanceRef, composerScope, parts, partsRef, setParts, setPartsState,
    localDraftRef, setLocalDraft, activeDraft, entryBaseDraft, draftHistoryReady, draftHistoryEntries,
    boundSubmit, hasPendingOperation, setActionError, setConflict, setInteraction, openRename,
    setRenameError,
  } = options
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const [initialPendingSlot] = useState(() => pendingSlotView(owner, paneId, target))
  const [pendingAcceptance, setPendingAcceptance] = useState<PendingAcceptance | null>(() => {
    // 栅栏 ref 与 state 同步起步：首帧 hasPendingOperation 就必须看到恢复的未决验收。
    pendingAcceptanceRef.current = initialPendingSlot.pending
    return initialPendingSlot.pending
  })
  const [pendingAcceptanceBlocked, setPendingAcceptanceBlocked] = useState(
    initialPendingSlot.blocked,
  )

  useEffect(() => {
    pendingAcceptanceRef.current = pendingAcceptance
  }, [pendingAcceptance, pendingAcceptanceRef])

  // 展示目标切换后只恢复与原目标一致的记录；其他目标的 pending 保留在槽位中，
  // 用于阻塞新提交并提示切回原目标，不复制、不删除、不套用到当前面板。
  useEffect(() => {
    if (!owner) {
      return
    }
    if (pendingAcceptanceRef.current != null) {
      setPendingAcceptanceBlocked(false)
      return
    }
    const slot = inspectPendingAcceptanceSlot(owner, paneId)
    if (slot.status === 'occupied' && samePaneTarget(slot.pending.target, targetRef.current)) {
      const restored = { ...slot.pending, unknownOutcome: true }
      pendingAcceptanceRef.current = restored
      setPendingAcceptance(restored)
      setPendingAcceptanceBlocked(false)
      return
    }
    setPendingAcceptanceBlocked(slot.status === 'invalid' || slot.status === 'occupied')
  }, [owner, ownerKey, paneId, target, targetRef, pendingAcceptanceRef])

  /**
   * 提交前同步检查槽位：只允许空槽或当前目标自己的请求继续。其他目标/损坏槽位
   * 保持原记录并阻止新请求，避免 save 覆盖；切回原目标后本函数会恢复同一请求。
   */
  function blockSubmissionForForeignPending(): boolean {
    if (!owner) {
      return false
    }
    const slot = inspectPendingAcceptanceSlot(owner, paneId)
    if (slot.status === 'empty' || slot.status === 'unavailable') {
      if (slot.status === 'empty' && pendingAcceptanceRef.current == null) {
        setPendingAcceptanceBlocked(false)
      }
      return false
    }
    if (
      slot.status === 'occupied'
      && !isBoundTarget(targetRef.current)
      && samePaneTarget(slot.pending.target, targetRef.current)
    ) {
      if (pendingAcceptanceRef.current == null) {
        const restored = { ...slot.pending, unknownOutcome: true }
        pendingAcceptanceRef.current = restored
        setPendingAcceptance(restored)
      }
      setPendingAcceptanceBlocked(false)
      return false
    }
    setPendingAcceptanceBlocked(true)
    setActionError(t('ai.runtime.action.pendingAcceptanceBlocked'))
    return true
  }

  function abandonPendingAcceptance(): void {
    const pending = pendingAcceptanceRef.current
    if (pending != null) {
      setParts(prependFrozenComposerParts(pending.composerParts, partsRef.current))
      setLocalDraft(preserveCurrentBranchDraft(pending.branchDraft, localDraftRef.current))
    }
    generationRef.current += 1
    pendingAcceptanceRef.current = null
    setPendingAcceptance(null)
    if (owner && pending != null) {
      clearPendingAcceptance(owner, paneId, pending)
    }
    setActionError(null)
    setConflict(null)
  }

  function makePending(frozen: FrozenCommandBatchRequest): PendingAcceptance {
    return {
      owner: owner ? { ...owner } : { type: 'CHAT', chatId: '' },
      target: frozen.target,
      request: frozen.request,
      branchDraft: frozen.branchDraft,
      composerParts: frozen.composerParts,
      generation: generationRef.current,
      unknownOutcome: false,
    }
  }

  function startAcceptance(frozen: FrozenCommandBatchRequest, clearComposer: boolean): void {
    const pending = makePending(frozen)
    pendingAcceptanceRef.current = pending
    setPendingAcceptance(pending)
    if (owner && !savePendingAcceptance(owner, paneId, pending)) {
      // 另一个 tab 可能刚占用了同一槽位，或本 tab 无法持久化未决请求。两种情况都
      // 不能发送：失败响应将失去原请求对账资料。保留已有记录，不覆盖。
      pendingAcceptanceRef.current = null
      setPendingAcceptance(null)
      const slot = inspectPendingAcceptanceSlot(owner, paneId)
      if (slot.status === 'occupied' && samePaneTarget(slot.pending.target, targetRef.current)) {
        const restored = { ...slot.pending, unknownOutcome: true }
        pendingAcceptanceRef.current = restored
        setPendingAcceptance(restored)
        setPendingAcceptanceBlocked(false)
      } else {
        setPendingAcceptanceBlocked(slot.status === 'invalid' || slot.status === 'occupied')
      }
      setActionError(t(slot.status === 'invalid' || slot.status === 'occupied'
        ? 'ai.runtime.action.pendingAcceptanceBlocked'
        : 'ai.runtime.action.pendingAcceptanceNotSaved'))
      return
    }
    if (clearComposer) {
      setParts([])
    }
    void submitFrozenAcceptance(pending)
  }

  function submitGoal(
    goalText: string | null,
    submitOptions?: {
      localParts?: ComposerPart[]
    },
  ): void {
    if (capabilities?.readOnly) {
      return
    }
    if (blockSubmissionForForeignPending()) {
      return
    }
    if (hasPendingOperation()) {
      setActionError(t('ai.runtime.action.operationPending'))
      return
    }
    // 既有 Thread 走通用 thread command batch：与普通消息共用同一 CAS 游标与未决重试通道。
    if (isBoundTarget(target)) {
      onFocus?.()
      void boundSubmit.submitGoal(goalText)
      setInteraction(null)
      return
    }
    if (!owner) {
      return
    }
    const effectiveBase = entryBaseDraft
    const draft = activeDraft
    if (!draft || !effectiveBase || !draftHistoryReady) {
      setActionError(t('ai.runtime.action.threadNotLoaded'))
      return
    }
    try {
      const frozenParts = submitOptions?.localParts ?? parts
      const frozen = buildGoalAcceptanceRequest({
        owner,
        target,
        draft,
        base: effectiveBase,
        goalText,
        localParts: frozenParts,
      })
      onFocus?.()
      startAcceptance(frozen, false)
      setInteraction(null)
    } catch (error) {
      setActionError(errorMessage(error, t('ai.runtime.goal.submitFailed')))
    }
  }

  async function invalidateAcceptanceResult(threadId: string): Promise<void> {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) }),
      ...(ownerKey
        ? [
            queryClient.invalidateQueries({
              queryKey: ['agent-pane', 'sessions', ownerKey],
            }),
          ]
        : []),
    ])
  }

  /**
   * 验收成功后、切目标之前把权威 Thread 状态放进 snapshot 缓存。
   *
   * 新 Session 没有历史；新 Thread 沿用已验证的闭合前缀，直到 invalidate 取回
   * 权威快照。已有缓存的 Thread（重放）保持原样，不覆盖已加载的 Entry。
   */
  function seedAcceptedThreadSnapshot(
    client: QueryClient,
    response: HarnessAcceptedCommandsDTO,
  ): void {
    const key = queryKeys.threads.snapshot(response.thread.threadId)
    if (client.getQueryData<HarnessThreadSnapshotDTO>(key) != null) {
      return
    }
    client.setQueryData<HarnessThreadSnapshotDTO>(key, {
      version: response.thread.version,
      thread: response.thread,
      entries: isDraftHistoryTarget(targetRef.current) ? draftHistoryEntries : [],
      queuedCommands: response.acceptedCommands,
      executionStatus: 'IDLE',
      activeAssistantOutput: null,
      toolInvocations: [],
      generationFailures: [],
      manualCompaction: { available: false, disabledReason: null },
      stopReceipts: [],
      unconfirmedExecutions: [],
    })
  }

  async function refreshPaneProjection(): Promise<void> {
    const currentTarget = targetRef.current
    if (isBoundTarget(currentTarget)) {
      await queryClient.invalidateQueries({
        queryKey: queryKeys.threads.snapshot(currentTarget.threadId),
      })
    }
    if (ownerKey) {
      await queryClient.invalidateQueries({
        queryKey: ['agent-pane', 'sessions', ownerKey],
      })
    }
  }

  async function submitFrozenAcceptance(pending: PendingAcceptance): Promise<void> {
    try {
      const response = await harnessService.acceptCommandBatch(pending.request)
      const stillActive = acceptanceCompletionApplies(
        targetRef.current,
        pending,
        generationRef.current,
      )
      if (!stillActive) {
        await invalidateAcceptanceResult(response.thread.threadId)
        return
      }
      pendingAcceptanceRef.current = null
      setPendingAcceptance(null)
      if (owner) {
        clearPendingAcceptance(owner, paneId, pending)
      }
      generationRef.current += 1
      const bound: PaneTarget = { kind: 'BOUND_THREAD', threadId: response.thread.threadId }
      // 先种快照、再切目标：目标切换与执行根身份落在同一次 commit，根控制面（草稿与
      // 上传注册表）不会因为身份未知而瞬间卸载重建。
      seedAcceptedThreadSnapshot(queryClient, response)
      targetRef.current = bound
      setTarget(bound)
      setFocusIntent(bound)
      if (partsRef.current.length === 0) {
        clearStoredComposerDraft(composerScope)
        setPartsState([])
      } else {
        storeComposerDraft(composerScope, partsRef.current)
      }
      await invalidateAcceptanceResult(response.thread.threadId)
    } catch (error) {
      const currentPending = pendingAcceptanceRef.current
      const stillActive = currentPending != null
        && acceptanceCompletionApplies(
          targetRef.current,
          pending,
          generationRef.current,
        )
      if (!stillActive) {
        return
      }
      if (!isDefiniteAcceptanceFailure(error)) {
        const unknown = { ...pending, unknownOutcome: true }
        setPendingAcceptance(unknown)
        if (owner && !savePendingAcceptance(owner, paneId, unknown)) {
          const slot = inspectPendingAcceptanceSlot(owner, paneId)
          setActionError(t(slot.status === 'empty' || slot.status === 'unavailable'
            ? 'ai.runtime.action.pendingAcceptanceNotSaved'
            : 'ai.runtime.action.pendingAcceptanceBlocked'))
          return
        }
        setActionError(errorMessage(error, t('ai.runtime.action.requestFailed')))
        return
      }
      pendingAcceptanceRef.current = null
      setPendingAcceptance(null)
      if (owner) {
        clearPendingAcceptance(owner, paneId, pending)
      }
      setParts(prependFrozenComposerParts(pending.composerParts, partsRef.current))
      setLocalDraft(preserveCurrentBranchDraft(pending.branchDraft, localDraftRef.current))
      const presented = presentConflict(error)
      if (presented?.reason === 'THREAD_NAME_CONFLICT' && isNewThreadTarget(targetRef.current)) {
        openRename('thread', targetRef.current.sessionId, targetRef.current.threadName)
        setRenameError(t('ai.chat.branch.nameConflict'))
      } else if (presented != null) {
        setConflict(presented)
      } else {
        setActionError(errorMessage(error, t('ai.runtime.action.firstSendFailed')))
      }
      if (shouldRefreshAfterAcceptanceFailure(error)) {
        await refreshPaneProjection()
      }
    }
  }

  function retryAcceptance(): void {
    if (pendingAcceptance == null) {
      return
    }
    // generation 是当前 tab 的本地栅栏，不是请求身份；恢复的旧记录重试时刷新它，
    // 原 request/idempotencyKey 保持不变。
    const retry = { ...pendingAcceptance, unknownOutcome: false, generation: generationRef.current }
    setActionError(null)
    setConflict(null)
    pendingAcceptanceRef.current = retry
    setPendingAcceptance(retry)
    if (owner && !savePendingAcceptance(owner, paneId, retry)) {
      const slot = inspectPendingAcceptanceSlot(owner, paneId)
      setActionError(t(slot.status === 'empty' || slot.status === 'unavailable'
        ? 'ai.runtime.action.pendingAcceptanceNotSaved'
        : 'ai.runtime.action.pendingAcceptanceBlocked'))
      return
    }
    void submitFrozenAcceptance(retry)
  }

  function handleSubmit(payloadParts?: ComposerPart[], localDraftParts?: ComposerPart[]) {
    if (capabilities?.readOnly) {
      return
    }
    if (blockSubmissionForForeignPending()) {
      return
    }
    if (isBoundTarget(target)) {
      void boundSubmit.submitMessage(payloadParts, localDraftParts)
      return
    }
    if (!owner) {
      return
    }
    if (hasPendingOperation()) {
      setActionError(t('ai.runtime.action.operationPending'))
      return
    }
    const payload = trimMessageParts(payloadParts ?? parts)
    if (
      !hasMessageContent(payload)
      || slashQueryOf(payload) != null
      || activeDraft == null
      || !draftHistoryReady
      || entryBaseDraft == null
    ) {
      return
    }
    try {
      const frozen = buildAcceptanceRequest({
        owner,
        target,
        draft: activeDraft,
        base: entryBaseDraft,
        parts: payload,
        localParts: trimMessageParts(localDraftParts ?? parts),
      })
      onFocus?.()
      startAcceptance(frozen, true)
    } catch (error) {
      setActionError(errorMessage(error, t('ai.runtime.action.firstSendFailed')))
    }
  }

  return {
    pendingAcceptance,
    pendingAcceptanceBlocked,
    submitGoal,
    handleSubmit,
    retryAcceptance,
    abandonPendingAcceptance,
    refreshPaneProjection,
  }
}
