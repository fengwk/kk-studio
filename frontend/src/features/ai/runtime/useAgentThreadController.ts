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
 * One failed-send replay identity: the exact command batch (same IDs/payload/order and original
 * expected cursors) plus the restored composer content.
 */
export interface CommandBatchReplay {
  plan: CommandBatchPlan
  content: string
}

/**
 * One ambiguous Stop operation. Retries must send the EXACT original body (same stopRequestId +
 * original expectedRevision); the basis identifies the snapshot the operation was minted against,
 * so an authoritative snapshot proving the basis moved (old Turn ended / head or revision
 * advanced) automatically retires the operation — the next Stop on a new Turn mints a fresh id.
 * Java StopControl replays by durable key BEFORE the revision CAS, so the original
 * expectedRevision stays valid for the exact retry.
 */
export interface PendingStopOperation {
  stopRequestId: string
  expectedRevision: string
  basisHeadEntryId: string
  basisRevision: string
}

/**
 * Basis fence shared by stopThread (SYNCHRONOUS, before every reuse) and the passive
 * cleanup effect: a pending operation is retired exactly when the authoritative snapshot
 * proves its basis moved (head or revision advanced => the old Turn ended or the Thread
 * advanced). Retiring synchronously inside stopThread closes the window where a Stop is
 * invoked after the snapshot advanced but before the effect flushed — the next Stop on the
 * new Turn must mint a fresh id, never reuse the old id against a newer revision.
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
  initialDraft = '',
  initialReplay?: CommandBatchReplay,
  buildBatch: ((content: string) => CommandBatchPlan | null) | null = null,
  environmentNames: ReadonlyMap<string, string> | null = null,
) {
  const { t } = useI18n()
  const [draft, setDraftState] = useState(initialDraft)
  const draftRef = useRef(initialDraft)
  const [actionError, setActionError] = useState<string | null>(null)
  // Local in-flight count keeps pending accurate across overlapping mutateAsync calls.
  const [inFlightSubmissions, setInFlightSubmissions] = useState(0)
  // An undecided exact batch (in flight or kept after an uncertain network failure) is a pane
  // transition blocker: switching Threads must not drop a byte-for-byte replay opportunity.
  const [replayPending, setReplayPending] = useState(Boolean(initialReplay))
  const replayRef = useRef<CommandBatchReplay | null>(null)
  const initializedReplayThreadRef = useRef<string | null>(null)
  const pendingStopRef = useRef<PendingStopOperation | null>(null)
  // True while an ambiguous Stop operation is waiting to be retried or retired: the pane must
  // not silently drop the exact retry by switching Threads.
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
  const runtimeLabels = resolveRuntimeLabels(thread, agents, models, environmentNames)

  useChatTranscriptAutoScroll(
    bodyRef,
    timeline.messages.length,
    entries.length + queuedCommands.length,
  )

  useEffect(() => {
    if (initializedReplayThreadRef.current === threadId) {
      return
    }
    setDraftState(initialDraft)
    draftRef.current = initialDraft
    replayRef.current = initialReplay ?? null
    setReplayPending(initialReplay != null)
    initializedReplayThreadRef.current = threadId
    // Rebinding to another Thread must not leak stop/decision replay identity.
    pendingStopRef.current = null
    setStopReplayPending(false)
    decisionIdByInvocation.current.clear()
  }, [initialDraft, initialReplay, threadId])

  const approvalMutation = useMutation({
    mutationFn: ({
      invocationId,
      decision,
      decisionId,
    }: {
      invocationId: string
      decision: 'ALLOW' | 'DENY'
      decisionId: string
    }) => harnessService.decideApproval(threadId, invocationId, {
      decision,
      decisionId,
      actor: 'web',
      reason: null,
    }),
    onSuccess: async (_result, variables) => {
      decisionIdByInvocation.current.delete(`${variables.invocationId}:${variables.decision}`)
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) }),
        // Approval updates the ToolInvocation projected into the Chat-scoped Thread list.
        queryClient.invalidateQueries({ queryKey: queryKeys.chats.all }),
      ])
    },
  })

  /**
   * Approves/denies a pending ToolInvocation. The decision id stays stable across retries of
   * the SAME decision; switching the decision (ALLOW -> DENY) mints a new id — the server 409s
   * when a previous decision already landed, which the refresh surfaces.
   */
  function decideApproval(invocationId: string, decision: 'ALLOW' | 'DENY'): Promise<void> {
    const key = `${invocationId}:${decision}`
    const decisionId = decisionIdByInvocation.current.get(key) ?? createDecisionId()
    decisionIdByInvocation.current.set(key, decisionId)
    return approvalMutation
      .mutateAsync({ invocationId, decision, decisionId })
      .then(() => undefined)
      .catch((error: unknown) => {
        reportMutationError(error, 'ai.runtime.action.approvalFailed')
      })
  }

  const stopMutation = useMutation({
    mutationFn: (body: { stopRequestId: string; expectedRevision: string }) =>
      harnessService.stopThread(threadId, body),
  })

  /**
   * A 409 means the local revision is stale or the Thread is not quiescent. Surface an explicit
   * message and refetch the Thread so the next attempt carries the current revision.
   */
  function reportMutationError(error: unknown, fallbackKey: string) {
    if (isConflictError(error)) {
      setActionError(
        t('ai.runtime.action.threadStateChanged', {
          action: t(fallbackKey),
          error: errorMessage(error),
        }),
      )
      void queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) })
      return
    }
    setActionError(errorMessage(error))
  }

  function setDraft(next: string) {
    // Editing restored draft to different content resets request replay identity.
    if (replayRef.current != null && next !== replayRef.current.content) {
      replayRef.current = null
      setReplayPending(false)
    }
    draftRef.current = next
    setDraftState(next)
  }

  function submitMessage(): Promise<void> {
    const content = draft.trim()
    if (!content || content.startsWith('/') || !thread) {
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
    const plan = buildBatch(content)
    if (plan == null) {
      return Promise.resolve()
    }
    // Retry reuses the exact previous batch (same command IDs/payload/order and original
    // expected cursors: the server ordered command-set replay bypasses moved cursors).
    const previous = replayRef.current
    const reused = previous != null && previous.plan.identity === plan.identity
      ? previous.plan
      : plan
    replayRef.current = { plan: reused, content }
    setReplayPending(true)
    // Capture content + id then clear draft immediately so the next message can be typed.
    draftRef.current = ''
    setDraftState('')
    setInFlightSubmissions((count) => count + 1)
    return batchMutation
      .mutateAsync(reused.batch)
      .then(() => {
        // Only clear identity when it still belongs to this completing request.
        if (replayRef.current?.plan === reused) {
          replayRef.current = null
          setReplayPending(false)
        }
      })
      .catch((error: unknown) => {
        reportMutationError(error, 'ai.runtime.action.sendFailed')
        // Restore only when the composer is empty so an in-progress next draft is preserved.
        // (Ref mutations must happen OUTSIDE state updaters: React defers updater functions.)
        if (draftRef.current.trim() === '') {
          if (isConflictError(error)) {
            // 409 = the batch was NOT accepted: the exact replay is stale. The next send
            // rebuilds against the refreshed head/nextSequence with NEW command ids.
            replayRef.current = null
            setReplayPending(false)
          } else {
            // Network/uncertain failures keep the exact batch for byte-for-byte replay.
            replayRef.current = { plan: reused, content }
            setReplayPending(true)
          }
          draftRef.current = content
          setDraftState(content)
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

  /** Block mailbox actions until the durable Thread snapshot has loaded. */
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
    // SYNCHRONOUS basis fence: never reuse a pending operation whose basis no longer matches
    // the CURRENT render snapshot (head/revision moved => the old Turn ended or the Thread
    // advanced). Retiring here — not only in the passive cleanup effect — closes the window
    // where a Stop is invoked after the snapshot advanced but before the effect flushed.
    const pending = retireStaleStopPending(pendingStopRef.current, thread)
    if (pending !== pendingStopRef.current) {
      pendingStopRef.current = pending
      setStopReplayPending(false)
    }
    // Ambiguous retry: reuse the EXACT previous operation (same stopRequestId + original
    // expectedRevision + basis). A brand-new Stop mints a fresh operation against the current
    // snapshot; the original expectedRevision is never re-derived from a newer snapshot.
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
        // Stop succeeded (or the server replayed the identical earlier Stop): the operation
        // is settled; the next stop mints a fresh id.
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
          // Known 409: the operation was NOT accepted (stale revision / not quiescent /
          // terminal apply pending / id reused). Clear it; the next stop mints a new
          // operation against the refreshed snapshot.
          pendingStopRef.current = null
          setStopReplayPending(false)
        }
        // Network/uncertain failures keep the exact operation for byte-for-byte retry.
        reportMutationError(error, 'ai.runtime.action.stopFailed')
      })
  }

  // Authoritative-snapshot basis reconcile: while an ambiguous Stop operation exists, a
  // snapshot proving its basis changed (head or revision moved => the old Turn ended or the
  // Thread advanced) retires it, so a Stop issued on a NEW Turn always uses a fresh id. The
  // effect only acts when an operation is actually pending — the initial hook mount never
  // clears anything. stopThread itself runs the same fence synchronously before every reuse.
  useEffect(() => {
    const pending = retireStaleStopPending(pendingStopRef.current, thread ?? null)
    if (pending !== pendingStopRef.current) {
      pendingStopRef.current = pending
      setStopReplayPending(false)
    }
    // thread is a fresh snapshot object per refetch; only its identity fields gate the check.
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
    // Overlapping submits keep pending accurate via local count, not mutation observer alone.
    pending: inFlightSubmissions > 0,
    // A Thread must be loaded before it can accept a message.
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
    // An ambiguous Stop operation awaiting an exact retry blocks pane transitions.
    stopReplayPending,
    replayPending,
  }
}

function resolveRuntimeLabels(
  thread: ReturnType<typeof useAgentThreadQueries>['thread'],
  agents: AgentDefinitionDTO[],
  models: AgentModelView[],
  environmentNames: ReadonlyMap<string, string> | null,
) {
  const settings = thread?.branchSettings
  const agent = agents.find((item) => item.name === settings?.agentName)
  const model = models.find((item) => modelRef(item) === agent?.model)
  const contextWindow = extractContextWindow(model)
  const environmentId = settings?.environmentId ?? null
  return {
    agentName: settings?.agentName || translate('ai.runtime.action.blankAgent'),
    providerName: settings?.model.providerName || undefined,
    // Canonical display identity is provider/model.
    modelName: formatModelRef(settings?.model.providerName, settings?.model.modelName),
    variantName: settings?.model.variant || undefined,
    environmentDisplayName: environmentId == null
      ? null
      : (environmentNames?.get(environmentId) ?? environmentId),
    contextWindow,
  }
}
