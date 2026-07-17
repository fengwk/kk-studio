import { useRef, useState } from 'react'
import { buildSessionTimeline, hasActiveRun } from '@/features/ai/session-events'
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
    if (!content || activeRun || createMessageMutation.isPending) {
      return
    }
    setActionError(null)
    createMessageMutation.mutate(content, {
      onError: (error) => {
        // Keep draft so the user can retry; surface a pi-style error panel.
        setActionError(errorMessage(error))
      },
    })
  }

  function submitSteer() {
    const content = draft.trim()
    if (!content || !activeRun || runControls.pending) {
      return
    }
    setActionError(null)
    runControls.steer.mutate(content, {
      onSuccess: () => setDraft(''),
      onError: (error) => setActionError(errorMessage(error)),
    })
  }

  function submitFollowUp() {
    const content = draft.trim()
    if (!content || runControls.pending) {
      return
    }
    setActionError(null)
    runControls.followUp.mutate(content, {
      onSuccess: () => setDraft(''),
      onError: (error) => setActionError(errorMessage(error)),
    })
  }

  function abortRun() {
    if (!activeRun || runControls.pending) {
      return
    }
    setActionError(null)
    runControls.abort.mutate(undefined, {
      onError: (error) => setActionError(errorMessage(error)),
    })
  }

  return {
    sessions,
    agentsById,
    session,
    title: session?.title || 'Chat',
    agent: currentAgent,
    timeline,
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
    controlsPending: runControls.pending,
    actionError,
    dismissActionError: () => setActionError(null),
    setDraft,
    submitMessage,
    submitSteer,
    submitFollowUp,
    abortRun,
  }
}
