import { useRef, useState } from 'react'
import { buildSessionTimeline, hasActiveRun } from '@/features/ai/session-events'
import { useAgentSessionMessageMutation } from '@/features/ai/useAgentSessionMessageMutation'
import { useAgentSessionQueries } from '@/features/ai/useAgentSessionQueries'
import { useChatTranscriptAutoScroll } from '@/features/ai/useChatTranscriptAutoScroll'
import { useHarnessRunEventStream } from '@/features/ai/useHarnessRunEventStream'
import { useHarnessRunControls } from '@/features/ai/useHarnessRunControls'
import { useHarnessSessionObservability } from '@/features/ai/useHarnessSessionObservability'
import { useHarnessTaskTimeline } from '@/features/ai/useHarnessTaskTimeline'

export function useAgentSessionController(sessionId: string) {
  const [draft, setDraft] = useState('')
  const bodyRef = useRef<HTMLDivElement>(null)

  const { sessions, agents, session, entries, runs, activeRun: currentRun, runEvents, sessionQuery, entriesQuery, runEventsQuery } = useAgentSessionQueries(sessionId)
  const createMessageMutation = useAgentSessionMessageMutation(sessionId, session?.leafEntryId ?? null, () => setDraft(''))
  const agentsById = new Map(agents.map((agent) => [String(agent.id), agent]))
  const timeline = buildSessionTimeline(entries, runEvents)
  const activeRun = hasActiveRun(runs)
  const currentAgent = session ? agentsById.get(session.agentDefinitionId) : undefined
  const observability = useHarnessSessionObservability(sessionId, currentRun)
  const rootTaskSessionId = session && !session.parentSessionId ? sessionId : ''
  const taskTimeline = useHarnessTaskTimeline(
    rootTaskSessionId,
    sessionQuery.isSuccess && Boolean(rootTaskSessionId),
  )
  const runControls = useHarnessRunControls(sessionId)

  useChatTranscriptAutoScroll(bodyRef, timeline.messages.length, entries.length + runEvents.length)
  useHarnessRunEventStream(sessionId, currentRun?.runId ?? null, runEventsQuery.isSuccess)

  function submitMessage() {
    const content = draft.trim()
    if (!content || activeRun || createMessageMutation.isPending) {
      return
    }
    createMessageMutation.mutate(content)
  }

  function submitSteer() {
    const content = draft.trim()
    if (!content || !activeRun || runControls.pending) {
      return
    }
    runControls.steer.mutate(content, { onSuccess: () => setDraft('') })
  }

  function submitFollowUp() {
    const content = draft.trim()
    if (!content || runControls.pending) {
      return
    }
    runControls.followUp.mutate(content, { onSuccess: () => setDraft('') })
  }

  function abortRun() {
    if (!activeRun || runControls.pending) {
      return
    }
    runControls.abort.mutate()
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
    messagesError: sessionQuery.error || entriesQuery.error || runEventsQuery.error || runControls.error,
    bodyRef,
    draft,
    pending: createMessageMutation.isPending,
    disabled: !session || !session.leafEntryId,
    observability,
    taskTimeline,
    controlsPending: runControls.pending,
    setDraft,
    submitMessage,
    submitSteer,
    submitFollowUp,
    abortRun,
  }
}
