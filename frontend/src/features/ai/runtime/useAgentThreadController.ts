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
import {
  createClientMessageId,
  useAgentThreadMessageMutation,
} from '@/features/ai/runtime/useAgentThreadMessageMutation'
import {
  sameThreadMessagePayload,
  type ThreadMessagePayload,
  type ThreadMessageReplay,
} from '@/features/ai/runtime/thread-message-retry'
import { useAgentThreadQueries } from '@/features/ai/runtime/useAgentThreadQueries'
import { useChatTranscriptAutoScroll } from '@/features/ai/runtime/useChatTranscriptAutoScroll'
import { useHarnessThreadRealtime } from '@/features/ai/runtime/useHarnessThreadRealtime'
import { useHarnessThreadObservability } from '@/features/ai/runtime/useHarnessThreadObservability'
import { isConflictError } from '@/shared/api/client'
import { harnessService } from '@/shared/api/harness-service'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
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

export type { ThreadMessageReplay } from '@/features/ai/runtime/thread-message-retry'

export interface ThreadTurnSettings {
  agentName: string
  environmentName: string | null
  yoloEnabled: boolean
}

export function useAgentThreadController(
  threadId: string,
  initialDraft = '',
  initialReplay?: ThreadMessageReplay,
  visibleSettings: ThreadTurnSettings = {
    agentName: '',
    environmentName: null,
    yoloEnabled: false,
  },
) {
  const { t } = useI18n()
  const [draft, setDraftState] = useState(initialDraft)
  const [actionError, setActionError] = useState<string | null>(null)
  // Local in-flight count keeps pending accurate across overlapping mutateAsync calls.
  const [inFlightSubmissions, setInFlightSubmissions] = useState(0)
  const clientMessageIdRef = useRef<string | null>(null)
  const replayPayloadRef = useRef<ThreadMessagePayload | null>(null)
  const initializedReplayThreadRef = useRef<string | null>(null)
  const bodyRef = useRef<HTMLDivElement>(null)
  const queryClient = useQueryClient()

  const {
    agents,
    models,
    thread,
    sessionId,
    entries,
    inputs,
    snapshotQuery,
    modelInvocations,
    toolInvocations,
    usage,
  } = useAgentThreadQueries(threadId)
  const bound = Boolean(thread)
  const createMessageMutation = useAgentThreadMessageMutation(threadId)
  const modelStream = useHarnessThreadRealtime(
    threadId,
    Boolean(threadId) && snapshotQuery.isSuccess,
    thread?.revision,
    modelInvocations,
  )
  const timeline = buildThreadTimeline(entries, inputs, modelStream)
  const working = isThreadWorking(thread, timeline)
  const currentAgent = agents.find((agent) => agent.name === visibleSettings.agentName)
  const runtimeLabels = resolveRuntimeLabels(visibleSettings, currentAgent, models)
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
    replayPayloadRef.current = initialReplay ?? null
    initializedReplayThreadRef.current = threadId
  }, [initialDraft, initialReplay, threadId])

  const stopMutation = useMutation({
    mutationFn: (expectedExecutionEpoch: BackendLong) =>
      harnessService.stopThread(threadId, { expectedExecutionEpoch }),
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
    if (replayPayloadRef.current != null && next !== replayPayloadRef.current.content) {
      clientMessageIdRef.current = null
      replayPayloadRef.current = null
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
      setActionError(t('ai.runtime.action.threadNotLoaded'))
      return Promise.resolve()
    }
    setActionError(null)
    const previousPayload = replayPayloadRef.current
    const payload: ThreadMessagePayload = {
      kind: previousPayload?.kind ?? 'USER_MESSAGE',
      role: previousPayload?.role ?? 'user',
      content,
      agentName: visibleSettings.agentName,
      environmentName: visibleSettings.environmentName,
      yoloEnabled: visibleSettings.yoloEnabled,
      firstSendContext: previousPayload?.firstSendContext ?? null,
    }
    // Reuse clientMessageId only when the complete semantic payload is unchanged.
    const isReplay = (
      Boolean(clientMessageIdRef.current)
      && sameThreadMessagePayload(previousPayload, payload)
    )
    const clientMessageId = isReplay ? clientMessageIdRef.current! : createClientMessageId()
    clientMessageIdRef.current = clientMessageId
    replayPayloadRef.current = payload
    // Capture content + id then clear draft immediately so the next message can be typed.
    setDraftState('')
    setInFlightSubmissions((count) => count + 1)
    // Per-call mutateAsync Promise: do not rely on mutate() observer callbacks under overlap.
    // Catch settles the returned promise so concurrent fire-and-forget callers stay safe.
    return createMessageMutation
      .mutateAsync({
        kind: payload.kind,
        role: payload.role,
        firstSendContext: payload.firstSendContext,
        content,
        agentName: visibleSettings.agentName,
        environmentName: visibleSettings.environmentName,
        yoloEnabled: visibleSettings.yoloEnabled,
        clientMessageId,
        expectedExecutionEpoch: thread.executionEpoch,
      })
      .then(() => {
        // Only clear identity when it still belongs to this completing request.
        if (clientMessageIdRef.current === clientMessageId) {
          clientMessageIdRef.current = null
        }
        if (replayPayloadRef.current === payload) {
          replayPayloadRef.current = null
        }
      })
      .catch((error: unknown) => {
        reportMutationError(error, 'ai.runtime.action.sendFailed')
        // Restore only when the composer is empty so an in-progress next draft is preserved.
        setDraftState((current) => {
          if (current.trim() === '') {
            replayPayloadRef.current = payload
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
    return stopMutation
      .mutateAsync(thread.executionEpoch)
      .then(async () => {
        // Stop is not request-idempotent; clear local message replay identity only on success.
        clientMessageIdRef.current = null
        replayPayloadRef.current = null
        await Promise.all([
          queryClient.invalidateQueries({ queryKey: queryKeys.threads.snapshot(threadId) }),
          queryClient.invalidateQueries({ queryKey: queryKeys.threads.list }),
        ])
      })
      .catch((error: unknown) => reportMutationError(error, 'ai.runtime.action.stopFailed'))
  }

  return {
    sessionId,
    agents,
    models,
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
    // A Thread must be loaded before it can accept a message.
    disabled: !bound,
    observability: {
      ...observability,
      yolo: { enabled: visibleSettings.yoloEnabled },
    },
    actionError,
    dismissActionError: () => setActionError(null),
    setDraft,
    submitMessage,
    stopThread,
    runCommand,
  }
}

function resolveRuntimeLabels(
  visibleSettings: ThreadTurnSettings,
  agent: AgentDefinitionDTO | undefined,
  models: AgentModelView[],
) {
  const model = models.find((item) => modelRef(item) === agent?.model)
  const contextWindow = extractContextWindow(model)

  const providerName = firstNonEmpty(model?.providerName)
  const bareModelName = firstNonEmpty(model?.name, agent?.model)
  return {
    agentName: firstNonEmpty(visibleSettings.agentName, translate('ai.runtime.action.blankAgent')),
    providerName,
    // Canonical display identity is provider/model.
    modelName: formatModelRef(providerName, bareModelName),
    variantName: firstNonEmpty(agent?.variant),
    environmentName: firstNonEmpty(visibleSettings.environmentName),
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
