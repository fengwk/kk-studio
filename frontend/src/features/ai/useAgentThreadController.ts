import { useEffect, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { extractContextWindow } from '@/features/ai/ai-model-draft-codec'
import { buildThreadTimeline, isThreadWorking } from '@/features/ai/thread-timeline'
import type { ThreadCommand } from '@/features/ai/thread-panel/thread-commands'
import {
  createClientMessageId,
  useAgentThreadMessageMutation,
} from '@/features/ai/useAgentThreadMessageMutation'
import { useAgentThreadQueries } from '@/features/ai/useAgentThreadQueries'
import { useChatTranscriptAutoScroll } from '@/features/ai/useChatTranscriptAutoScroll'
import { useHarnessThreadRealtime } from '@/features/ai/useHarnessThreadRealtime'
import { useHarnessThreadObservability } from '@/features/ai/useHarnessThreadObservability'
import { useHarnessTaskTimeline } from '@/features/ai/useHarnessTaskTimeline'
import { harnessService } from '@/shared/api/harness-service'
import { formatModelRef, type AgentModelView } from '@/features/ai/AgentModelView'
import type {
  AgentDefinitionDTO,
  AgentProviderDTO,
  HarnessThreadDTO,
} from '@/shared/api/contracts'
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

export function useAgentThreadController(threadId: string, sessionIdHint = '', initialDraft = '') {
  const [draft, setDraftState] = useState(initialDraft)
  const [actionError, setActionError] = useState<string | null>(null)
  // Local in-flight count keeps pending accurate across overlapping mutateAsync calls.
  const [inFlightSubmissions, setInFlightSubmissions] = useState(0)
  const clientMessageIdRef = useRef<string | null>(null)
  const replayContentRef = useRef<string | null>(null)
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
    session,
    threadQuery,
    entriesQuery,
  } = useAgentThreadQueries(threadId, sessionIdHint)
  const createMessageMutation = useAgentThreadMessageMutation(threadId)
  const timeline = buildThreadTimeline(entries, inputs)
  const working = isThreadWorking(thread, timeline)
  const agentsById = new Map(agents.map((agent) => [String(agent.id), agent]))
  const currentAgent = thread?.activeAgentDefinitionId
    ? agentsById.get(String(thread.activeAgentDefinitionId))
    : undefined
  const runtimeLabels = resolveRuntimeLabels(thread, currentAgent, models, providers)
  const observability = useHarnessThreadObservability(threadId, working)
  const taskTimeline = useHarnessTaskTimeline(
    thread?.sessionId ?? sessionIdHint,
    threadQuery.isSuccess && Boolean(thread?.sessionId || sessionIdHint),
  )

  useChatTranscriptAutoScroll(
    bodyRef,
    timeline.messages.length,
    entries.length + inputs.length,
  )
  useHarnessThreadRealtime(threadId, Boolean(threadId))

  useEffect(() => {
    setDraftState(initialDraft)
    clientMessageIdRef.current = null
    replayContentRef.current = null
  }, [initialDraft, threadId])

  const stopMutation = useMutation({
    mutationFn: () => harnessService.stopThread(threadId),
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
  const setModelMutation = useMutation({
    mutationFn: (payload: { modelId: string; variant: string }) =>
      harnessService.setThreadModel(threadId, {
        modelId: payload.modelId,
        variant: payload.variant,
        clientMessageId: createClientMessageId(),
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.threads.detail(threadId) })
      await queryClient.invalidateQueries({ queryKey: queryKeys.threads.inputs(threadId) })
    },
  })

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
      .mutateAsync({ content, clientMessageId })
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
        setActionError(errorMessage(error))
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
        const enabled = !(thread?.yoloEnabled ?? false)
        observability.setYolo(enabled)
        return
      }
      case 'stop':
        void stopThread()
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
    return stopMutation
      .mutateAsync()
      .then(async () => {
        // Stop is not request-idempotent; clear local message replay identity only on success.
        clientMessageIdRef.current = null
        replayContentRef.current = null
        await Promise.all([
          queryClient.invalidateQueries({ queryKey: queryKeys.threads.detail(threadId) }),
          queryClient.invalidateQueries({ queryKey: queryKeys.threads.entries(threadId) }),
          queryClient.invalidateQueries({ queryKey: queryKeys.threads.inputs(threadId) }),
          queryClient.invalidateQueries({ queryKey: queryKeys.sessions.threads(thread.sessionId) }),
        ])
      })
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

  function setThreadModel(modelId: string, variant: string): Promise<void> {
    setActionError(null)
    return setModelMutation
      .mutateAsync({ modelId, variant })
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
    messagesError: threadQuery.error || entriesQuery.error,
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
    setThreadAgent,
    setThreadModel,
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
    agentName: firstNonEmpty(thread?.activeAgentName, agent?.name, thread?.activeAgentDefinitionId, '（无 Agent）'),
    providerName,
    // Canonical display identity is provider/model.
    modelName: formatModelRef(providerName, bareModelName),
    variantName: firstNonEmpty(thread?.variant, agent?.variant),
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
