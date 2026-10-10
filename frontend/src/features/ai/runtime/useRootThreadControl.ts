import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import type { ThreadProjection } from '@/features/ai/runtime/useThreadProjection'
import {
  useBoundBranchPanel,
} from '@/features/ai/runtime/useBoundBranchPanel'
import { useBoundThreadPanelViews } from '@/features/ai/runtime/useBoundThreadPanelViews'
import type { ThreadPanelComposerInput } from '@/features/ai/runtime/thread-panel/ThreadPanel'
import type { ThreadCommand } from '@/features/ai/runtime/thread-panel/thread-commands'
import {
  branchDraftFromThread,
  branchDraftsEqual,
  materializeAgentBranchDraft,
  materializeBlankBranchDraft,
  type BranchDraft,
} from '@/features/ai/chat/branch-draft'
import { buildMessageBatchPlan } from '@/features/ai/chat/command-batch-plan'
import { formatPreviewErrorMessage } from '@/features/ai/runtime/preview-reasons'
import type {
  ComposerPreviewReadiness,
  ThreadComposerHandle,
} from '@/features/ai/runtime/thread-panel'
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
} from '@/features/ai/composer/composer-draft'
import { harnessService } from '@/shared/api/harness-service'
import { chatService } from '@/shared/api/chat-service'
import {
  presentConflict,
  type ConflictPresentation,
} from '@/shared/conflict/conflict-presenter'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type {
  AgentCommandBatchResponseDTO,
  AgentRuntimeOwnerDTO,
  HarnessSessionEntryDTO,
  HarnessThreadDTO,
  HarnessThreadSnapshotDTO,
  RuntimeSessionSummaryDTO,
  RuntimeThreadSummaryDTO,
} from '@/shared/api/contracts/ai-runtime'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'
import {
  buildAcceptanceRequest,
  buildGoalAcceptanceRequest,
  isDefiniteAcceptanceFailure,
  prependFrozenComposerParts,
  preserveCurrentBranchDraft,
  acceptanceCompletionApplies,
  shouldRefreshAfterAcceptanceFailure,
  type FrozenCommandBatchRequest,
} from '@/features/ai/runtime/agent-pane/agent-pane-pipeline'
import {
  parseGoalProgress,
  type BranchGoalProgressResult,
} from '@/features/ai/runtime/goal-progress'
import {
  clearPendingAcceptance,
  isBoundTarget,
  isDraftHistoryTarget,
  isNewThreadTarget,
  loadPendingAcceptance,
  ownerIdentity,
  savePendingAcceptance,
  samePaneTarget,
  type PaneTarget,
  type PendingAcceptance,
} from '@/features/ai/runtime/agent-pane'
import { threadCommandsForTarget } from '@/features/ai/runtime/thread-panel/thread-commands'
import { useApplicationEvents } from '@/shared/app-events'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'
import { formatBackendDate } from '@/features/ai/chat/chat-utils'
import { parsePayload } from '@/features/ai/runtime/payload-json'
import { normalizeThreadName } from '@/features/ai/chat/thread-name'
import { threadDraftPath } from '@/features/ai/chat/thread-draft-path'
import { buildThreadTimeline } from '@/features/ai/runtime/thread-timeline'
import { buildThreadEventTimeline } from '@/features/ai/runtime/thread-events'
import { paneTargetViewKey } from './thread-presentation'

export type PaneInteraction =
  | 'subagent'
  | 'agent'
  | 'shortcuts'
  | 'history'
  | 'thread-sessions'
  | 'thread-threads'
  | 'rename-session'
  | 'rename-thread'
  | 'goal'
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

export interface AgentPaneCapabilities {
  allowNewSession?: boolean
  readOnly?: boolean
  allowSwitchAgent?: boolean
  allowBranching?: boolean
}

export interface AgentPaneDefaults {
  agentName?: string
  yoloEnabled?: boolean
}

/** 新建分支请求：命名与目标 pane 由 Chat workspace 统一持有。 */
export interface BranchRequestInput {
  sessionId: string
  startEntryId: string
  /** 触发分支的 pane 当前分支名（用于目标选择与提示）；未知时为 null。 */
  sourceLabel: string | null
}

/**
 * 面板运行时摘要。只用于 workspace 的目标路由（草稿确认/在途阻止）与顶栏面包屑，
 * 不参与任何持久化，也不是跨 pane 的 storage sidechannel。
 */
export interface PaneReport {
  target: PaneTarget
  draftKey: string
  pending: boolean
  hasUnsentDraft: boolean
  sessionId: string | null
  branchName: string | null
}

export interface UseRootThreadControlOptions {
  owner?: AgentRuntimeOwnerDTO
  paneId: string
  agents: AgentDefinitionDTO[]
  environments: EnvironmentCardDTO[]
  defaults: AgentPaneDefaults
  focused: boolean
  covered?: boolean
  focusTarget?: PaneTarget | null
  onFocusTargetChange?: (target: PaneTarget | null) => void
  onFocus?: () => void
  initialTarget?: PaneTarget
  onTargetConsumed?: (target: PaneTarget) => void
  capabilities?: AgentPaneCapabilities
  /** 新建分支入口：由 Chat workspace 提供；缺失时分支动作不可用（只读/非 Chat 宿主）。 */
  onRequestBranch?: (request: BranchRequestInput) => void
  /** 面板运行时摘要上报；缺失时不产生任何跨 pane 通信。 */
  onReport?: (report: PaneReport) => void
  onValidateDraftName?: (target: PaneTarget, name: string) => Promise<string | null>
  /** 由父面板持有的 Pane 绑定目标；本 Hook 只读取并请求切换。 */
  target: PaneTarget
  setTarget: (next: PaneTarget) => void
  /** 父面板已持有的该目标 Thread 只读投影；不重复查询与订阅。 */
  projection: ThreadProjection
}

export function useRootThreadControl({
  owner,
  paneId,
  target,
  setTarget,
  projection,
  agents,
  environments,
  defaults,
  focused,
  covered = false,
  focusTarget: focusIntent = null,
  onFocusTargetChange,
  onFocus,
  initialTarget,
  onTargetConsumed,
  capabilities,
  onRequestBranch,
  onReport,
  onValidateDraftName,
}: UseRootThreadControlOptions) {
  const { t } = useI18n()
  const queryClient = useQueryClient()
  const applicationEvents = useApplicationEvents()
  const ownerKey = owner ? ownerIdentity(owner) : null
  const composerScope = ownerKey
    ? `agent-pane:${ownerKey}:${paneId}`
    : `agent-pane:thread:${paneId}`
  const [localDraft, setLocalDraft] = useState<BranchDraft | null>(null)
  // 未创建分支草稿是否被用户真的改过：只有存在 diff 才展示 draft 状态，默认不做常驻噪声。
  const [localDraftEdited, setLocalDraftEdited] = useState(false)
  const [parts, setPartsState] = useState<ComposerPart[]>(
    () => restoreComposerDraft(composerScope, []),
  )
  const [pendingAcceptance, setPendingAcceptance] = useState<PendingAcceptance | null>(
    () => {
      if (!owner) {
        return null
      }
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
  // target 由父面板持有；此处保留同步镜像，使 changeTarget/验收完成等路径在 state
  // commit 之前也能看到刚请求的绑定（与既有同步栅栏语义一致）。
  const targetRef = useRef(target)
  useEffect(() => {
    targetRef.current = target
  }, [target])
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

  const isMountedRef = useRef(true)
  useEffect(() => {
    isMountedRef.current = true
    return () => {
      isMountedRef.current = false
    }
  }, [])

  const [previewLoading, setPreviewLoading] = useState(false)
  const [previewError, setPreviewError] = useState<string | null>(null)
  const composerRef = useRef<ThreadComposerHandle | null>(null)
  const setFocusIntent = useCallback((next: PaneTarget | null) => {
    onFocusTargetChange?.(next)
  }, [onFocusTargetChange])
  const [composerReadiness, setComposerReadiness] = useState<ComposerPreviewReadiness>({
    canPreview: false,
    reason: 'EMPTY_DRAFT',
  })
  const previewRequestIdRef = useRef(0)
  const previewInFlightRef = useRef(false)

  const boundThreadId = isBoundTarget(target) ? target.threadId : ''

  // 视图身份与预览作用域同源：绑定 Thread 用 threadId，本地分支草稿用
  // sessionId:startEntryId:name（新建 Session 草稿没有身份可言，固定为空串）。
  // 身份变化即作废在途预览，并重置该 Pane 的本地视图状态（Debug 模式/滚动/检查器选中）。
  const previewScope = paneTargetViewKey(target)
  useEffect(() => {
    previewRequestIdRef.current += 1
    previewInFlightRef.current = false
    setPreviewLoading(false)
    setPreviewError(null)
  }, [previewScope])
  const branchPanel = useBoundBranchPanel({
    threadId: boundThreadId,
    projection,
  })
  const controller = branchPanel.controller
  const controllerRef = useRef(controller)
  const branchPanelRef = useRef(branchPanel)
  useEffect(() => {
    controllerRef.current = controller
    branchPanelRef.current = branchPanel
  })
  const activeDraft = isBoundTarget(target) ? branchPanel.draft ?? null : localDraft
  const boundThreadName = isBoundTarget(target) && controller.thread?.threadId === target.threadId
    ? controller.thread.name
    : null
  const models = controller.models

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
        ) != null,
      )
    const materialized = preferred == null
      ? null
      : materializeBlankBranchDraft(
        preferred,
        defaults.yoloEnabled ?? false,
        models,
      )
    if (materialized != null) {
      setLocalDraft(materialized)
    }
  }, [agents, defaults.agentName, defaults.yoloEnabled, localDraft, models, target])

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
        if (applicationEvents != null) {
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
  // 历史路径读取的 Session：草稿历史目标（NEW_THREAD_DRAFT / FORK_SESSION_DRAFT）直接来自
  // 目标携带的源 Session；绑定 Thread 用 on-demand 导航或当前 Session。
  const historySessionId = isDraftHistoryTarget(target)
    ? target.sessionId
    : threadNavigationSessionId ?? currentSessionId
  const sessionsQuery = useQuery<RuntimeSessionSummaryDTO[]>({
    queryKey: ['agent-pane', 'sessions', ownerKey],
    queryFn: () => {
      if (!owner) {
        return Promise.resolve([])
      }
      if (owner.type === 'CHAT') {
        return chatService.listChatSessions(owner.chatId)
      }
      return Promise.resolve([])
    },
    enabled: owner?.type === 'CHAT' && (interaction === 'thread-sessions' || interaction === 'rename-session'),
  })
  const threadsQuery = useQuery({
    queryKey: ['agent-pane', 'threads', threadNavigationSessionId],
    queryFn: () => harnessService.listSessionThreads(threadNavigationSessionId!),
    enabled: interaction === 'thread-threads' && threadNavigationSessionId != null,
  })
  const treeEntriesQuery = useQuery({
    queryKey: ['agent-pane', 'entries', historySessionId],
    queryFn: () => harnessService.listSessionEntries(historySessionId!),
    enabled:
      (interaction === 'history' || isDraftHistoryTarget(target))
      && historySessionId != null,
  })

  const draftHistory = useMemo(() => {
    if (!isDraftHistoryTarget(target) || treeEntriesQuery.data == null || treeEntriesQuery.isError) {
      return { entries: [], error: null }
    }
    try {
      return { entries: threadDraftPath(treeEntriesQuery.data, target.sessionId, target.startEntryId), error: null }
    } catch {
      return { entries: [], error: new Error(t('ai.chat.branch.historyInvalid')) }
    }
  }, [target, treeEntriesQuery.data, treeEntriesQuery.isError, t])
  const draftHistoryReady = !isDraftHistoryTarget(target)
    || (treeEntriesQuery.data != null && !treeEntriesQuery.isError && draftHistory.error == null)
  const draftTimeline = useMemo(() => buildThreadTimeline(draftHistory.entries, [], []), [draftHistory.entries])
  const draftEvents = useMemo(() => buildThreadEventTimeline({
    entries: draftHistory.entries,
    modelInvocation: null,
    toolInvocations: [],
    modelAttemptFailures: [],
    modelStream: null,
    toolStreams: null,
  }), [draftHistory.entries])

  const entryBaseDraft = useMemo(() => {
    if (!isDraftHistoryTarget(target)) {
      return activeDraft
    }
    if (!draftHistoryReady) {
      return null
    }
    return draftHistory.entries.reduce<BranchDraft | null>((draft, entry) => branchDraftFromEntry(entry, draft), activeDraft)
  }, [activeDraft, target, draftHistory, draftHistoryReady])

  useEffect(() => {
    if (!isDraftHistoryTarget(target) || treeEntriesQuery.data == null || entryBaseDraft == null) {
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

  const setParts = useCallback((next: ComposerPart[]) => {
    partsRef.current = next
    storeComposerDraft(composerScope, next)
    setPartsState(next)
  }, [composerScope])

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
      if (isNewThreadTarget(target)) {
        return { id: target.sessionId, name: target.threadName }
      }
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
    if (renamePendingRef.current) {
      return
    }
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
    const trimmed = normalizeThreadName(name)
    if (trimmed == null) {
      setRenameError(t('ai.runtime.rename.nameRequired'))
      return
    }
    setRenameError(null)
    setRenamePending(true)
    renamePendingRef.current = true
    const current = renameTarget
    renameTargetRef.current = current
    try {
      if (current.kind === 'thread' && isNewThreadTarget(target)) {
        const frozenTarget = target
        const nameError = onValidateDraftName == null
          ? t('ai.chat.branch.nameCheckFailed')
          : await onValidateDraftName(frozenTarget, trimmed)
        if (!isMountedRef.current || renameTargetRef.current !== current
          || !samePaneTarget(targetRef.current, frozenTarget)) {
          return
        }
        if (nameError != null) {
          renamePendingRef.current = false
          setRenamePending(false)
          setRenameError(nameError)
          return
        }
        const next = { ...target, threadName: trimmed }
        targetRef.current = next
        setTarget(next)
        generationRef.current += 1
        renamePendingRef.current = false
        closeRename()
        setActionError(null)
        setFocusIntent(next)
        return
      }
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
      renamePendingRef.current = false
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
    if (!ownerKey) {
      return
    }
    for (const [key, data] of queryClient.getQueriesData<RuntimeSessionSummaryDTO[]>({
      queryKey: ['agent-pane', 'sessions', ownerKey],
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
      ...(ownerKey
        ? [
            queryClient.invalidateQueries({
              queryKey: ['agent-pane', 'sessions', ownerKey],
            }),
          ]
        : []),
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
  const hasPendingOperation = useCallback((): boolean => {
    return renamePendingRef.current
      || pendingAcceptanceRef.current != null
      || controller.pending
      || controller.compactPending
      || controller.stopPending
      || controller.stopReplayPending
      || controller.approvalPending
      || controller.replayPending
      || controller.queuedCommands.length > 0
  }, [
    controller.approvalPending,
    controller.compactPending,
    controller.pending,
    controller.queuedCommands.length,
    controller.replayPending,
    controller.stopPending,
    controller.stopReplayPending,
  ])

  const changeTarget = useCallback(
    (next: PaneTarget, draft: BranchDraft | null = activeDraft): boolean => {
      // 容器与列表属于 owner 范围：无 owner 的面板没有可导航的 Session/Thread 容器。
      if (!owner) {
        return false
      }
      if (hasPendingOperation()) {
        setActionError(t('ai.runtime.action.operationPending'))
        return false
      }
      generationRef.current += 1
      initializedEntryDraftRef.current = null
      targetRef.current = next
      setTarget(next)
      setFocusIntent(next)
      setLocalDraft(draft == null ? null : cloneDraft(draft))
      setLocalDraftEdited(false)
      setInteraction(null)
      setActionError(null)
      setConflict(null)
      return true
    },
    [activeDraft, hasPendingOperation, owner, setTarget, setFocusIntent, t],
  )

  /**
   * 外部（deep-link/workspace 路由）目标应用边界：与本地切换共用同一门禁，
   * 并在落入新建分支草稿时清空该 pane 既有的未发送输入（新分支是全新草稿）。
   */
  const applyExternalTarget = useCallback(
    (next: PaneTarget): boolean => {
      if (!changeTarget(next)) {
        return false
      }
      // 新分支/会话 fork 都是全新草稿：清空该 pane 既有的未发送输入。
      if (isDraftHistoryTarget(next)) {
        setParts([])
      }
      return true
    },
    [changeTarget, setParts],
  )

  useEffect(() => {
    if (!initialTarget) {
      return
    }
    if (samePaneTarget(target, initialTarget)) {
      onTargetConsumed?.(initialTarget)
      return
    }
    if (hasPendingOperation()) {
      setActionError(t('ai.runtime.action.operationPending'))
      return
    }
    if (applyExternalTarget(initialTarget)) {
      onTargetConsumed?.(initialTarget)
    }
  }, [applyExternalTarget, hasPendingOperation, initialTarget, onTargetConsumed, t, target])

  function abandonPendingAcceptance(): void {
    const pending = pendingAcceptanceRef.current
    if (pending != null) {
      setParts(prependFrozenComposerParts(pending.composerParts, partsRef.current))
      setLocalDraft(preserveCurrentBranchDraft(pending.branchDraft, localDraftRef.current))
    }
    generationRef.current += 1
    pendingAcceptanceRef.current = null
    setPendingAcceptance(null)
    if (owner) {
      clearPendingAcceptance(owner, paneId)
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

  function startAcceptance(frozen: FrozenCommandBatchRequest): void {
    const pending = makePending(frozen)
    pendingAcceptanceRef.current = pending
    setPendingAcceptance(pending)
    if (owner) {
      savePendingAcceptance(owner, paneId, pending)
    }
    setParts([])
    void submitFrozenAcceptance(pending)
  }

  function startGoalAcceptance(frozen: FrozenCommandBatchRequest): void {
    const pending = makePending(frozen)
    pendingAcceptanceRef.current = pending
    setPendingAcceptance(pending)
    if (owner) {
      savePendingAcceptance(owner, paneId, pending)
    }
    void submitFrozenAcceptance(pending)
  }

  function submitGoal(
    goalText: string | null,
    options?: {
      localParts?: ComposerPart[]
    },
  ): void {
    if (capabilities?.readOnly) {
      return
    }
    if (hasPendingOperation()) {
      setActionError(t('ai.runtime.action.operationPending'))
      return
    }
    // 既有 Thread 走通用 thread command batch：与普通消息共用同一 CAS 游标与未决重试通道。
    if (isBoundTarget(target)) {
      onFocus?.()
      void controller.submitGoal(goalText)
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
      const frozenParts = options?.localParts ?? parts
      const frozen = buildGoalAcceptanceRequest({
        owner,
        target,
        draft,
        base: effectiveBase,
        goalText,
        localParts: frozenParts,
      })
      onFocus?.()
      startGoalAcceptance(frozen)
      setInteraction(null)
    } catch (error) {
      setActionError(errorMessage(error, t('ai.runtime.goal.submitFailed')))
    }
  }

  function clearGoal(): void {
    submitGoal(null)
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
    response: AgentCommandBatchResponseDTO,
  ): void {
    const key = queryKeys.threads.snapshot(response.thread.threadId)
    if (client.getQueryData<HarnessThreadSnapshotDTO>(key) != null) {
      return
    }
    client.setQueryData<HarnessThreadSnapshotDTO>(key, {
      version: response.thread.version,
      thread: response.thread,
      entries: isDraftHistoryTarget(targetRef.current) ? draftHistory.entries : [],
      queuedCommands: response.acceptedCommands,
      modelInvocation: null,
      toolInvocations: [],
      modelAttemptFailures: [],
      manualCompaction: { available: false, disabledReason: null },
      stopReceipts: [],
    })
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
        clearPendingAcceptance(owner, paneId)
      }
      generationRef.current += 1
      const bound: PaneTarget = { kind: 'BOUND_THREAD', threadId: response.thread.threadId }
      // 先种快照、再切目标：目标切换与执行根身份落在同一次 commit，根控制面（草稿与
      // 上传注册表）不会因为身份未知而瞬间卸载重建。
      seedAcceptedThreadSnapshot(queryClient, response)
      targetRef.current = bound
      setTarget(bound)
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
        if (owner) {
          savePendingAcceptance(owner, paneId, unknown)
        }
        setActionError(errorMessage(error, t('ai.runtime.action.requestFailed')))
        return
      }
      pendingAcceptanceRef.current = null
      setPendingAcceptance(null)
      if (owner) {
        clearPendingAcceptance(owner, paneId)
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
    const retry = { ...pendingAcceptance, unknownOutcome: false }
    setActionError(null)
    setConflict(null)
    pendingAcceptanceRef.current = retry
    setPendingAcceptance(retry)
    if (owner) {
      savePendingAcceptance(owner, paneId, retry)
    }
    void submitFrozenAcceptance(retry)
  }

  function handleSubmit(payloadParts?: ComposerPart[], localDraftParts?: ComposerPart[]) {
    if (capabilities?.readOnly) {
      return
    }
    if (isBoundTarget(target)) {
      void controller.submitMessage(payloadParts, localDraftParts)
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
      startAcceptance(frozen)
    } catch (error) {
      setActionError(errorMessage(error, t('ai.runtime.action.firstSendFailed')))
    }
  }

  /**
   * 本地新建分支草稿的预检：只调用会话级预览端点，绝不为了预览先创建 Thread。
   * 命令批次与真正的首次提交完全一致（settings diff + USER_MESSAGE）。
   */
  async function previewLocalBranchDraft(
    frozenPayload: ComposerPart[],
    frozenLocalDraft: ComposerPart[],
  ) {
    if (!owner || !isDraftHistoryTarget(target)) {
      return
    }
    const frozenTarget = target
    const frozenBranchDraft = activeDraft ? cloneDraft(activeDraft) : null
    const frozenBase = entryBaseDraft ? cloneDraft(entryBaseDraft) : null
    if (!frozenBranchDraft || !frozenBase || !draftHistoryReady) {
      return
    }
    const requestId = ++previewRequestIdRef.current
    const requestPartsKey = partsKey(frozenLocalDraft)
    previewInFlightRef.current = true
    setPreviewLoading(true)
    setPreviewError(null)
    setActionError(null)
    const isCurrentDraftPreview = () => isMountedRef.current
      && previewRequestIdRef.current === requestId
      && samePaneTarget(targetRef.current, frozenTarget)
      && partsKey(trimMessageParts(partsRef.current)) === requestPartsKey
      && localDraftRef.current != null
      && branchDraftsEqual(localDraftRef.current, frozenBranchDraft)
    try {
      const frozen = buildAcceptanceRequest({
        owner,
        target: frozenTarget,
        draft: frozenBranchDraft,
        base: frozenBase,
        parts: frozenPayload,
        localParts: frozenLocalDraft,
      })
      const response = await harnessService.previewBranchRequest(frozenTarget.sessionId, {
        startEntryId: frozenTarget.startEntryId,
        commands: frozen.request.commands,
      })
      if (!isCurrentDraftPreview()) {
        return
      }
      if (response.kind !== 'DRAFT_REQUEST_PREVIEW') {
        throw new Error(t('ai.runtime.debug.previewFailed'))
      }
      boundViewsRef.current?.selectDebugInspector({ type: 'preview', preview: response })
    } catch (error) {
      if (!isCurrentDraftPreview()) {
        return
      }
      const msg = formatPreviewErrorMessage(error, t)
      setPreviewError(msg)
      setActionError(msg)
    } finally {
      if (isMountedRef.current && previewRequestIdRef.current === requestId) {
        previewInFlightRef.current = false
        setPreviewLoading(false)
      }
    }
  }

  async function handlePreview() {
    if (capabilities?.readOnly) {
      return
    }
    if (!isBoundTarget(target) && !isDraftHistoryTarget(target)) {
      return
    }
    if (previewInFlightRef.current || previewDisabled) {
      return
    }

    const prepared = composerRef.current?.preparePreview()
    if (!prepared) {
      return
    }
    const frozenPayload = trimMessageParts(prepared.payload)
    const frozenLocalDraft = trimMessageParts(prepared.localDraft)

    if (!hasMessageContent(frozenPayload)) {
      return
    }
    if (slashQueryOf(frozenPayload) != null) {
      return
    }

    if (isDraftHistoryTarget(target)) {
      await previewLocalBranchDraft(frozenPayload, frozenLocalDraft)
      return
    }
    if (!isBoundTarget(target)) {
      return
    }

    const currentThreadId = target.threadId
    const frozenBranchDraft = branchPanel.draft ? cloneDraft(branchPanel.draft) : null
    const frozenEffectiveBase = branchPanel.effectiveBase ? cloneDraft(branchPanel.effectiveBase) : null
    if (!frozenBranchDraft || !frozenEffectiveBase) {
      return
    }

    const requestId = ++previewRequestIdRef.current
    const requestPartsKey = partsKey(frozenLocalDraft)

    previewInFlightRef.current = true
    setPreviewLoading(true)
    setPreviewError(null)
    setActionError(null)

    const isCurrentPreview = () => {
      if (!isMountedRef.current || previewRequestIdRef.current !== requestId) {
        return false
      }
      const latestTarget = targetRef.current
      if (!isBoundTarget(latestTarget) || latestTarget.threadId !== currentThreadId) {
        return false
      }
      const latestPayload = trimMessageParts(controllerRef.current.draft)
      const latestDraft = branchPanelRef.current.draft
      return partsKey(latestPayload) === requestPartsKey
        && latestDraft != null
        && branchDraftsEqual(latestDraft, frozenBranchDraft)
    }

    try {
      const freshSnapshot = await harnessService.getThreadSnapshot(currentThreadId)
      if (!isCurrentPreview()) {
        return
      }

      // 检查服务端 branch settings 与冻结 effectiveBase 是否变化
      const freshServerDraft = branchDraftFromThread(freshSnapshot.thread)
      if (!branchDraftsEqual(freshServerDraft, frozenEffectiveBase)) {
        const msg = t('ai.runtime.debug.settingsChanged')
        setPreviewError(msg)
        setActionError(msg)
        queryClient.setQueryData(queryKeys.threads.snapshot(currentThreadId), freshSnapshot)
        return
      }

      // fresh busy/queued/active 不能预览直接本地明确原因
      if (freshSnapshot.thread.processing || freshSnapshot.thread.status !== 'IDLE'
        || freshSnapshot.modelInvocation != null || freshSnapshot.toolInvocations.length > 0) {
        const msg = t('ai.runtime.debug.previewError.PREVIEW_THREAD_BUSY')
        setPreviewError(msg)
        setActionError(msg)
        return
      }
      if (freshSnapshot.queuedCommands.length > 0) {
        const msg = t('ai.runtime.debug.previewError.PREVIEW_QUEUED_COMMANDS')
        setPreviewError(msg)
        setActionError(msg)
        return
      }

      // 用 fresh thread.headEntryId 和 nextCommandSequence 重建 plan.request target
      const plan = buildMessageBatchPlan({
        thread: freshSnapshot.thread,
        effectiveBase: frozenEffectiveBase,
        draft: frozenBranchDraft,
        parts: frozenPayload,
      })

      const response = await harnessService.previewProviderRequest(currentThreadId, plan.request)
      if (!isCurrentPreview()) {
        return
      }

      if (!response || response.kind !== 'DRAFT_REQUEST_PREVIEW') {
        throw new Error(t('ai.runtime.debug.previewFailed'))
      }

      boundViewsRef.current?.selectDebugInspector({ type: 'preview', preview: response })
    } catch (error) {
      if (!isCurrentPreview()) {
        return
      }
      const msg = formatPreviewErrorMessage(error, t)
      setPreviewError(msg)
      setActionError(msg)
    } finally {
      if (isMountedRef.current && previewRequestIdRef.current === requestId) {
        previewInFlightRef.current = false
        setPreviewLoading(false)
      }
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
        if (capabilities?.allowBranching === false) {
          setActionError(t('ai.runtime.action.branchingDisabled'))
          return
        }
        if (hasPendingOperation()) {
          setActionError(t('ai.runtime.action.operationPending'))
          return
        }
        setInteraction('thread-sessions')
        return
      case 'history':
        if (capabilities?.allowBranching === false) {
          setActionError(t('ai.runtime.action.branchingDisabled'))
          return
        }
        if (hasPendingOperation()) {
          setActionError(t('ai.runtime.action.operationPending'))
          return
        }
        if (historySessionId == null) {
          setActionError(t('ai.runtime.action.threadNotLoaded'))
          return
        }
        setInteraction('history')
        return
      case 'new':
        if (capabilities?.readOnly) {
          return
        }
        if (capabilities?.allowBranching === false) {
          setActionError(t('ai.runtime.action.branchingDisabled'))
          return
        }
        if (capabilities?.allowNewSession === false) {
          if (currentSessionId && rootEntryId) {
            requestBranch({
              sessionId: currentSessionId,
              startEntryId: rootEntryId,
              sourceLabel: boundThreadName,
            })
            return
          }
          if (!currentSessionId) {
            changeTarget({ kind: 'NEW_SESSION_DRAFT' }, activeDraft)
          }
          return
        }
        changeTarget({ kind: 'NEW_SESSION_DRAFT' }, activeDraft)
        return
      case 'agent':
        if (capabilities?.allowSwitchAgent === false) {
          setActionError(t('ai.runtime.action.agentSwitchDisabled'))
          return
        }
        setInteraction('agent')
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
        // 已绑定 Thread 与本地分支/会话 fork 草稿都从各自的 Debug 视图退出/进入：草稿没有绑定
        // Thread，但 Debug 预览走会话级 branch preview（绝不为此创建 Thread）。
        if (isBoundTarget(target) || isDraftHistoryTarget(target)) {
          boundViews.switchMode(boundViews.mode === 'debug' ? 'conversation' : 'debug')
        }
        return
      case 'subagent':
        if (isBoundTarget(target)) {
          setInteraction('subagent')
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
      case 'goal':
        setInteraction('goal')
        return
      case 'compact':
        controller.runCommand(command)
        return
      case 'stop':
        controller.runCommand(command)
        return
      case 'models':
      case 'upload':
        return
    }
  }

  function selectAgent(agentName: string): void {
    if (capabilities?.allowSwitchAgent === false) {
      setActionError(t('ai.runtime.action.agentSwitchDisabled'))
      return
    }
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
      const nextDraft = materializeAgentBranchDraft(
        agent,
        models,
        localDraft,
        defaults.yoloEnabled ?? false,
      )
      if (nextDraft == null) {
        setActionError(t('ai.runtime.action.agentUnresolvable', { selectedAgent: agentName }))
        return
      }
      setLocalDraft(nextDraft)
      setLocalDraftEdited(true)
    }
    setInteraction(null)
    setActionError(null)
  }

  /**
   * 统一的“从此处分支”入口：分支名称与目标 pane 由 Chat workspace 决定，
   * 本 pane 只请求，不本地改写自己的目标（新建 Thread 落在目标 pane）。
   * 无 owner/无 workspace 回调（如只读宿主）时保持此前的无操作语义。
   */
  function requestBranch(input: BranchRequestInput): boolean {
    if (owner == null || capabilities?.allowBranching === false) {
      setActionError(t('ai.runtime.action.branchingDisabled'))
      return false
    }
    // 没有 workspace 回调的宿主（只读嵌入）没有新建分支的去处，保持无操作。
    if (onRequestBranch == null) {
      return false
    }
    if (hasPendingOperation()) {
      setActionError(t('ai.runtime.action.operationPending'))
      return false
    }
    setActionError(null)
    onRequestBranch(input)
    return true
  }

  /** 历史树行的分叉动作：只对 ROOT / 已关闭 TURN_END 可用（面板已禁用其它行）。 */
  function requestBranchFromEntry(entry: HarnessSessionEntryDTO): void {
    if (requestBranch({
      sessionId: entry.sessionId,
      startEntryId: entry.entryId,
      sourceLabel: boundThreadName,
    })) {
      setThreadNavigationSessionId(null)
      setInteraction(null)
    }
  }

  const historySourceThreadId = isBoundTarget(target)
    && threadNavigationSessionId == null
    && controller.sessionId != null
    ? target.threadId
    : target.kind === 'FORK_SESSION_DRAFT' && threadNavigationSessionId == null
      ? target.sourceThreadId
      : null
  const canForkSession = Boolean(owner)
    && capabilities?.allowBranching !== false
    && !capabilities?.readOnly
    && historySourceThreadId != null

  /**
   * 历史树行的显式“从此处新建会话”动作：把当前来源执行根 Thread 与选定切点写入
   * 本 pane 的会话 fork 草稿（不预创建 Session/Thread）。源 Thread 继续独立运行，
   * 目标与本地草稿只改本 pane；创建仍由首次输入原子提交。
   */
  function requestSessionForkFromEntry(entry: HarnessSessionEntryDTO): void {
    if (!canForkSession || historySourceThreadId == null) {
      setActionError(t('ai.runtime.action.branchingDisabled'))
      return
    }
    if (hasPendingOperation()) {
      setActionError(t('ai.runtime.action.operationPending'))
      return
    }
    if (changeTarget({
      kind: 'FORK_SESSION_DRAFT',
      sessionId: entry.sessionId,
      sourceThreadId: historySourceThreadId,
      startEntryId: entry.entryId,
    })) {
      setThreadNavigationSessionId(null)
      setInteraction(null)
    }
  }

  function selectSession(session: RuntimeSessionSummaryDTO): void {
    if (!owner || capabilities?.allowBranching === false) {
      setActionError(t('ai.runtime.action.branchingDisabled'))
      return
    }
    setThreadNavigationSessionId(session.sessionId)
    setInteraction(session.threadCount === 0 ? 'history' : 'thread-threads')
  }

  function selectThread(thread: RuntimeThreadSummaryDTO): void {
    if (!owner || capabilities?.allowBranching === false) {
      setActionError(t('ai.runtime.action.branchingDisabled'))
      return
    }
    changeTarget({ kind: 'BOUND_THREAD', threadId: thread.threadId }, activeDraft)
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

  const pending = pendingAcceptance != null
    || controller.pending
    || controller.compactPending
    || controller.stopPending
    || controller.stopReplayPending
    || controller.approvalPending
    || controller.replayPending

  const unsentDraft = isBoundTarget(target)
    ? trimMessageParts(controller.draft).length > 0
    : trimMessageParts(parts).length > 0
  const reportDraftKey = partsKey(isBoundTarget(target) ? controller.draft : parts)
  const reportPending = pending || renamePending || controller.queuedCommands.length > 0
  const reportSessionId = currentSessionId
  const reportBranchName = isBoundTarget(target)
    ? boundThreadName
    : isNewThreadTarget(target) ? target.threadName : null
  const onReportRef = useRef(onReport)
  useEffect(() => {
    onReportRef.current = onReport
  })
  useEffect(() => {
    onReportRef.current?.({
      target,
      draftKey: reportDraftKey,
      pending: reportPending,
      hasUnsentDraft: unsentDraft,
      sessionId: reportSessionId,
      branchName: reportBranchName,
    })
  }, [reportPending, reportBranchName, reportSessionId, unsentDraft, target, reportDraftKey])
  // Workspace keeps busy hidden panes mounted until settlement. Unmount must
  // never erase their last reported gate or uncommitted target identity.

  const { previewDisabled, previewDisabledReason } = useMemo(() => {
    if (!isBoundTarget(target) && !isDraftHistoryTarget(target)) {
      return {
        previewDisabled: true,
        previewDisabledReason: t('ai.runtime.debug.previewDisabled.unsupported'),
      }
    }
    if (isDraftHistoryTarget(target)) {
      if (capabilities?.readOnly) {
        return {
          previewDisabled: true,
          previewDisabledReason: t('ai.runtime.debug.previewDisabled.readOnly'),
        }
      }
      if (pending) {
        return {
          previewDisabled: true,
          previewDisabledReason: t('ai.runtime.debug.previewDisabled.busy'),
        }
      }
      if (!draftHistoryReady || activeDraft == null || entryBaseDraft == null) {
        return {
          previewDisabled: true,
          previewDisabledReason: t('ai.runtime.debug.previewDisabled.unsupported'),
        }
      }
      if (previewLoading) {
        return {
          previewDisabled: true,
          previewDisabledReason: t('ai.runtime.composer.previewLoading'),
        }
      }
      if (!composerReadiness.canPreview) {
        let draftReasonText: string = t('ai.runtime.debug.previewDisabled.emptyDraft')
        if (composerReadiness.reason === 'SLASH_COMMAND') {
          draftReasonText = t('ai.runtime.debug.previewDisabled.slashCommand')
        } else if (composerReadiness.reason === 'GOAL_COMMAND') {
          draftReasonText = t('ai.runtime.debug.previewDisabled.goalCommand')
        } else if (composerReadiness.reason === 'UPLOADS_PENDING') {
          draftReasonText = t('ai.runtime.debug.previewDisabled.uploading')
        }
        return {
          previewDisabled: true,
          previewDisabledReason: draftReasonText,
        }
      }
      return { previewDisabled: false, previewDisabledReason: null }
    }
    if (capabilities?.readOnly) {
      return {
        previewDisabled: true,
        previewDisabledReason: t('ai.runtime.debug.previewDisabled.readOnly'),
      }
    }
    if (controller.working || pending) {
      return {
        previewDisabled: true,
        previewDisabledReason: t('ai.runtime.debug.previewDisabled.busy'),
      }
    }
    if (controller.queuedCommands.length > 0) {
      return {
        previewDisabled: true,
        previewDisabledReason: t('ai.runtime.debug.previewDisabled.queued'),
      }
    }
    if (previewLoading) {
      return {
        previewDisabled: true,
        previewDisabledReason: t('ai.runtime.composer.previewLoading'),
      }
    }
    if (controller.disabled || branchPanel.branchState == null || branchPanel.effectiveBase == null) {
      return {
        previewDisabled: true,
        previewDisabledReason: t('ai.runtime.debug.previewDisabled.unsupported'),
      }
    }
    if (!composerReadiness.canPreview) {
      let reasonText: string = t('ai.runtime.debug.previewDisabled.emptyDraft')
      if (composerReadiness.reason === 'SLASH_COMMAND') {
        reasonText = t('ai.runtime.debug.previewDisabled.slashCommand')
      } else if (composerReadiness.reason === 'GOAL_COMMAND') {
        reasonText = t('ai.runtime.debug.previewDisabled.goalCommand')
      } else if (composerReadiness.reason === 'UPLOADS_PENDING') {
        reasonText = t('ai.runtime.debug.previewDisabled.uploading')
      } else if (composerReadiness.reason === 'EMPTY_DRAFT') {
        reasonText = t('ai.runtime.debug.previewDisabled.emptyDraft')
      }
      return {
        previewDisabled: true,
        previewDisabledReason: reasonText,
      }
    }
    return {
      previewDisabled: false,
      previewDisabledReason: null,
    }
  }, [
    capabilities?.readOnly,
    composerReadiness,
    controller.disabled,
    controller.queuedCommands.length,
    controller.working,
    pending,
    previewLoading,
    t,
    target,
    activeDraft,
    entryBaseDraft,
    draftHistoryReady,
    branchPanel.branchState,
    branchPanel.effectiveBase,
  ])

  const boundViews = useBoundThreadPanelViews(boundThreadId, isDraftHistoryTarget(target) ? {
    ...controller, events: draftEvents, sessionId: target.sessionId,
  } : controller, {
    // 本地草稿没有 threadId：视图状态按目标身份隔离，API 预览仍按真实 threadId/会话。
    viewKey: previewScope,
    debugSettings: activeDraft == null ? undefined : {
      model: activeDraft.model,
      environmentName: activeDraft.environmentName,
    },
    historyLoading: isDraftHistoryTarget(target) ? treeEntriesQuery.isLoading : controller.messagesLoading,
    historyError: (isDraftHistoryTarget(target) ? treeEntriesQuery.error ?? draftHistory.error : controller.messagesError)
      ? t('ai.chat.history.loadFailed') : null,
    onRetryHistory: () => { void (isDraftHistoryTarget(target) ? treeEntriesQuery.refetch() : controller.snapshotQuery.refetch()) },
    previewError,
  })
  const boundViewsRef = useRef(boundViews)
  useEffect(() => {
    boundViewsRef.current = boundViews
  })
  const rootEntryId = controller.entries?.find((e) => e.parentEntryId == null)?.entryId
    ?? treeEntriesQuery.data?.find((e) => e.parentEntryId == null)?.entryId
    ?? null
  const canBranchFromRoot = rootEntryId != null
  const commands = threadCommandsForTarget(
    target,
    {
      manualCompaction: isBoundTarget(target) ? controller.manualCompaction : null,
      allowNewSession: capabilities?.allowNewSession,
      readOnly: capabilities?.readOnly,
      canBranchFromRoot,
      allowSwitchAgent: capabilities?.allowSwitchAgent,
      allowBranching: capabilities?.allowBranching,
    },
  )
  const boundGoal = isBoundTarget(target) && controller.thread?.branchSettings?.goal != null
    ? controller.thread.branchSettings.goal
    : null

  const boundGoalProgress: BranchGoalProgressResult = useMemo(() => {
    if (!isBoundTarget(target) || !boundGoal) {
      return { active: null, stale: null }
    }
    return parseGoalProgress(
      controller.entries ?? [],
      boundGoal.id,
    )
  }, [target, controller.entries, boundGoal])

  const composerDraft = isBoundTarget(target) ? controller.draft : parts
  const goalDraft = isBoundTarget(target) ? controller.goalDraft : null
  const composerDisabled = Boolean(capabilities?.readOnly)
    || pending
    || (isBoundTarget(target)
      ? controller.disabled || branchPanel.branchState == null || branchPanel.effectiveBase == null
      : activeDraft == null || !draftHistoryReady)
  const composer: ThreadPanelComposerInput = {
    scope: composerScope,
    parts: composerDraft,
    pending,
    disabled: composerDisabled,
    onPartsChange: isBoundTarget(target) ? controller.setDraft : setParts,
    onHistoryPartsChange: isBoundTarget(target)
      ? (next) => controller.setDraft(next, 'history')
      : undefined,
    onSubmit: handleSubmit,
    onSubmitGoal: (goalText: string, localDraft?: ComposerPart[]) => {
      submitGoal(goalText, { localParts: localDraft })
    },
    onCommand: handleCommand,
    commands,
    focusOnEscape: focused,
    composerRef,
    onPreviewReadinessChange: setComposerReadiness,
    settings: activeDraft == null ? undefined : {
      model: activeDraft.model,
      models,
      yoloEnabled: activeDraft.yoloEnabled,
      environmentName: activeDraft.environmentName,
      environments,
      status: isBoundTarget(target)
        ? branchPanel.settingsStatus
        : localDraftEdited ? 'draft' : null,
      onModelChange: (model) => {
        if (isBoundTarget(target)) {
          branchPanel.selectModel(model)
        } else {
          setLocalDraftEdited(true)
          setLocalDraft((current) => (current ? { ...current, model } : current))
        }
      },
      onYoloChange: (enabled) => {
        if (isBoundTarget(target)) {
          branchPanel.setYoloEnabled(enabled)
        } else {
          setLocalDraftEdited(true)
          setLocalDraft((current) => (current ? { ...current, yoloEnabled: enabled } : current))
        }
      },
      onEnvironmentChange: (environmentName) => {
        if (isBoundTarget(target)) {
          branchPanel.selectEnvironment(environmentName)
        } else {
          setLocalDraftEdited(true)
          setLocalDraft((current) => (current ? { ...current, environmentName } : current))
        }
      },
    },
  }
  useEffect(() => {
    if (focusIntent == null) {
      return
    }
    if (!focused || !samePaneTarget(focusIntent, target)) {
      setFocusIntent(null)
      return
    }
    if (!composerDisabled && !covered && interaction == null && boundViews.mode === 'conversation') {
      composerRef.current?.focus()
      setFocusIntent(null)
    }
  }, [focusIntent, focused, target, composerDisabled, covered, interaction, boundViews.mode, setFocusIntent])
  const activeDraftEnvironmentName = activeDraft?.environmentName ?? null
  const boundEnvCard = activeDraftEnvironmentName
    ? environments.find((env) => env.name === activeDraftEnvironmentName) ?? null
    : null
  const boundEnvironment = activeDraftEnvironmentName
    ? {
        id: boundEnvCard?.id ?? activeDraftEnvironmentName,
        name: activeDraftEnvironmentName,
      }
    : null
  const environmentReady =
    activeDraftEnvironmentName == null
      ? undefined
      : boundEnvCard != null
        ? boundEnvCard.ready
        : false
  const error = actionError
    ?? (isBoundTarget(target)
      ? branchPanel.settingsError ?? branchPanel.yoloError ?? controller.actionError
      : null)
  const combinedConflict = conflict ?? controller.conflict ?? branchPanel.conflict

  return {
    target,
    draftTimeline,
    draftAtRoot: draftHistoryReady && draftHistory.entries.length === 1,
    draftHistoryLoading: isDraftHistoryTarget(target) && treeEntriesQuery.isLoading,
    draftHistoryError: treeEntriesQuery.error ?? draftHistory.error,
    draftHistoryErrorText: draftHistory.error != null
      ? t('ai.chat.branch.historyInvalid')
      : treeEntriesQuery.error != null ? t('ai.chat.history.loadFailed') : undefined,
    retryDraftHistory: () => void treeEntriesQuery.refetch(),
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
    boundGoal,
    boundGoalProgress,
    goalDraft,
    setGoalDraft: (text: string) => {
      if (isBoundTarget(target)) {
        controller.setGoalDraft(text)
      }
    },
    submitGoal,
    clearGoal,
    composer,
    restoreComposerFocus: () => setFocusIntent(target),
    pendingAcceptance,
    pendingMessage: isBoundTarget(target) ? controller.pendingMessage : null,
    draftRestoreError: isBoundTarget(target) ? controller.draftRestoreError : null,
    retryDraftRestore: () => {
      if (isBoundTarget(target)) {
        controller.retryDraftRestore()
      }
    },
    pending,
    error,
    previewDisabled,
    previewDisabledReason,
    previewLoading,
    previewError,
    handlePreview,
    dismissActionError: () => {
      setActionError(null)
      setPreviewError(null)
      branchPanel.dismissSettingsError()
      branchPanel.dismissYoloError()
    },
    conflict: combinedConflict,
    dismissConflict: () => {
      setConflict(null)
      controller.dismissConflict()
      branchPanel.dismissConflict()
    },
    refreshPaneProjection,
    retryAcceptance,
    abandonPendingAcceptance,
    retryPendingMessage: controller.retryPendingMessage,
    abandonPendingMessage: controller.abandonPendingMessage,
    selectAgent,
    requestBranch,
    requestBranchFromEntry,
    requestSessionForkFromEntry,
    canForkSession,
    boundBranchName: boundThreadName,
    selectSession,
    selectThread,
    sessionSelectionItem,
    threadSelectionItem,
    boundEnvironment,
    environmentReady,
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

export function branchDraftFromEntry(
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
  let environmentName = fallback.environmentName
  if (Object.hasOwn(settings, 'environmentName')) {
    if (typeof settings.environmentName === 'string') {
      environmentName = settings.environmentName
    } else if (settings.environmentName === null) {
      environmentName = null
    }
  }
  return {
    ...cloneDraft(fallback),
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
    environmentName,
  }
}

export function branchDraftFromEntryPath(
  entries: HarnessSessionEntryDTO[],
  targetEntryId: string,
  fallback: BranchDraft | null,
): BranchDraft | null {
  if (fallback == null) {
    return null
  }
  const path = threadDraftPath(entries, entries.find((entry) => entry.entryId === targetEntryId)?.sessionId ?? '', targetEntryId)
  let draft = cloneDraft(fallback)
  for (const entry of path) {
    draft = branchDraftFromEntry(entry, draft) ?? draft
  }
  return draft
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
