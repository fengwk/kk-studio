import { useEffect, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  extractContextWindow,
  formatModelRef,
  type AgentModelView,
} from '@/features/ai/catalog'
import { buildThreadTimeline, isThreadWorking } from '@/features/ai/runtime/thread-timeline'
import type { ThreadCommand } from '@/features/ai/runtime'
import {
  createClientMessageId,
  useAgentThreadMessageMutation,
} from '@/features/ai/runtime/useAgentThreadMessageMutation'
import { useAgentThreadQueries } from '@/features/ai/runtime/useAgentThreadQueries'
import { useChatTranscriptAutoScroll } from '@/features/ai/runtime/useChatTranscriptAutoScroll'
import { useHarnessThreadRealtime } from '@/features/ai/runtime/useHarnessThreadRealtime'
import { useHarnessThreadObservability } from '@/features/ai/runtime/useHarnessThreadObservability'
import { isConflictError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import type { AgentDefinitionDTO, AgentProviderDTO } from '@/shared/api/contracts/ai-catalog'
import type { HarnessThreadDTO } from '@/shared/api/contracts/ai-runtime'
import type { BackendLong } from '@/shared/api/contracts/base'
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

export interface ThreadMessageReplay {
  content: string
  clientMessageId: string
}

export function useAgentThreadController(
  threadId: string,
  initialDraft = '',
  initialReplay?: ThreadMessageReplay,
) {
  const { t } = useI18n()
  const [draft, setDraftState] = useState(initialDraft)
  const [actionError, setActionError] = useState<string | null>(null)
  // Local in-flight count keeps pending accurate across overlapping mutateAsync calls.
  const [inFlightSubmissions, setInFlightSubmissions] = useState(0)
  const clientMessageIdRef = useRef<string | null>(null)
  const replayContentRef = useRef<string | null>(null)
  const initializedReplayThreadRef = useRef<string | null>(null)
  const bodyRef = useRef<HTMLDivElement>(null)
  const queryClient = useQueryClient()

  const {
    agents,
    models,
    providers,
    thread,
    sessionId,
    entries,
    inputs,
    snapshotQuery,
    modelInvocations,
    toolInvocations,
    usage,
  } = useAgentThreadQueries(threadId)
  // UNBOUND Threads accept bind/bootstrap only; every mailbox mutation needs a bound head.
  const bound = Boolean(thread && thread.status !== 'UNBOUND')
  const executionEpoch = thread?.executionEpoch
  const createMessageMutation = useAgentThreadMessageMutation(threadId)
  const modelStream = useHarnessThreadRealtime(
    threadId,
    Boolean(threadId) && snapshotQuery.isSuccess,
    thread?.revision,
    modelInvocations,
  )
  const timeline = buildThreadTimeline(entries, inputs, modelStream)
  const working = isThreadWorking(thread, timeline)
  const agentsById = new Map(agents.map((agent) => [String(agent.id), agent]))
  const currentAgent = thread?.activeAgentDefinitionId
    ? agentsById.get(String(thread.activeAgentDefinitionId))
    : undefined
  const runtimeLabels = resolveRuntimeLabels(thread, currentAgent, models, providers)
  const observability = useHarnessThreadObservability(threadId, usage, toolInvocations)

  useChatTranscriptAutoScroll(
    bodyRef,
    timeline.messages.length,
    entries.length + inputs.length,
  )

  useEffect(() => {
    if (initializedReplayThreadRef.current === threadId) {
      return
    }
    setDraftState(initialDraft)
    clientMessageIdRef.current = initialReplay?.clientMessageId ?? null
    replayContentRef.current = initialReplay?.content ?? null
    initializedReplayThreadRef.current = threadId
  }, [initialDraft, initialReplay?.clientMessageId, initialReplay?.content, threadId])

  const stopMutation = useMutation({
    mutationFn: (expectedExecutionEpoch: BackendLong) =>
      harnessService.stopThread(threadId, { expectedExecutionEpoch }),
  })
  const setAgentMutation = useMutation({
    mutationFn: (payload: { agentDefinitionId: string; expectedExecutionEpoch: BackendLong }) =>
      harnessService.setThreadAgent(threadId, {
        agentDefinitionId: payload.agentDefinitionId,
        clientMessageId: createClientMessageId(),
        expectedExecutionEpoch: payload.expectedExecutionEpoch,
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) })
    },
  })
  const setModelMutation = useMutation({
    mutationFn: (payload: {
      modelId: string
      variant: string
      expectedExecutionEpoch: BackendLong
    }) =>
      harnessService.setThreadModel(threadId, {
        modelId: payload.modelId,
        variant: payload.variant,
        clientMessageId: createClientMessageId(),
        expectedExecutionEpoch: payload.expectedExecutionEpoch,
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) })
    },
  })
  const setEnvironmentMutation = useMutation({
    mutationFn: (payload: {
      environmentName: string | null
      expectedExecutionEpoch: BackendLong
    }) =>
      harnessService.setThreadEnvironment(threadId, {
        environmentName: payload.environmentName,
        clientMessageId: createClientMessageId(),
        expectedExecutionEpoch: payload.expectedExecutionEpoch,
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) })
    },
  })

  /**
   * A 409 means the local epoch is stale or the Thread is not quiescent. Surface an explicit
   * message and refetch the Thread so the next attempt carries the current epoch.
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
    if (replayContentRef.current != null && next !== replayContentRef.current) {
      clientMessageIdRef.current = null
      replayContentRef.current = null
    }
    setDraftState(next)
  }

  function submitMessage(): Promise<void> {
    const content = draft.trim()
    // Composer stays enabled while processing or while a prior HTTP request is in flight.
    if (!content || content.startsWith('/') || !thread) {
      return Promise.resolve()
    }
    if (!bound) {
      setActionError(t('ai.runtime.action.unboundThread'))
      return Promise.resolve()
    }
    setActionError(null)
    // Reuse clientMessageId only when replaying the same request after its HTTP submission failed.
    const isReplay = replayContentRef.current === content && Boolean(clientMessageIdRef.current)
    const clientMessageId = isReplay ? clientMessageIdRef.current! : createClientMessageId()
    clientMessageIdRef.current = clientMessageId
    // Capture content + id then clear draft immediately so the next message can be typed.
    setDraftState('')
    setInFlightSubmissions((count) => count + 1)
    // Per-call mutateAsync Promise: do not rely on mutate() observer callbacks under overlap.
    // Catch settles the returned promise so concurrent fire-and-forget callers stay safe.
    return createMessageMutation
      .mutateAsync({ content, clientMessageId, expectedExecutionEpoch: thread.executionEpoch })
      .then(() => {
        // Only clear identity when it still belongs to this completing request.
        if (clientMessageIdRef.current === clientMessageId) {
          clientMessageIdRef.current = null
        }
        if (replayContentRef.current === content) {
          replayContentRef.current = null
        }
      })
      .catch((error: unknown) => {
        reportMutationError(error, 'ai.runtime.action.sendFailed')
        // Restore only when the composer is empty so an in-progress next draft is preserved.
        setDraftState((current) => {
          if (current.trim() === '') {
            replayContentRef.current = content
            clientMessageIdRef.current = clientMessageId
            return content
          }
          return current
        })
      })
      .finally(() => {
        setInFlightSubmissions((count) => Math.max(0, count - 1))
      })
  }

  function runCommand(command: ThreadCommand) {
    setActionError(null)
    switch (command.id) {
      case 'yolo': {
        if (!requireBoundThread('ai.runtime.action.switchYoloFailed')) {
          return
        }
        const enabled = !(thread?.yoloEnabled ?? false)
        observability.setYolo(enabled, executionEpoch!)
        return
      }
      case 'stop':
        void stopThread()
        return
      default:
        setActionError(t('ai.runtime.action.unknownCommand', { command: command.id }))
    }
  }

  /** UNBOUND Threads reject every mailbox Input; block the request before it reaches the server. */
  function requireBoundThread(actionKey: string): boolean {
    if (bound) {
      return true
    }
    setActionError(t('ai.runtime.action.unboundAction', { action: t(actionKey) }))
    return false
  }

  function stopThread(): Promise<void> {
    if (!thread || !requireBoundThread('ai.runtime.action.stopFailed')) {
      return Promise.resolve()
    }
    setActionError(null)
    return stopMutation
      .mutateAsync(thread.executionEpoch)
      .then(async () => {
        // Stop is not request-idempotent; clear local message replay identity only on success.
        clientMessageIdRef.current = null
        replayContentRef.current = null
        await Promise.all([
          queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) }),
          queryClient.invalidateQueries({ queryKey: queryKeys.threads.list }),
        ])
      })
      .catch((error: unknown) => reportMutationError(error, 'ai.runtime.action.stopFailed'))
  }

  function setThreadAgent(agentDefinitionId: string): Promise<void> {
    if (!thread || !requireBoundThread('ai.runtime.action.switchAgentFailed')) {
      return Promise.resolve()
    }
    setActionError(null)
    return setAgentMutation
      .mutateAsync({ agentDefinitionId, expectedExecutionEpoch: thread.executionEpoch })
      .then(() => undefined)
      .catch((error: unknown) => {
        reportMutationError(error, 'ai.runtime.action.switchAgentFailed')
      })
  }

  function setThreadModel(modelId: string, variant: string): Promise<void> {
    if (!thread || !requireBoundThread('ai.runtime.action.switchModelFailed')) {
      return Promise.resolve()
    }
    setActionError(null)
    return setModelMutation
      .mutateAsync({ modelId, variant, expectedExecutionEpoch: thread.executionEpoch })
      .then(() => undefined)
      .catch((error: unknown) => {
        reportMutationError(error, 'ai.runtime.action.switchModelFailed')
      })
  }

  function setThreadEnvironment(environmentName: string | null): Promise<boolean> {
    if (!thread || !requireBoundThread('ai.runtime.action.switchEnvironmentFailed')) {
      return Promise.resolve(false)
    }
    setActionError(null)
    return setEnvironmentMutation
      .mutateAsync({ environmentName, expectedExecutionEpoch: thread.executionEpoch })
      .then(() => true)
      .catch((error: unknown) => {
        reportMutationError(error, 'ai.runtime.action.switchEnvironmentFailed')
        return false
      })
  }

  return {
    sessionId,
    agents,
    agentsById,
    models,
    providers,
    thread,
    bound,
    title: thread?.sessionTitle || thread?.threadId || t('ai.chat.chatLabel'),
    agent: currentAgent,
    timeline,
    runtimeLabels,
    working,
    messagesLoading: snapshotQuery.isLoading,
    messagesError: snapshotQuery.error,
    bodyRef,
    draft,
    // Overlapping submits keep pending accurate via local count, not mutation observer alone.
    pending: inFlightSubmissions > 0,
    // UNBOUND Threads are a legal UI state but cannot send messages or change configuration.
    disabled: !bound,
    observability: {
      ...observability,
      yolo: { enabled: Boolean(thread?.yoloEnabled) },
    },
    actionError,
    dismissActionError: () => setActionError(null),
    setDraft,
    submitMessage,
    stopThread,
    setThreadAgent,
    setThreadModel,
    setThreadEnvironment,
    runCommand,
  }
}

function resolveRuntimeLabels(
  thread: HarnessThreadDTO | undefined,
  agent: AgentDefinitionDTO | undefined,
  models: AgentModelView[],
  providers: AgentProviderDTO[],
) {
  const modelId = firstNonEmpty(thread?.modelId, agent?.modelId)
  const model = models.find((item) => String(item.id) === modelId) ?? models.find((item) => item.name === modelId)
  const provider = model
    ? providers.find((item) => String(item.id) === String(model.providerId))
    : undefined
  const contextWindow = extractContextWindow(model)

  const providerName = firstNonEmpty(provider?.name, model?.providerName)
  const bareModelName = firstNonEmpty(model?.name, modelId)
  return {
    agentName: firstNonEmpty(
      thread?.activeAgentName,
      agent?.name,
      thread?.activeAgentDefinitionId,
      translate('ai.runtime.action.blankAgent'),
    ),
    providerName,
    // Canonical display identity is provider/model.
    modelName: formatModelRef(providerName, bareModelName),
    variantName: firstNonEmpty(thread?.variant, agent?.variant),
    environmentName: firstNonEmpty(thread?.activeEnvironmentName),
    contextWindow,
  }
}

function firstNonEmpty(...values: unknown[]): string {
  for (const value of values) {
    if (value == null) {
      continue
    }
    const text = String(value).trim()
    if (text && text !== '-' && text !== 'undefined' && text !== 'null') {
      return text
    }
  }
  return ''
}
