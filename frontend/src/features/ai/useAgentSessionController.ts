import { useRef, useState } from 'react'
import { buildSessionTimeline, hasActiveRun } from '@/features/ai/session-events'
import type { SessionCommand } from '@/features/ai/session-panel/session-commands'
import { useAgentSessionMessageMutation } from '@/features/ai/useAgentSessionMessageMutation'
import { useAgentSessionQueries } from '@/features/ai/useAgentSessionQueries'
import { useChatTranscriptAutoScroll } from '@/features/ai/useChatTranscriptAutoScroll'
import { useHarnessRunEventStream } from '@/features/ai/useHarnessRunEventStream'
import { useHarnessRunControls } from '@/features/ai/useHarnessRunControls'
import { useHarnessSessionObservability } from '@/features/ai/useHarnessSessionObservability'
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

export function useAgentSessionController(sessionId: string) {
  const [draft, setDraft] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const bodyRef = useRef<HTMLDivElement>(null)

  const {
    sessions,
    agents,
    models,
    providers,
    session,
    entries,
    runs,
    activeRun: currentActiveRun,
    runEvents,
    sessionQuery,
    entriesQuery,
    runEventsQuery,
  } = useAgentSessionQueries(sessionId)
  const createMessageMutation = useAgentSessionMessageMutation(sessionId, () => setDraft(''))
  const agentsById = new Map(agents.map((agent) => [String(agent.id), agent]))
  const timeline = buildSessionTimeline(entries, runEvents)
  const activeRun = hasActiveRun(runs)
  const currentAgent = session ? agentsById.get(session.agentDefinitionId) : undefined
  const runtimeLabels = resolveRuntimeLabels(
    currentAgent,
    timeline,
    models as Array<{ id: string | number; name: string; providerId: string | number; providerName?: string; configJson?: string }>,
    providers,
  )
  const observability = useHarnessSessionObservability(sessionId, currentActiveRun)
  const rootTaskSessionId = session && !session.parentSessionId ? sessionId : ''
  const taskTimeline = useHarnessTaskTimeline(
    rootTaskSessionId,
    sessionQuery.isSuccess && Boolean(rootTaskSessionId),
  )
  const runControls = useHarnessRunControls(sessionId)

  useChatTranscriptAutoScroll(bodyRef, timeline.messages.length, entries.length + runEvents.length)
  useHarnessRunEventStream(
    sessionId,
    currentActiveRun?.runId ?? null,
    Boolean(currentActiveRun) && runEventsQuery.isSuccess,
  )

  function submitMessage() {
    const content = draft.trim()
    if (!content || content.startsWith('/') || activeRun || createMessageMutation.isPending) {
      return
    }
    setActionError(null)
    createMessageMutation.mutate(content, {
      onError: (error) => setActionError(errorMessage(error)),
    })
  }

  function runCommand(command: SessionCommand) {
    setActionError(null)
    switch (command.id) {
      case 'yolo': {
        const enabled = !(observability.yolo?.enabled ?? false)
        observability.setYolo(enabled)
        return
      }
      case 'steer': {
        const content = draft.trim()
        if (!content || !activeRun || runControls.pending) {
          setActionError('Steer 需要运行中且输入区有内容')
          return
        }
        runControls.steer.mutate(content, {
          onSuccess: () => setDraft(''),
          onError: (error) => setActionError(errorMessage(error)),
        })
        return
      }
      case 'follow-up': {
        const content = draft.trim()
        if (!content || runControls.pending) {
          setActionError('Follow-up 需要输入区有内容')
          return
        }
        runControls.followUp.mutate(content, {
          onSuccess: () => setDraft(''),
          onError: (error) => setActionError(errorMessage(error)),
        })
        return
      }
      case 'abort': {
        if (!activeRun || runControls.pending) {
          setActionError('当前没有可终止的运行')
          return
        }
        runControls.abort.mutate(undefined, {
          onError: (error) => setActionError(errorMessage(error)),
        })
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
    sessions,
    agentsById,
    session,
    title: session?.title || 'Chat',
    agent: currentAgent,
    timeline,
    runtimeLabels,
    runs,
    activeRun,
    messagesLoading: sessionQuery.isLoading || entriesQuery.isLoading,
    messagesError: sessionQuery.error || entriesQuery.error || runEventsQuery.error,
    bodyRef,
    draft,
    pending: createMessageMutation.isPending,
    disabled: !session || !session.leafEntryId,
    observability,
    taskTimeline,
    controlsPending: runControls.pending || observability.yoloPending,
    actionError,
    dismissActionError: () => setActionError(null),
    setDraft,
    submitMessage,
    runCommand,
  }
}

function resolveRuntimeLabels(
  agent: ReturnType<typeof useAgentSessionQueries> extends never ? never : any,
  timeline: ReturnType<typeof buildSessionTimeline>,
  models: Array<{ id: string | number; name: string; providerId: string | number; providerName?: string; configJson?: string }>,
  providers: Array<{ id: string | number; name: string }>,
) {
  const agentRecord = (agent ?? {}) as Record<string, unknown>
  const modelId = String(
    agentRecord.defaultModelId
      ?? agentRecord.modelId
      ?? timeline.runtimeContext.model
      ?? '',
  )
  const model = models.find((item) => String(item.id) === modelId)
  const providerId = String(model?.providerId ?? agentRecord.defaultProviderId ?? '')
  const provider = providers.find((item) => String(item.id) === providerId)
  const contextWindow = parseContextWindow(model)

  return {
    agentName: String(agentRecord.name ?? 'agent'),
    providerName: provider?.name || String(agentRecord.defaultProviderName ?? model?.providerName ?? ''),
    modelName: model?.name || String(agentRecord.defaultModelName ?? ''),
    variantName: String(
      timeline.runtimeContext.variant
        ?? agentRecord.variant
        ?? agentRecord.defaultVariant
        ?? 'default',
    ),
    contextWindow,
  }
}

function parseContextWindow(model: { configJson?: string } | undefined): number | undefined {
  if (!model?.configJson) {
    return undefined
  }
  try {
    const config = JSON.parse(model.configJson) as { contextWindow?: number }
    const value = Number(config.contextWindow)
    return Number.isFinite(value) && value > 0 ? value : undefined
  } catch {
    return undefined
  }
}
