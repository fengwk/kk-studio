import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ThreadProjection } from '@/features/chat/runtime/useThreadProjection'
import { useBoundBranchPanel } from '@/features/chat/runtime/useBoundBranchPanel'
import { useBoundThreadPanelViews } from '@/features/chat/runtime/useBoundThreadPanelViews'
import type { ThreadPanelComposerInput } from '@/features/chat/runtime/thread-panel/ThreadPanel'
import {
  materializeAgentBranchDraft,
  materializeBlankBranchDraft,
  type BranchDraft,
} from '@/features/chat/branch-draft'
import type { ThreadComposerHandle } from '@/features/chat/runtime/thread-panel'
import {
  partsKey,
  trimMessageParts,
  type ComposerPart,
} from '@/features/chat/composer/composer-parts'
import {
  restoreComposerDraft,
  storeComposerDraft,
} from '@/features/chat/composer/composer-draft'
import { hasBlockingModal } from '@/shared/ui/blocking-overlay'
import type { ConflictPresentation } from '@/shared/conflict/conflict-presenter'
import type { AgentRuntimeOwnerDTO } from '@/shared/api/contracts/ai-runtime'
import {
  parseGoalProgress,
  type BranchGoalProgressResult,
} from '@/features/chat/runtime/goal-progress'
import {
  isBoundTarget,
  isDraftHistoryTarget,
  isNewThreadTarget,
  ownerIdentity,
  samePaneTarget,
  type PaneTarget,
  type PendingAcceptance,
} from '@/features/chat/runtime/agent-pane'
import { threadCommandsForTarget } from '@/features/chat/runtime/thread-panel/thread-commands'
import { useI18n } from '@/shared/i18n'
import { paneTargetViewKey } from '@/features/chat/runtime/thread-presentation'
import { cloneDraft } from '@/features/chat/runtime/root-thread/branch-draft-replay'
import {
  handlePaneCommand,
  type PaneCommandContext,
} from '@/features/chat/runtime/root-thread/pane-command-handler'
import { usePaneAcceptance } from '@/features/chat/runtime/root-thread/usePaneAcceptance'
import { usePaneBackgroundProjection } from '@/features/chat/runtime/root-thread/usePaneBackgroundProjection'
import {
  usePaneDraftHistory,
  usePaneNavigationQueries,
} from '@/features/chat/runtime/root-thread/usePaneHistory'
import { usePaneNavigation } from '@/features/chat/runtime/root-thread/usePaneNavigation'
import { usePaneRename } from '@/features/chat/runtime/root-thread/usePaneRename'
import type {
  AgentPaneCapabilities,
  AgentPaneDefaults,
  BranchRequestInput,
  PaneInteraction,
  PaneReport,
  RenameKind,
  RenameTarget,
} from '@/features/chat/runtime/root-thread/types'
import type { AgentDefinitionDTO, EnvironmentCardDTO } from '@/shared/api/generated/contracts'

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
  onOpenIde?: (options: { mode: 'files' | 'shell' }) => void
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
  onOpenIde,
}: UseRootThreadControlOptions) {
  const { t } = useI18n()
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
  const [interaction, setInteraction] = useState<PaneInteraction>(null)
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
  const pendingAcceptanceRef = useRef<PendingAcceptance | null>(null)
  // rename PUT 在途栅栏：state commit 前也能让 changeTarget/handleCommand 看到
  // 重命名正在进行，从而阻塞外部 target 切换与并发命令（与 pendingAcceptance
  // 同栅栏，不持久化、不改变 pendingAcceptance 渲染语义）。
  const renamePendingRef = useRef(false)
  const generationRef = useRef(0)

  const isMountedRef = useRef(true)
  useEffect(() => {
    isMountedRef.current = true
    return () => {
      isMountedRef.current = false
    }
  }, [])

  const composerRef = useRef<ThreadComposerHandle | null>(null)
  const setFocusIntent = useCallback((next: PaneTarget | null) => {
    onFocusTargetChange?.(next)
  }, [onFocusTargetChange])
  const boundThreadId = isBoundTarget(target) ? target.threadId : ''

  // 视图身份：绑定 Thread 用 threadId，本地分支草稿用
  // sessionId:startEntryId:name（新建 Session 草稿没有身份可言，固定为空串）。
  // 身份变化即重置该 Pane 的本地视图状态（Debug 模式/滚动/检查器选中）。
  const targetViewKey = paneTargetViewKey(target)
  const branchPanel = useBoundBranchPanel({
    threadId: boundThreadId,
    projection,
  })
  const controller = branchPanel.controller
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
          defaults.environmentName,
        ) != null,
      )
    const materialized = preferred == null
      ? null
      : materializeBlankBranchDraft(
        preferred,
        defaults.yoloEnabled ?? false,
        models,
        defaults.environmentName,
      )
    if (materialized != null) {
      setLocalDraft(materialized)
    }
  }, [agents, defaults.agentName, defaults.environmentName, defaults.yoloEnabled, localDraft, models, target])

  usePaneBackgroundProjection(boundThreadId)

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
  const { sessionsQuery, threadsQuery, treeEntriesQuery } = usePaneNavigationQueries({
    owner,
    ownerKey,
    target,
    interaction,
    threadNavigationSessionId,
    historySessionId,
  })
  const {
    draftHistory,
    draftHistoryReady,
    draftTimeline,
    draftEvents,
    entryBaseDraft,
    resetEntryDraftInit,
  } = usePaneDraftHistory({ target, treeEntriesQuery, activeDraft, setLocalDraft })

  const setParts = useCallback((next: ComposerPart[]) => {
    partsRef.current = next
    storeComposerDraft(composerScope, next)
    setPartsState(next)
  }, [composerScope])

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

  const rename = usePaneRename({
    ownerKey,
    target,
    targetRef,
    setTarget,
    setFocusIntent,
    generationRef,
    isMountedRef,
    renamePendingRef,
    boundThread: controller.thread == null
      ? null
      : { threadId: controller.thread.threadId, name: controller.thread.name },
    boundSessionId: controller.sessionId || null,
    sessionsQuery,
    hasPendingOperation,
    setInteraction,
    setActionError,
    onValidateDraftName,
  })

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
      resetEntryDraftInit()
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
    // resetEntryDraftInit 只写 ref，身份每次渲染变化但语义稳定。
    // eslint-disable-next-line react-hooks/exhaustive-deps
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

  const acceptance = usePaneAcceptance({
    owner,
    ownerKey,
    paneId,
    target,
    targetRef,
    setTarget,
    setFocusIntent,
    capabilities,
    onFocus,
    generationRef,
    pendingAcceptanceRef,
    composerScope,
    parts,
    partsRef,
    setParts,
    setPartsState,
    localDraftRef,
    setLocalDraft,
    activeDraft,
    entryBaseDraft,
    draftHistoryReady,
    draftHistoryEntries: draftHistory.entries,
    boundSubmit: controller,
    hasPendingOperation,
    setActionError,
    setConflict,
    setInteraction,
    openRename: rename.openRename,
    setRenameError: rename.setRenameError,
  })
  const { pendingAcceptance, pendingAcceptanceBlocked, submitGoal } = acceptance

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
        defaults.environmentName,
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

  const navigation = usePaneNavigation({
    owner,
    target,
    capabilities,
    onRequestBranch,
    boundThreadName,
    boundSessionId: controller.sessionId,
    activeDraft,
    threadNavigationSessionId,
    setThreadNavigationSessionId,
    changeTarget,
    hasPendingOperation,
    setInteraction,
    setActionError,
  })

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
  const reportPending = pending
    || pendingAcceptanceBlocked
    || rename.renamePending
    || controller.queuedCommands.length > 0
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

  const boundViews = useBoundThreadPanelViews(boundThreadId, isDraftHistoryTarget(target) ? {
    ...controller, events: draftEvents, sessionId: target.sessionId,
  } : controller, {
    // 本地草稿没有 threadId：视图状态按目标身份隔离，API 规划仍按真实 threadId/会话。
    viewKey: targetViewKey,
    debugSettings: activeDraft == null ? undefined : {
      model: activeDraft.model,
      environmentName: activeDraft.environmentName,
    },
    historyLoading: isDraftHistoryTarget(target) ? treeEntriesQuery.isLoading : controller.messagesLoading,
    historyError: (isDraftHistoryTarget(target) ? treeEntriesQuery.error ?? draftHistory.error : controller.messagesError)
      ? t('ai.chat.history.loadFailed') : null,
    onRetryHistory: () => { void (isDraftHistoryTarget(target) ? treeEntriesQuery.refetch() : controller.snapshotQuery.refetch()) },
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

  const commandContext: PaneCommandContext = {
    target,
    capabilities,
    t,
    onFocus,
    onOpenIde,
    activeDraft,
    currentSessionId,
    historySessionId,
    rootEntryId,
    boundThreadName,
    hasPendingOperation,
    setActionError,
    setInteraction,
    setLocalDraft,
    changeTarget,
    requestBranch: navigation.requestBranch,
    openRenameForTarget: rename.openRenameForTarget,
    setBoundYolo: branchPanel.setYoloEnabled,
    boundYoloEnabled: branchPanel.draft?.yoloEnabled ?? false,
    toggleDebugView: () => boundViews.switchMode(boundViews.mode === 'debug' ? 'conversation' : 'debug'),
    runControllerCommand: controller.runCommand,
  }

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
    onSubmit: acceptance.handleSubmit,
    onSubmitGoal: (goalText: string, localDraft?: ComposerPart[]) => {
      submitGoal(goalText, { localParts: localDraft })
    },
    onCommand: (command) => handlePaneCommand(commandContext, command),
    commands,
    focusOnEscape: focused,
    composerRef,
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
    // 受控目标的父级提交可能晚于本次 focusIntent 渲染；targetRef 已在 changeTarget
    // 内同步指向新目标，用它判断才能保留本次待恢复的焦点。
    if (!focused || !samePaneTarget(focusIntent, targetRef.current)) {
      setFocusIntent(null)
      return
    }
    if (!composerDisabled && !covered && interaction == null && boundViews.mode === 'conversation') {
      if (!hasBlockingModal()) {
        composerRef.current?.focus()
      }
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
    renameTarget: rename.renameTarget,
    renamePending: rename.renamePending,
    renameError: rename.renameError,
    renameBusy: rename.renameBusy,
    renameSession: (sessionId: string, name: string | null) => rename.openRename('session', sessionId, name),
    renameThread: (threadId: string, name: string | null) => rename.openRename('thread', threadId, name),
    openRenameWithBackTo: (
      kind: RenameKind,
      id: string,
      name: string | null,
      backTo: RenameTarget['backTo'],
    ) => rename.openRename(kind, id, name, backTo),
    submitRename: rename.submitRename,
    closeRename: rename.closeRename,
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
    clearGoal: () => submitGoal(null),
    composer,
    restoreComposerFocus: () => setFocusIntent(target),
    pendingAcceptance,
    pendingAcceptanceBlocked,
    pendingMessage: isBoundTarget(target) ? controller.pendingMessage : null,
    draftRestoreError: isBoundTarget(target) ? controller.draftRestoreError : null,
    retryDraftRestore: () => {
      if (isBoundTarget(target)) {
        controller.retryDraftRestore()
      }
    },
    pending,
    error,
    dismissActionError: () => {
      setActionError(null)
      branchPanel.dismissSettingsError()
      branchPanel.dismissYoloError()
    },
    conflict: combinedConflict,
    dismissConflict: () => {
      setConflict(null)
      controller.dismissConflict()
      branchPanel.dismissConflict()
    },
    refreshPaneProjection: acceptance.refreshPaneProjection,
    retryAcceptance: acceptance.retryAcceptance,
    abandonPendingAcceptance: acceptance.abandonPendingAcceptance,
    retryPendingMessage: controller.retryPendingMessage,
    abandonPendingMessage: controller.abandonPendingMessage,
    selectAgent,
    requestBranch: navigation.requestBranch,
    requestBranchFromEntry: navigation.requestBranchFromEntry,
    requestSessionForkFromEntry: navigation.requestSessionForkFromEntry,
    canForkSession: navigation.canForkSession,
    boundBranchName: boundThreadName,
    selectSession: navigation.selectSession,
    selectThread: navigation.selectThread,
    boundEnvironment,
    environmentReady,
  }
}
