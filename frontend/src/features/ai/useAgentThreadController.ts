import { useEffect, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { buildThreadTimeline, isThreadWorking } from '@/features/ai/thread-events'
import type { ThreadCommand } from '@/features/ai/thread-panel/thread-commands'
import {
  createClientMessageId,
  useAgentThreadMessageMutation,
} from '@/features/ai/useAgentThreadMessageMutation'
import { useAgentThreadQueries } from '@/features/ai/useAgentThreadQueries'
import { useChatTranscriptAutoScroll } from '@/features/ai/useChatTranscriptAutoScroll'
import { useHarnessThreadEventStream } from '@/features/ai/useHarnessThreadEventStream'
import { useHarnessThreadObservability } from '@/features/ai/useHarnessThreadObservability'
import { useHarnessTaskTimeline } from '@/features/ai/useHarnessTaskTimeline'
import { harnessService } from '@/shared/api/harness-service'
import type { AgentDefinitionDTO, AgentModelDTO, AgentProviderDTO, HarnessThreadDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message.trim()) {
    return error.message
  }
  if (typeof error === 'string' && error.trim()) {
    return error
  }
  return '请求失败'
}

export function useAgentThreadController(threadId: string, sessionId: string, initialDraft = '') {
  const [draft, setDraftState] = useState(initialDraft)
  const [actionError, setActionError] = useState<string | null>(null)
  // Local in-flight count keeps pending accurate across overlapping mutateAsync calls.
  const [inFlightSubmissions, setInFlightSubmissions] = useState(0)
  const clientMessageIdRef = useRef<string | null>(null)
  const retryContentRef = useRef<string | null>(null)
  const stopRequestIdRef = useRef<string | null>(null)
  const handledStopIdsRef = useRef(new Set<string>())
  const bodyRef = useRef<HTMLDivElement>(null)
  const queryClient = useQueryClient()

  const {
    threads,
    agents,
    models,
    providers,
    thread,
    entries,
    inputs,
    events,
    session,
    threadQuery,
    entriesQuery,
    eventsQuery,
  } = useAgentThreadQueries(threadId, sessionId)
  const createMessageMutation = useAgentThreadMessageMutation(threadId)
  const timeline = buildThreadTimeline(entries, inputs, events)
  const working = isThreadWorking(thread, timeline)
  const agentsById = new Map(agents.map((agent) => [String(agent.id), agent]))
  const currentAgent = thread?.activeAgentDefinitionId
    ? agentsById.get(String(thread.activeAgentDefinitionId))
    : undefined
  const runtimeLabels = resolveRuntimeLabels(thread, currentAgent, models, providers)
  const observability = useHarnessThreadObservability(threadId, working)
  const taskTimeline = useHarnessTaskTimeline(
    thread?.sessionId ?? sessionId,
    threadQuery.isSuccess && Boolean(thread?.sessionId || sessionId),
  )

  useChatTranscriptAutoScroll(
    bodyRef,
    timeline.messages.length,
    entries.length + events.length,
  )
  useHarnessThreadEventStream(threadId, Boolean(threadId) && eventsQuery.isSuccess)

  useEffect(() => {
    setDraftState(initialDraft)
    clientMessageIdRef.current = null
    retryContentRef.current = null
  }, [initialDraft, threadId])

  const stopMutation = useMutation({
    mutationFn: (clientRequestId: string) => harnessService.stopThread(threadId, { clientRequestId }),
  })
  const retryMutation = useMutation({
    mutationFn: () => harnessService.retryThread(threadId),
  })
  const setAgentMutation = useMutation({
    mutationFn: (agentDefinitionId: string) =>
      harnessService.setThreadAgent(threadId, {
        agentDefinitionId,
        clientMessageId: createClientMessageId(),
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.threads.detail(threadId) })
      await queryClient.invalidateQueries({ queryKey: queryKeys.threads.inputs(threadId) })
    },
  })

  function setDraft(next: string) {
    // Editing restored draft to different content resets retry identity.
    if (retryContentRef.current != null && next !== retryContentRef.current) {
      clientMessageIdRef.current = null
      retryContentRef.current = null
    }
    setDraftState(next)
  }

  function submitMessage(): Promise<void> {
    const content = draft.trim()
    // Composer stays enabled while processing or while a prior HTTP request is in flight.
    if (!content || content.startsWith('/') || !thread) {
      return Promise.resolve()
    }
    setActionError(null)
    // Reuse clientMessageId only when retrying the same restored content after a failure.
    const isRetry = retryContentRef.current === content && Boolean(clientMessageIdRef.current)
    const clientMessageId = isRetry ? clientMessageIdRef.current! : createClientMessageId()
    clientMessageIdRef.current = clientMessageId
    // Capture content + id then clear draft immediately so the next message can be typed.
    setDraftState('')
    setInFlightSubmissions((count) => count + 1)
    // Per-call mutateAsync Promise: do not rely on mutate() observer callbacks under overlap.
    // Catch settles the returned promise so concurrent fire-and-forget callers stay safe.
    return createMessageMutation
      .mutateAsync({ content, clientMessageId })
      .then(() => {
        // Only clear identity when it still belongs to this completing request.
        if (clientMessageIdRef.current === clientMessageId) {
          clientMessageIdRef.current = null
        }
        if (retryContentRef.current === content) {
          retryContentRef.current = null
        }
      })
      .catch((error: unknown) => {
        setActionError(errorMessage(error))
        // Restore only when the composer is empty so an in-progress next draft is preserved.
        setDraftState((current) => {
          if (current.trim() === '') {
            retryContentRef.current = content
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
        const enabled = !(thread?.yoloEnabled ?? false)
        observability.setYolo(enabled)
        return
      }
      case 'clear-draft':
        setDraft('')
        return
      case 'stop':
        void stopThread()
        return
      case 'retry':
        void retryThread()
        return
      default:
        setActionError(`未知命令：${command.id}`)
    }
  }

  function stopThread(): Promise<void> {
    if (!thread) {
      return Promise.resolve()
    }
    setActionError(null)
    const clientRequestId = stopRequestIdRef.current ?? createClientMessageId()
    stopRequestIdRef.current = clientRequestId
    return stopMutation.mutateAsync(clientRequestId)
      .then(async (result) => {
        // A completed Stop receipt closes this idempotency attempt. A later Stop is a new request.
        stopRequestIdRef.current = null
        if (!handledStopIdsRef.current.has(result.stopId)) {
          handledStopIdsRef.current.add(result.stopId)
          const restored = result.restoredMessages.filter((message) => message.trim())
          if (restored.length > 0) {
            setDraftState((current) => [...restored, current.trim()].filter(Boolean).join('\n\n'))
          }
          clientMessageIdRef.current = null
          retryContentRef.current = null
        }
        await Promise.all([
          queryClient.invalidateQueries({ queryKey: queryKeys.threads.detail(threadId) }),
          queryClient.invalidateQueries({ queryKey: queryKeys.threads.entries(threadId) }),
          queryClient.invalidateQueries({ queryKey: queryKeys.threads.inputs(threadId) }),
          queryClient.invalidateQueries({ queryKey: queryKeys.threads.events(threadId) }),
          queryClient.invalidateQueries({ queryKey: queryKeys.sessions.threads(thread.sessionId) }),
        ])
      })
      .catch((error: unknown) => setActionError(errorMessage(error)))
  }

  function retryThread(): Promise<void> {
    if (thread?.status !== 'FAILED') {
      return Promise.resolve()
    }
    setActionError(null)
    return retryMutation.mutateAsync()
      .then(() => queryClient.invalidateQueries({ queryKey: queryKeys.threads.detail(threadId) }))
      .catch((error: unknown) => setActionError(errorMessage(error)))
  }

  function setThreadAgent(agentDefinitionId: string): Promise<void> {
    setActionError(null)
    return setAgentMutation
      .mutateAsync(agentDefinitionId)
      .then(() => undefined)
      .catch((error: unknown) => {
        setActionError(errorMessage(error))
      })
  }

  return {
    threads,
    session,
    agents,
    agentsById,
    models,
    providers,
    thread,
    title: session?.title || thread?.sessionTitle || thread?.threadId || 'Chat',
    agent: currentAgent,
    timeline,
    runtimeLabels,
    working,
    messagesLoading: threadQuery.isLoading || entriesQuery.isLoading,
    messagesError: threadQuery.error || entriesQuery.error || eventsQuery.error,
    bodyRef,
    draft,
    // Overlapping submits keep pending accurate via local count, not mutation observer alone.
    pending: inFlightSubmissions > 0,
    disabled: !thread,
    observability: {
      ...observability,
      yolo: { enabled: Boolean(thread?.yoloEnabled) },
    },
    taskTimeline,
    actionError,
    dismissActionError: () => setActionError(null),
    setDraft,
    submitMessage,
    stopThread,
    retryThread,
    setThreadAgent,
    runCommand,
  }
}

function resolveRuntimeLabels(
  thread: HarnessThreadDTO | undefined,
  agent: AgentDefinitionDTO | undefined,
  models: AgentModelDTO[],
  providers: AgentProviderDTO[],
) {
  const modelId = firstNonEmpty(thread?.modelId, agent?.modelId)
  const model = models.find((item) => String(item.id) === modelId) ?? models.find((item) => item.name === modelId)
  const provider = model
    ? providers.find((item) => String(item.id) === String(model.providerId))
    : undefined
  const contextWindow = parseContextWindow(model)

  return {
    agentName: firstNonEmpty(thread?.activeAgentName, agent?.name, thread?.activeAgentDefinitionId, '（无 Agent）'),
    providerName: firstNonEmpty(provider?.name, model?.providerName),
    modelName: firstNonEmpty(model?.name, modelId),
    variantName: firstNonEmpty(thread?.variant, agent?.variant, 'default'),
    contextWindow,
  }
}

function parseContextWindow(model: AgentModelDTO | undefined): number | undefined {
  if (!model?.variantsJson) {
    return undefined
  }
  try {
    const config = JSON.parse(model.variantsJson) as unknown
    if (config && typeof config === 'object' && !Array.isArray(config)) {
      const value = Number((config as { contextWindow?: number }).contextWindow)
      return Number.isFinite(value) && value > 0 ? value : undefined
    }
  } catch {
    return undefined
  }
  return undefined
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
