import { useRef, useState } from 'react'
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

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message.trim()) {
    return error.message
  }
  if (typeof error === 'string' && error.trim()) {
    return error
  }
  return '请求失败'
}

export function useAgentThreadController(threadId: string) {
  const [draft, setDraftState] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  // Local in-flight count keeps pending accurate across overlapping mutateAsync calls.
  const [inFlightSubmissions, setInFlightSubmissions] = useState(0)
  const clientMessageIdRef = useRef<string | null>(null)
  const retryContentRef = useRef<string | null>(null)
  const bodyRef = useRef<HTMLDivElement>(null)

  const {
    threads,
    agents,
    models,
    providers,
    thread,
    entries,
    inputs,
    events,
    threadQuery,
    entriesQuery,
    eventsQuery,
  } = useAgentThreadQueries(threadId)
  const createMessageMutation = useAgentThreadMessageMutation(threadId)
  const agentsById = new Map(agents.map((agent) => [String(agent.id), agent]))
  const timeline = buildThreadTimeline(entries, inputs, events)
  const working = isThreadWorking(thread, timeline)
  const currentAgent = thread?.agentDefinitionId ? agentsById.get(thread.agentDefinitionId) : undefined
  const runtimeLabels = resolveRuntimeLabels(
    currentAgent,
    timeline,
    models as unknown as Array<Record<string, unknown>>,
    providers as unknown as Array<Record<string, unknown>>,
  )
  const observability = useHarnessThreadObservability(threadId, working)
  const taskTimeline = useHarnessTaskTimeline(
    thread?.sessionId ?? '',
    threadQuery.isSuccess && Boolean(thread?.sessionId),
  )

  useChatTranscriptAutoScroll(
    bodyRef,
    timeline.messages.length,
    entries.length + inputs.length + events.length,
  )
  useHarnessThreadEventStream(threadId, Boolean(threadId) && eventsQuery.isSuccess)

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
      default:
        setActionError(`未知命令：${command.id}`)
    }
  }

  return {
    threads,
    agentsById,
    thread,
    title: thread?.sessionTitle || thread?.threadId || 'Chat',
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
    controlsPending: observability.yoloPending,
    actionError,
    dismissActionError: () => setActionError(null),
    setDraft,
    submitMessage,
    runCommand,
  }
}

function resolveRuntimeLabels(
  agent: unknown,
  timeline: ReturnType<typeof buildThreadTimeline>,
  models: Array<Record<string, unknown>>,
  providers: Array<Record<string, unknown>>,
) {
  const agentRecord = asRecord(agent)
  const snapshotModel = timeline.runtimeContext.model
  const modelId = firstNonEmpty(agentRecord.defaultModelId, agentRecord.modelId, snapshotModel)
  const model =
    models.find((item) => String(item.id) === modelId)
    ?? models.find((item) => String(item.name) === modelId)
    ?? {}
  const providerId = firstNonEmpty(model.providerId, agentRecord.defaultProviderId)
  const provider = providers.find((item) => String(item.id) === providerId) ?? {}
  const contextWindow = parseContextWindow(model)

  return {
    agentName: firstNonEmpty(agentRecord.name, 'agent'),
    providerName: firstNonEmpty(provider.name, agentRecord.defaultProviderName, model.providerName),
    // Prefer catalog model name, then agent defaults, then snapshot model string.
    modelName: firstNonEmpty(model.name, agentRecord.defaultModelName, snapshotModel),
    variantName: firstNonEmpty(
      timeline.runtimeContext.variant,
      agentRecord.variant,
      agentRecord.defaultVariant,
      'default',
    ),
    contextWindow,
  }
}

function parseContextWindow(model: Record<string, unknown>): number | undefined {
  const raw = model.configJson ?? model.config
  if (!raw) {
    return undefined
  }
  try {
    const config = typeof raw === 'string' ? JSON.parse(raw) : raw
    const value = Number((config as { contextWindow?: number }).contextWindow)
    return Number.isFinite(value) && value > 0 ? value : undefined
  } catch {
    return undefined
  }
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' ? (value as Record<string, unknown>) : {}
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
