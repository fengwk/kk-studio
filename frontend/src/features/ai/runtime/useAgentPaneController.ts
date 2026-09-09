import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  useBoundBranchPanel,
} from '@/features/ai/runtime/useBoundBranchPanel'
import {
  useBoundThreadPanelLabels,
  useBoundThreadPanelViews,
  buildBoundThreadTranscript,
} from '@/features/ai/runtime/useBoundThreadPanelViews'
import type { ChatPanelComposerInput } from '@/features/ai/runtime/ChatPanel'
import type { ThreadCommand } from '@/features/ai/runtime/thread-panel/thread-commands'
import {
  materializeAgentBranchDraft,
  materializeBlankBranchDraft,
  type BranchDraft,
} from '@/features/ai/chat/branch-draft'
import {
  hasMessageContent,
  slashQueryOf,
  trimMessageParts,
  type ComposerPart,
} from '@/features/ai/composer/composer-parts'
import {
  clearStoredComposerDraft,
  restoreComposerDraft,
  storeComposerDraft,
} from '@/features/ai/composer/composer-draft'
import { harnessService } from '@/shared/api/harness-service'
import { chatService } from '@/shared/api/chat-service'
import { listCanvasSessions } from '@/shared/api/studio-service'
import {
  presentConflict,
  type ConflictPresentation,
} from '@/shared/conflict/conflict-presenter'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type {
  AgentRuntimeOwnerDTO,
  HarnessSessionEntryDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
  RuntimeSessionSummaryDTO,
  RuntimeThreadSummaryDTO,
} from '@/shared/api/contracts/ai-runtime'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import { useEnvironmentWorkspaceMetadata } from '@/features/ai/environment/useEnvironmentWorkspaceMetadata'
import {
  buildAcceptanceRequest,
  isDefiniteAcceptanceFailure,
  prependFrozenComposerParts,
  preserveCurrentBranchDraft,
  acceptanceCompletionApplies,
  shouldRefreshAfterAcceptanceFailure,
  type FrozenCommandBatchRequest,
} from '@/features/ai/runtime/agent-pane/agent-pane-pipeline'
import {
  clearPendingAcceptance,
  isBoundTarget,
  isNewThreadTarget,
  loadPaneTarget,
  loadPendingAcceptance,
  savePaneTarget,
  savePendingAcceptance,
  type PaneTarget,
  type PendingAcceptance,
} from '@/features/ai/runtime/agent-pane'
import { threadCommandsForTarget } from '@/features/ai/runtime/thread-panel/thread-commands'
import { useApplicationEvents } from '@/shared/app-events'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'
import { formatBackendDate } from '@/features/ai/chat/chat-utils'
import { parsePayload } from '@/features/ai/runtime/payload-json'

export type PaneInteraction =
  | 'agent'
  | 'environment'
  | 'shortcuts'
  | 'tree'
  | 'thread-sessions'
  | 'thread-threads'
  | 'rename-session'
  | 'rename-thread'
  | null

export type RenameKind = 'session' | 'thread'

/**
 * 单输入重命名面板的目标实体。name 是当前权威名称：thread 目标在 bound 快照
 * 就绪后直接得到；session 目标先为 null，owner Sessions 摘要 on-demand 到达后
 * 由 effect 填充。绝不把名称持久化进 PaneTarget。
 */
export interface RenameTarget {
  kind: RenameKind
  id: string
  /** 当前权威名称；null 表示仍在解析（panel 进入 busy 状态）。 */
  name: string | null
  /** 面板关闭后返回的交互；null 表示回到主面板。 */
  backTo: 'thread-sessions' | 'thread-threads' | null
}

export interface AgentPaneDefaults {
  agentName?: string
  workspacePath?: string | null
  yoloEnabled?: boolean
}

export interface UseAgentPaneControllerOptions {
  owner: AgentRuntimeOwnerDTO
  paneId: string
  agents: AgentDefinitionDTO[]
  environments: EnvironmentCardDTO[]
  defaults: AgentPaneDefaults
  focused: boolean
  onFocus?: () => void
}

export function useAgentPaneController({
  owner,
  paneId,
  agents,
  environments,
  defaults,
  focused,
  onFocus,
}: UseAgentPaneControllerOptions) {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const applicationEvents = useApplicationEvents()
  const composerScope = `agent-pane:${owner.type}:${owner.id}:${paneId}`
  const [target, setTargetState] = useState<PaneTarget>(
    () => loadPaneTarget(owner, paneId),
  )
  const [localDraft, setLocalDraft] = useState<BranchDraft | null>(null)
  const [parts, setPartsState] = useState<ComposerPart[]>(
    () => restoreComposerDraft(composerScope, []),
  )
  const [pendingAcceptance, setPendingAcceptance] = useState<PendingAcceptance | null>(
    () => {
      const pending = loadPendingAcceptance(owner, paneId)
      return pending == null ? null : { ...pending, unknownOutcome: true }
    },
  )
  const [interaction, setInteraction] = useState<PaneInteraction>(null)
  const [renameTarget, setRenameTargetState] = useState<RenameTarget | null>(null)
  const [renamePending, setRenamePending] = useState(false)
  const [renameError, setRenameError] = useState<string | null>(null)
  const [threadNavigationSessionId, setThreadNavigationSessionId] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [conflict, setConflict] = useState<ConflictPresentation | null>(null)
  const targetRef = useRef(target)
  const partsRef = useRef(parts)
  const localDraftRef = useRef<BranchDraft | null>(localDraft)
  const initializedEntryDraftRef = useRef<string | null>(null)
  const pendingAcceptanceRef = useRef<PendingAcceptance | null>(pendingAcceptance)
  const renameTargetRef = useRef<RenameTarget | null>(null)
  // rename PUT 在途栅栏：state commit 前也能让 changeTarget/handleCommand 看到
  // 重命名正在进行，从而阻塞外部 target 切换与并发命令（与 pendingAcceptance
  // 同栅栏，不持久化、不改变 pendingAcceptance 渲染语义）。
  const renamePendingRef = useRef(false)
  const generationRef = useRef(0)
  const previousBoundThreadRef = useRef<string | null>(null)
  const backgroundSubscriptionRef = useRef<{
    threadId: string
    unsubscribe: () => void
  } | null>(null)

  const boundThreadId = isBoundTarget(target) ? target.threadId : ''
  const branchPanel = useBoundBranchPanel({
    owner,
    threadId: boundThreadId,
  })
  const controller = branchPanel.controller
  const activeDraft = isBoundTarget(target) ? branchPanel.draft ?? null : localDraft
  const models = controller.models

  useEffect(() => {
    targetRef.current = target
    savePaneTarget(owner, paneId, target)
  }, [owner, paneId, target])

  useEffect(() => {
    partsRef.current = parts
  }, [parts])

  useEffect(() => {
    localDraftRef.current = localDraft
  }, [localDraft])

  useEffect(() => {
    pendingAcceptanceRef.current = pendingAcceptance
  }, [pendingAcceptance])

  useEffect(() => {
    if (isBoundTarget(target)) {
      return
    }
    if (localDraft != null || models.length === 0) {
      return
    }
    const preferred = defaults.agentName
      ? agents.find((agent) => agent.name === defaults.agentName)
      : agents.find((agent) =>
        materializeBlankBranchDraft(
          agent,
          defaults.yoloEnabled ?? false,
          models,
          defaults.workspacePath,
        ) != null,
      )
    const materialized = preferred == null
      ? null
      : materializeBlankBranchDraft(
        preferred,
        defaults.yoloEnabled ?? false,
        models,
        defaults.workspacePath,
      )
    if (materialized != null) {
      setLocalDraft(materialized)
    }
  }, [agents, defaults.agentName, defaults.workspacePath, defaults.yoloEnabled, localDraft, models, target])

  /**
   * Retain at most one inactive running Thread projection. The active Thread
   * owns its realtime subscription in useHarnessThreadRealtime; an inactive
   * terminal Thread never gets a background listener.
   */
  useEffect(() => {
    const previous = previousBoundThreadRef.current
    if (previous == null || previous === boundThreadId) {
      previousBoundThreadRef.current = boundThreadId || null
      return
    }
    const currentBackground = backgroundSubscriptionRef.current
    if (currentBackground != null && currentBackground.threadId !== previous) {
      currentBackground.unsubscribe()
      backgroundSubscriptionRef.current = null
    }
    if (backgroundSubscriptionRef.current == null) {
      const snapshot = queryClient.getQueryData<HarnessThreadSnapshotDTO>(
        queryKeys.threads.snapshot(previous),
      )
      if (needsBackgroundProjection(snapshot)) {
        const threadId = previous
        let unsubscribe: () => void = () => undefined
        const refresh = () => {
          const refreshPromise = queryClient.invalidateQueries({
            queryKey: queryKeys.threads.snapshot(threadId),
          })
          void refreshPromise.then(() => {
            const latest = queryClient.getQueryData<HarnessThreadSnapshotDTO>(
              queryKeys.threads.snapshot(threadId),
            )
            if (!needsBackgroundProjection(latest)
              && backgroundSubscriptionRef.current?.threadId === threadId) {
              unsubscribe()
              backgroundSubscriptionRef.current = null
            }
          })
        }
        unsubscribe = applicationEvents.subscribe(
          { kind: 'thread', id: threadId },
          {
            onSubscribed: refresh,
            onEvent: (name) => {
              if (name === 'version') {
                refresh()
              }
            },
            onResync: refresh,
            onError: refresh,
          },
        )
        backgroundSubscriptionRef.current = { threadId, unsubscribe }
      }
    }
    previousBoundThreadRef.current = boundThreadId || null
  }, [applicationEvents, boundThreadId, queryClient])

  useEffect(() => () => {
    backgroundSubscriptionRef.current?.unsubscribe()
    backgroundSubscriptionRef.current = null
  }, [])

  const currentSessionId = isBoundTarget(target)
    ? controller.sessionId
    : isNewThreadTarget(target)
      ? target.sessionId
      : null
  const sessionsQuery = useQuery({
    queryKey: ['agent-pane', 'sessions', owner.type, owner.id],
    queryFn: () => owner.type === 'CHAT'
      ? chatService.listChatSessions(owner.id)
      : listCanvasSessions(owner.id),
    enabled: interaction === 'thread-sessions' || interaction === 'rename-session',
  })
  const threadsQuery = useQuery({
    queryKey: ['agent-pane', 'threads', threadNavigationSessionId],
    queryFn: () => harnessService.listSessionThreads(threadNavigationSessionId!),
    enabled: interaction === 'thread-threads' && threadNavigationSessionId != null,
  })
  const treeEntriesQuery = useQuery({
    queryKey: ['agent-pane', 'entries', threadNavigationSessionId ?? currentSessionId],
    queryFn: () => harnessService.listSessionEntries(threadNavigationSessionId ?? currentSessionId!),
    enabled:
      (interaction === 'tree' || isNewThreadTarget(target))
      && (threadNavigationSessionId ?? currentSessionId) != null,
  })

  const entryBaseDraft = useMemo(() => {
    if (!isNewThreadTarget(target)) {
      return activeDraft
    }
    if (treeEntriesQuery.data == null) {
      return activeDraft
    }
    return branchDraftFromEntryPath(
      treeEntriesQuery.data,
      target.startEntryId,
      activeDraft,
    )
  }, [activeDraft, target, treeEntriesQuery.data])

  useEffect(() => {
    if (!isNewThreadTarget(target) || treeEntriesQuery.data == null || entryBaseDraft == null) {
      return
    }
    const identity = `${target.sessionId}:${target.startEntryId}`
    if (initializedEntryDraftRef.current === identity) {
      return
    }
    initializedEntryDraftRef.current = identity
    setLocalDraft(cloneDraft(entryBaseDraft))
  }, [entryBaseDraft, target, treeEntriesQuery.data])

  // session 重命名面板的 on-demand 名称解析：打开时可能尚无 owner sessions
  // 摘要；摘要到达后填充到 renameTarget.name（面板据此预填一次）。摘要加载
  // 失败或已成功但不含目标（并发删除/权限变化）都会终止 busy 态并提示错误，
  // 绝不把面板留在永久 busy。
  const sessionsLoadFailed = sessionsQuery.isError
    ? errorMessage(sessionsQuery.error, t('ai.runtime.action.requestFailed'))
    : null
  useEffect(() => {
    if (renameTarget == null || renameTarget.kind !== 'session' || renameTarget.name != null) {
      return
    }
    if (sessionsQuery.isError) {
      setRenameTargetState((current) =>
        current != null && current.kind === 'session' && current.name == null
          ? { ...current, name: '' }
          : current,
      )
      setRenameError(sessionsLoadFailed ?? t('ai.runtime.action.requestFailed'))
      return
    }
    if (sessionsQuery.data == null) {
      return
    }
    const summary = sessionsQuery.data.find((item) => item.sessionId === renameTarget.id)
    if (summary != null) {
      setRenameTargetState((current) =>
        current != null && current.id === renameTarget.id ? { ...current, name: summary.name } : current,
      )
      return
    }
    // 摘要已成功加载但目标不存在：名称无法解析，结束 busy 并提示。
    setRenameTargetState((current) =>
      current != null && current.kind === 'session' && current.name == null
        ? { ...current, name: '' }
        : current,
    )
    setRenameError(t('ai.runtime.rename.sessionUnavailable'))
    // eslint-disable-next-line react-hooks/exhaustive-deps -- t 是稳定 useCallback。
  }, [renameTarget, sessionsQuery.data, sessionsQuery.isError, sessionsLoadFailed])

  function setParts(next: ComposerPart[]) {
    partsRef.current = next
    storeComposerDraft(composerScope, next)
    setPartsState(next)
  }

  /** 打开指定 kind 的单输入重命名面板。name 为空/未知时为 null（解析态）。 */
  function openRename(
    kind: RenameKind,
    id: string,
    name: string | null,
    backTo: RenameTarget['backTo'] = null,
  ): void {
    setRenameTargetState({ kind, id, name, backTo })
    setRenameError(null)
    setRenamePending(false)
    setInteraction(kind === 'session' ? 'rename-session' : 'rename-thread')
  }

  /** 当前 target 可重命名的实体；不可用返回 null（矩阵保证可达才调用）。 */
  function renameTargetOf(kind: RenameKind): { id: string; name: string | null } | null {
    if (kind === 'thread') {
      if (isBoundTarget(target) && controller.thread?.threadId === target.threadId) {
        return { id: target.threadId, name: controller.thread.name }
      }
      return null
    }
    if (isNewThreadTarget(target)) {
      const summary = sessionsQuery.data?.find((item) => item.sessionId === target.sessionId)
      return { id: target.sessionId, name: summary?.name ?? null }
    }
    if (isBoundTarget(target) && controller.sessionId) {
      const summary = sessionsQuery.data?.find((item) => item.sessionId === controller.sessionId)
      return { id: controller.sessionId, name: summary?.name ?? null }
    }
    return null
  }

  function openRenameForTarget(kind: RenameKind): void {
    if (hasPendingOperation()) {
      setActionError(t('ai.runtime.action.operationPending'))
      return
    }
    const targetEntity = renameTargetOf(kind)
    if (targetEntity == null) {
      setActionError(kind === 'thread'
        ? t('ai.runtime.rename.threadUnavailable')
        : t('ai.runtime.rename.sessionUnavailable'))
      return
    }
    openRename(kind, targetEntity.id, targetEntity.name)
  }

  function closeRename(): void {
    const backTo = renameTarget?.backTo ?? null
    renameTargetRef.current = null
    renamePendingRef.current = false
    setRenameTargetState(null)
    setRenameError(null)
    setRenamePending(false)
    // 从 Session/Thread picker 行内进入时，关闭后返回该 picker。
    setInteraction(backTo)
  }

  async function submitRename(name: string): Promise<void> {
    if (renameTarget == null || renamePendingRef.current || hasPendingOperation()) {
      return
    }
    const trimmed = name.trim()
    if (!trimmed) {
      setRenameError(t('ai.runtime.rename.nameRequired'))
      return
    }
    setRenameError(null)
    setRenamePending(true)
    renamePendingRef.current = true
    const current = renameTarget
    renameTargetRef.current = current
    try {
      if (current.kind === 'session') {
        const renamed = await harnessService.renameSession(current.id, { name: trimmed })
        if (renameTargetRef.current !== current) {
          return
        }
        patchSessionNameCache(current.id, renamed.name)
      } else {
        const renamed = await harnessService.renameThread(current.id, { name: trimmed })
        if (renameTargetRef.current !== current) {
          return
        }
        patchThreadNameCache(current.id, renamed)
      }
      await invalidateAfterRename(current)
      closeRename()
    } catch (error) {
      if (renameTargetRef.current !== current) {
        return
      }
      renamePendingRef.current = false
      setRenameError(errorMessage(error, t('ai.runtime.rename.failed')))
      setRenamePending(false)
    }
  }

  /**
   * 更新已加载缓存中的 Session 名称（owner session summaries）。保持简单：
   * 直接 setQueryData 到已存在的 key，随后 invalidateAfterRename 做必要失效。
   */
  function patchSessionNameCache(sessionId: string, name: string): void {
    for (const [key, data] of queryClient.getQueriesData<RuntimeSessionSummaryDTO[]>({
      queryKey: ['agent-pane', 'sessions'],
    })) {
      if (data == null) {
        continue
      }
      queryClient.setQueryData<RuntimeSessionSummaryDTO[]>(
        key,
        data.map((item) => item.sessionId === sessionId ? { ...item, name } : item),
      )
    }
  }

  /** 重命名成功后失效相应查询：Session 摘要、Thread 投影与 Chat 列表。 */
  async function invalidateAfterRename(target: RenameTarget): Promise<void> {
    await Promise.all([
      queryClient.invalidateQueries({
        queryKey: ['agent-pane', 'sessions', owner.type, owner.id],
      }),
      ...(target.kind === 'thread'
        ? [
            queryClient.invalidateQueries({
              queryKey: ['agent-pane', 'threads'],
            }),
            queryClient.invalidateQueries({
              queryKey: queryKeys.threads.snapshot(target.id),
            }),
          ]
        : []),
      queryClient.invalidateQueries({ queryKey: queryKeys.chats.all }),
    ])
  }

  /**
   * 把 Thread 重命名成功响应 patch 进已加载的 snapshot/thread summary 缓存：
   * snapshot cache 的 thread 本体直接采纳权威响应（含规范化的 name）；thread
   * summary 只更新 name 字段。之后 invalidateAfterRename 会按需重新拉取权威数据。
   */
  function patchThreadNameCache(threadId: string, thread: HarnessThreadDTO): void {
    const snapshotKey = queryKeys.threads.snapshot(threadId)
    const snapshot = queryClient.getQueryData<HarnessThreadSnapshotDTO>(snapshotKey)
    if (snapshot != null && snapshot.thread.threadId === threadId) {
      queryClient.setQueryData<HarnessThreadSnapshotDTO>(
        snapshotKey,
        { ...snapshot, thread },
      )
    }
    for (const [key, data] of queryClient.getQueriesData<RuntimeThreadSummaryDTO[]>({
      queryKey: ['agent-pane', 'threads'],
    })) {
      if (data == null) {
        continue
      }
      queryClient.setQueryData<RuntimeThreadSummaryDTO[]>(
        key,
        data.map((item) => item.threadId === threadId ? { ...item, name: thread.name } : item),
      )
    }
  }

  /** 事件同步栅栏：state commit 前也能看到刚写入 pendingAcceptanceRef。 */
  function hasPendingOperation(): boolean {
    return renamePendingRef.current
      || pendingAcceptanceRef.current != null
      || controller.pending
      || controller.compactPending
      || controller.stopPending
      || controller.stopReplayPending
      || controller.approvalPending
      || controller.replayPending
      || controller.queuedCommands.length > 0
  }

  function changeTarget(next: PaneTarget, draft: BranchDraft | null = activeDraft): boolean {
    if (hasPendingOperation()) {
      setActionError(t('ai.runtime.action.operationPending'))
      return false
    }
    generationRef.current += 1
    targetRef.current = next
    setTargetState(next)
    setLocalDraft(draft == null ? null : cloneDraft(draft))
    setInteraction(null)
    setActionError(null)
    setConflict(null)
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
    clearPendingAcceptance(owner, paneId)
    setActionError(null)
    setConflict(null)
  }

  function makePending(frozen: FrozenCommandBatchRequest): PendingAcceptance {
    return {
      owner: { ...owner },
      target: frozen.target,
      request: frozen.request,
      branchDraft: frozen.branchDraft,
      composerParts: frozen.composerParts,
      generation: generationRef.current,
      unknownOutcome: false,
    }
  }

  function startAcceptance(frozen: FrozenCommandBatchRequest): void {
    const pending = makePending(frozen)
    pendingAcceptanceRef.current = pending
    setPendingAcceptance(pending)
    savePendingAcceptance(owner, paneId, pending)
    setParts([])
    void submitFrozenAcceptance(pending)
  }

  async function invalidateAcceptanceResult(threadId: string): Promise<void> {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) }),
      queryClient.invalidateQueries({
        queryKey: ['agent-pane', 'sessions', owner.type, owner.id],
      }),
    ])
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
      clearPendingAcceptance(owner, paneId)
      generationRef.current += 1
      const bound: PaneTarget = { kind: 'BOUND_THREAD', threadId: response.thread.threadId }
      targetRef.current = bound
      setTargetState(bound)
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
        savePendingAcceptance(owner, paneId, unknown)
        setActionError(errorMessage(error, t('ai.runtime.action.requestFailed')))
        return
      }
      pendingAcceptanceRef.current = null
      setPendingAcceptance(null)
      clearPendingAcceptance(owner, paneId)
      setParts(prependFrozenComposerParts(pending.composerParts, partsRef.current))
      setLocalDraft(preserveCurrentBranchDraft(pending.branchDraft, localDraftRef.current))
      const presented = presentConflict(error)
      if (presented != null) {
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
    const retry = { ...pendingAcceptance, unknownOutcome: false }
    setActionError(null)
    setConflict(null)
    pendingAcceptanceRef.current = retry
    setPendingAcceptance(retry)
    savePendingAcceptance(owner, paneId, retry)
    void submitFrozenAcceptance(retry)
  }

  function handleSubmit(payloadParts?: ComposerPart[], localDraftParts?: ComposerPart[]) {
    if (isBoundTarget(target)) {
      void controller.submitMessage(payloadParts, localDraftParts)
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
      || (isNewThreadTarget(target) && treeEntriesQuery.data == null)
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
      startAcceptance(frozen)
    } catch (error) {
      setActionError(errorMessage(error, t('ai.runtime.action.firstSendFailed')))
    }
  }

  function handleCommand(command: ThreadCommand): void {
    onFocus?.()
    if (command.disabled) {
      if (command.disabledReason) {
        setActionError(command.disabledReason)
      }
      return
    }
    switch (command.id) {
      case 'thread':
        if (hasPendingOperation()) {
          setActionError(t('ai.runtime.action.operationPending'))
          return
        }
        setInteraction('thread-sessions')
        return
      case 'tree':
        if (hasPendingOperation()) {
          setActionError(t('ai.runtime.action.operationPending'))
          return
        }
        if (currentSessionId == null) {
          setActionError(t('ai.runtime.action.threadNotLoaded'))
          return
        }
        setInteraction('tree')
        return
      case 'new':
        changeTarget({ kind: 'NEW_SESSION_DRAFT' }, activeDraft)
        return
      case 'agent':
        setInteraction('agent')
        return
      case 'environment':
        setInteraction('environment')
        return
      case 'yolo':
        if (isBoundTarget(target)) {
          branchPanel.setYoloEnabled(!(branchPanel.draft?.yoloEnabled ?? false))
        } else {
          setLocalDraft((current) =>
            current ? { ...current, yoloEnabled: !current.yoloEnabled } : current,
          )
        }
        return
      case 'debug':
        if (isBoundTarget(target)) {
          boundViews.switchMode(boundViews.mode === 'debug' ? 'conversation' : 'debug')
        }
        return
      case 'shortcuts':
        setInteraction('shortcuts')
        return
      case 'rename-session':
        openRenameForTarget('session')
        return
      case 'rename-thread':
        openRenameForTarget('thread')
        return
      case 'compact':
      case 'stop':
        controller.runCommand(command)
        return
      case 'models':
      case 'upload':
        return
    }
  }

  function selectAgent(agentName: string): void {
    const agent = agents.find((item) => item.name === agentName)
    if (agent == null) {
      setActionError(t('ai.runtime.action.agentUnresolvable', { selectedAgent: agentName }))
      return
    }
    if (isBoundTarget(target)) {
      if (!branchPanel.selectAgent(agentName)) {
        setActionError(t('ai.runtime.action.agentUnresolvable', { selectedAgent: agentName }))
        return
      }
    } else {
      const prevAgent = agents.find((item) => item.name === localDraft?.agentName)
      setLocalDraft((current) => materializeAgentBranchDraft(
        agent,
        models,
        current,
        defaults.yoloEnabled ?? false,
        defaults.workspacePath ?? null,
        prevAgent,
      ))
    }
    setInteraction(null)
    setActionError(null)
  }

  function selectWorkspacePath(workspacePath: string | null): void {
    if (isBoundTarget(target)) {
      branchPanel.selectWorkspacePath(workspacePath)
    } else {
      setLocalDraft((current) => current ? { ...current, workspacePath } : current)
    }
    setInteraction(null)
  }

  function selectEntry(entry: HarnessSessionEntryDTO): void {
    const draft = branchDraftFromEntry(entry, activeDraft)
    setThreadNavigationSessionId(null)
    changeTarget({
      kind: 'NEW_THREAD_DRAFT',
      sessionId: entry.sessionId,
      startEntryId: entry.entryId,
    }, draft)
  }

  function selectSession(session: RuntimeSessionSummaryDTO): void {
    setThreadNavigationSessionId(session.sessionId)
    setInteraction(session.threadCount === 0 ? 'tree' : 'thread-threads')
  }

  function selectThread(thread: RuntimeThreadSummaryDTO): void {
    changeTarget({ kind: 'BOUND_THREAD', threadId: thread.threadId }, activeDraft)
  }

  async function refreshPaneProjection(): Promise<void> {
    const currentTarget = targetRef.current
    if (isBoundTarget(currentTarget)) {
      await queryClient.invalidateQueries({
        queryKey: queryKeys.threads.snapshot(currentTarget.threadId),
      })
    }
    await queryClient.invalidateQueries({
      queryKey: ['agent-pane', 'sessions', owner.type, owner.id],
    })
  }

  const boundViews = useBoundThreadPanelViews(boundThreadId, controller)
  const boundLabels = useBoundThreadPanelLabels(environments, controller)
  const commands = threadCommandsForTarget(
    target,
    isBoundTarget(target) ? controller.manualCompaction : null,
  )
  // queuedCommands 不并入 Composer pending/disabled：运行中 Thread 仍应接受
  // 新 batch 并保留当前 draft 编辑；target 切换栅栏由 hasPendingOperation 独立维护。
  const pending = pendingAcceptance != null
    || controller.pending
    || controller.compactPending
    || controller.stopPending
    || controller.stopReplayPending
    || controller.approvalPending
    || controller.replayPending
  const composerDraft = isBoundTarget(target) ? controller.draft : parts
  const composer: ChatPanelComposerInput = {
    parts: composerDraft,
    pending,
    disabled:
      pending
      || (isBoundTarget(target)
        ? controller.disabled || branchPanel.branchState == null || branchPanel.effectiveBase == null
        : activeDraft == null || (isNewThreadTarget(target) && treeEntriesQuery.data == null)),
    onPartsChange: isBoundTarget(target) ? controller.setDraft : setParts,
    onHistoryPartsChange: isBoundTarget(target)
      ? (next) => controller.setDraft(next, 'history')
      : undefined,
    onSubmit: handleSubmit,
    onCommand: handleCommand,
    commands,
    focusOnEscape: focused,
    settings: activeDraft == null ? undefined : {
      model: activeDraft.model,
      models,
      yoloEnabled: activeDraft.yoloEnabled,
      onModelChange: (model) => {
        if (isBoundTarget(target)) {
          branchPanel.selectModel(model)
        } else {
          setLocalDraft((current) => current ? { ...current, model } : current)
        }
      },
      onYoloChange: (enabled) => {
        if (isBoundTarget(target)) {
          branchPanel.setYoloEnabled(enabled)
        } else {
          setLocalDraft((current) => current ? { ...current, yoloEnabled: enabled } : current)
        }
      },
    },
  }
  const workspacePath = activeDraft?.workspacePath ?? null
  const currentAgent = agents.find((item) => item.name === activeDraft?.agentName)
  const boundEnvironment = currentAgent?.environmentId
    ? environments.find((env) => env.id === currentAgent.environmentId) ?? null
    : null
  const environmentReady = boundEnvironment ? boundEnvironment.ready : undefined
  const environmentBinding = boundEnvironment
    ? { environmentId: boundEnvironment.id, workspacePath: workspacePath ?? '.' }
    : null
  const { gitBranch } = useEnvironmentWorkspaceMetadata(environmentBinding, environmentReady)
  const error = actionError
    ?? (isBoundTarget(target) ? branchPanel.yoloError ?? controller.actionError : null)
    ?? (isNewThreadTarget(target) && treeEntriesQuery.error
      ? errorMessage(treeEntriesQuery.error, t('ai.chat.history.loadFailed'))
      : null)
  const combinedConflict = conflict ?? controller.conflict ?? branchPanel.conflict

  return {
    target,
    activeDraft,
    currentSessionId,
    interaction,
    openInteraction: (next: Exclude<PaneInteraction, null>) => setInteraction(next),
    closeInteraction: () => setInteraction(null),
    renameTarget,
    renamePending,
    renameError,
    renameBusy: renameTarget != null
      && renameTarget.kind === 'session'
      && renameTarget.name == null
      && !sessionsQuery.isError,
    renameSession: (sessionId: string, name: string | null) => openRename('session', sessionId, name),
    renameThread: (threadId: string, name: string | null) => openRename('thread', threadId, name),
    openRenameWithBackTo: (
      kind: RenameKind,
      id: string,
      name: string | null,
      backTo: RenameTarget['backTo'],
    ) => openRename(kind, id, name, backTo),
    submitRename,
    closeRename,
    sessions: sessionsQuery.data ?? [],
    sessionsLoading: sessionsQuery.isLoading,
    threads: threadsQuery.data ?? [],
    threadsLoading: threadsQuery.isLoading,
    treeEntries: treeEntriesQuery.data ?? [],
    treeEntriesLoading: treeEntriesQuery.isLoading,
    treeEntriesError: treeEntriesQuery.error,
    controller,
    branchPanel,
    boundViews,
    boundLabels,
    composer,
    pendingAcceptance,
    pending,
    error,
    dismissActionError: () => setActionError(null),
    conflict: combinedConflict,
    dismissConflict: () => {
      setConflict(null)
      controller.dismissConflict()
      branchPanel.dismissConflict()
    },
    refreshPaneProjection,
    retryAcceptance,
    abandonPendingAcceptance,
    selectAgent,
    selectWorkspacePath,
    selectEntry,
    selectSession,
    selectThread,
    onDecideTaskApproval: (
      threadId: string,
      invocationId: string,
      decision: 'ALLOW' | 'DENY',
    ) => {
      void controller.decideApproval(invocationId, decision, threadId)
    },
    sessionSelectionItem,
    threadSelectionItem,
    buildBoundThreadTranscript,
    boundEnvironment,
    workspacePath,
    environmentReady,
    gitBranch,
  }
}

function needsBackgroundProjection(snapshot: HarnessThreadSnapshotDTO | undefined): boolean {
  return snapshot != null
    && (snapshot.thread.processing || snapshot.thread.status !== 'IDLE')
}

function cloneDraft(draft: BranchDraft): BranchDraft {
  return {
    ...draft,
    model: { ...draft.model },
  }
}

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof Error && error.message.trim()) {
    return error.message
  }
  return fallback
}

function branchDraftFromEntry(
  entry: HarnessSessionEntryDTO,
  fallback: BranchDraft | null,
): BranchDraft | null {
  if (fallback == null) {
    return null
  }
  const payload = parsePayload(entry.payloadJson)
  const settings = isRecord(payload.settings) ? payload.settings : null
  if (settings == null) {
    return cloneDraft(fallback)
  }
  const model = isRecord(settings.model) ? settings.model : null
  return {
    ...cloneDraft(fallback),
    workspacePath: decodeWorkspacePath(settings.workspacePath, fallback.workspacePath),
    agentName: typeof settings.agentName === 'string' ? settings.agentName : fallback.agentName,
    model: {
      providerName: typeof model?.providerName === 'string'
        ? model.providerName
        : fallback.model.providerName,
      modelName: typeof model?.modelName === 'string'
        ? model.modelName
        : fallback.model.modelName,
      variant: typeof model?.variant === 'string' ? model.variant : fallback.model.variant,
    },
  }
}

function branchDraftFromEntryPath(
  entries: HarnessSessionEntryDTO[],
  targetEntryId: string,
  fallback: BranchDraft | null,
): BranchDraft | null {
  if (fallback == null) {
    return null
  }
  const byId = new Map(entries.map((entry) => [entry.entryId, entry]))
  const path: HarnessSessionEntryDTO[] = []
  const visited = new Set<string>()
  let cursor: string | null = targetEntryId
  while (cursor != null && !visited.has(cursor)) {
    visited.add(cursor)
    const entry = byId.get(cursor)
    if (entry == null) {
      break
    }
    path.push(entry)
    cursor = entry.parentEntryId
  }
  let draft = cloneDraft(fallback)
  for (const entry of path.reverse()) {
    draft = branchDraftFromEntry(entry, draft) ?? draft
  }
  return draft
}

function decodeWorkspacePath(
  value: unknown,
  fallback: string | null,
): string | null {
  if (value === null) {
    return null
  }
  if (typeof value === 'string') {
    return value
  }
  return fallback
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

export function sessionSelectionItem(session: RuntimeSessionSummaryDTO) {
  return {
    id: session.sessionId,
    title: session.name,
    // 预览保持次要（subtitle）；主展示与搜索都以 name 为准，绝不回退为 id。
    subtitle: [
      session.firstMessagePreview || null,
      `${formatBackendDate(session.lastActivityAt)} · ${session.threadCount} Threads`,
    ].filter(Boolean).join(' · '),
    // UUID 只作 badge 诊断。
    badge: session.sessionId,
  }
}

export function threadSelectionItem(thread: RuntimeThreadSummaryDTO) {
  return {
    id: thread.threadId,
    title: thread.name,
    subtitle: [
      thread.headMessagePreview,
      `${formatBackendDate(thread.updatedAt)} · ${thread.status}`,
    ].filter(Boolean).join(' · '),
    badge: thread.threadId,
  }
}
