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
import { agentPaneService } from '@/shared/api/agent-pane-service'
import {
  presentConflict,
  type ConflictPresentation,
} from '@/features/ai/runtime/conflict-presenter'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type {
  AgentRuntimeOwnerDTO,
  HarnessSessionEntryDTO,
  HarnessThreadSnapshotDTO,
  RuntimeSessionSummaryDTO,
  RuntimeThreadSummaryDTO,
} from '@/shared/api/contracts/ai-runtime'
import type { EnvironmentBindingDTO, LiveEnvironmentDTO } from '@/shared/api/contracts/ai-environment'
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
  isEntryTarget,
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
  | null

export interface AgentPaneDefaults {
  agentName?: string
  environment?: EnvironmentBindingDTO | null
  yoloEnabled?: boolean
}

export interface UseAgentPaneControllerOptions {
  owner: AgentRuntimeOwnerDTO
  paneId: string
  agents: AgentDefinitionDTO[]
  environments: LiveEnvironmentDTO[]
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
  const [threadNavigationSessionId, setThreadNavigationSessionId] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [conflict, setConflict] = useState<ConflictPresentation | null>(null)
  const targetRef = useRef(target)
  const partsRef = useRef(parts)
  const localDraftRef = useRef<BranchDraft | null>(localDraft)
  const initializedEntryDraftRef = useRef<string | null>(null)
  const pendingAcceptanceRef = useRef<PendingAcceptance | null>(pendingAcceptance)
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
          defaults.environment,
        ) != null,
      )
    const materialized = preferred == null
      ? null
      : materializeBlankBranchDraft(
        preferred,
        defaults.yoloEnabled ?? false,
        models,
        defaults.environment,
      )
    if (materialized != null) {
      setLocalDraft(materialized)
    }
  }, [agents, defaults.agentName, defaults.environment, defaults.yoloEnabled, localDraft, models, target])

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
              if (name === 'revision') {
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
    : isEntryTarget(target)
      ? target.sessionId
      : null
  const sessionsQuery = useQuery({
    queryKey: ['agent-pane', 'sessions', owner.type, owner.id],
    queryFn: () => owner.type === 'CHAT'
      ? agentPaneService.listChatSessions(owner.id)
      : agentPaneService.listCanvasSessions(owner.id),
    enabled: interaction === 'thread-sessions',
  })
  const threadsQuery = useQuery({
    queryKey: ['agent-pane', 'threads', threadNavigationSessionId],
    queryFn: () => agentPaneService.listSessionThreads(threadNavigationSessionId!),
    enabled: interaction === 'thread-threads' && threadNavigationSessionId != null,
  })
  const treeEntriesQuery = useQuery({
    queryKey: ['agent-pane', 'entries', threadNavigationSessionId ?? currentSessionId],
    queryFn: () => agentPaneService.listSessionEntries(threadNavigationSessionId ?? currentSessionId!),
    enabled:
      (interaction === 'tree' || isEntryTarget(target))
      && (threadNavigationSessionId ?? currentSessionId) != null,
  })

  const entryBaseDraft = useMemo(() => {
    if (!isEntryTarget(target)) {
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
    if (!isEntryTarget(target) || treeEntriesQuery.data == null || entryBaseDraft == null) {
      return
    }
    const identity = `${target.sessionId}:${target.startEntryId}`
    if (initializedEntryDraftRef.current === identity) {
      return
    }
    initializedEntryDraftRef.current = identity
    setLocalDraft(cloneDraft(entryBaseDraft))
  }, [entryBaseDraft, target, treeEntriesQuery.data])

  function setParts(next: ComposerPart[]) {
    partsRef.current = next
    storeComposerDraft(composerScope, next)
    setPartsState(next)
  }

  function changeTarget(next: PaneTarget, draft: BranchDraft | null = activeDraft): boolean {
    if (pendingAcceptance != null) {
      setActionError(t('ai.runtime.action.acceptancePending'))
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
      const response = await agentPaneService.acceptCommandBatch(pending.request)
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
    if (pendingAcceptance != null) {
      setActionError(t('ai.runtime.action.acceptancePending'))
      return
    }
    const payload = trimMessageParts(payloadParts ?? parts)
    if (
      !hasMessageContent(payload)
      || slashQueryOf(payload) != null
      || activeDraft == null
      || (isEntryTarget(target) && treeEntriesQuery.data == null)
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
        if (pendingAcceptance != null) {
          setActionError(t('ai.runtime.action.acceptancePending'))
          return
        }
        setInteraction('thread-sessions')
        return
      case 'tree':
        if (pendingAcceptance != null) {
          setActionError(t('ai.runtime.action.acceptancePending'))
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
      setLocalDraft((current) => materializeAgentBranchDraft(
        agent,
        models,
        current,
        defaults.yoloEnabled ?? false,
        defaults.environment ?? null,
      ))
    }
    setInteraction(null)
    setActionError(null)
  }

  function selectEnvironment(environment: EnvironmentBindingDTO | null): void {
    if (isBoundTarget(target)) {
      branchPanel.selectEnvironment(environment)
    } else {
      setLocalDraft((current) => current ? { ...current, environment } : current)
    }
    setInteraction(null)
  }

  function selectEntry(entry: HarnessSessionEntryDTO): void {
    const draft = branchDraftFromEntry(entry, activeDraft)
    setThreadNavigationSessionId(null)
    changeTarget({
      kind: 'ENTRY_DRAFT',
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
  const pending = pendingAcceptance != null || controller.pending
  const composerDraft = isBoundTarget(target) ? controller.draft : parts
  const composer: ChatPanelComposerInput = {
    parts: composerDraft,
    pending,
    disabled:
      pending
      || (isBoundTarget(target)
        ? controller.disabled || branchPanel.branchState == null || branchPanel.effectiveBase == null
        : activeDraft == null || (isEntryTarget(target) && treeEntriesQuery.data == null)),
    onPartsChange: isBoundTarget(target) ? controller.setDraft : setParts,
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
  const environment = activeDraft?.environment ?? null
  const environmentReadyByName = useMemo(
    () => new Map(environments.map((item) => [item.name, item.ready])),
    [environments],
  )
  const environmentReady = environment == null
    ? undefined
    : environmentReadyByName.get(environment.name) ?? false
  const { gitBranch } = useEnvironmentWorkspaceMetadata(environment, environmentReady)
  const error = actionError
    ?? (isBoundTarget(target) ? branchPanel.yoloError ?? controller.actionError : null)
    ?? (isEntryTarget(target) && treeEntriesQuery.error
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
    selectEnvironment,
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
    environment,
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
    environment: draft.environment ? { ...draft.environment } : null,
    model: { ...draft.model },
    activeTools: [...draft.activeTools],
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
    environment: decodeEnvironment(settings.environment, fallback.environment),
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
    activeTools: Array.isArray(settings.activeTools)
      ? settings.activeTools.filter((item): item is string => typeof item === 'string')
      : fallback.activeTools,
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

function decodeEnvironment(
  value: unknown,
  fallback: EnvironmentBindingDTO | null,
): EnvironmentBindingDTO | null {
  if (value == null) {
    return value === null ? null : fallback
  }
  if (!isRecord(value) || typeof value.name !== 'string' || typeof value.workspacePath !== 'string') {
    return fallback
  }
  return { name: value.name, workspacePath: value.workspacePath }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

export function sessionSelectionItem(session: RuntimeSessionSummaryDTO) {
  return {
    id: session.sessionId,
    title: session.firstMessagePreview || session.sessionId,
    subtitle: `${formatBackendDate(session.lastActivityAt)} · ${session.threadCount} Threads`,
    badge: session.sessionId,
  }
}

export function threadSelectionItem(thread: RuntimeThreadSummaryDTO) {
  return {
    id: thread.threadId,
    title: thread.headMessagePreview || thread.threadId,
    subtitle: `${formatBackendDate(thread.updatedAt)} · ${thread.status}`,
    badge: thread.threadId,
  }
}
