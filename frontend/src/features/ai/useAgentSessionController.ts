import { useRef, useState } from 'react'
import { buildSessionTimeline, hasActiveRun } from '@/features/ai/session-events'
import { useAgentSessionEventStream } from '@/features/ai/useAgentSessionEventStream'
import { useAgentSessionMessageMutation } from '@/features/ai/useAgentSessionMessageMutation'
import { useAgentSessionQueries } from '@/features/ai/useAgentSessionQueries'
import { useChatTranscriptAutoScroll } from '@/features/ai/useChatTranscriptAutoScroll'

export function useAgentSessionController(sessionId: string) {
  const [draft, setDraft] = useState('')
  const bodyRef = useRef<HTMLDivElement>(null)

  const { sessions, agents, session, events, runs, sessionQuery, eventsQuery } = useAgentSessionQueries(sessionId)
  const createMessageMutation = useAgentSessionMessageMutation(sessionId, () => setDraft(''))
  const agentsByName = new Map(agents.map((agent) => [agent.name, agent]))
  const timeline = buildSessionTimeline(events)
  const activeRun = hasActiveRun(runs)
  const currentAgent = session ? agentsByName.get(session.agentName) : undefined

  useChatTranscriptAutoScroll(bodyRef, timeline.messages.length, events.length)
  useAgentSessionEventStream(sessionId, eventsQuery.isSuccess)

  function submitMessage() {
    const content = draft.trim()
    if (!content || createMessageMutation.isPending) {
      return
    }
    createMessageMutation.mutate(content)
  }

  return {
    sessions,
    agentsByName,
    session,
    title: session?.title || 'Chat',
    agent: currentAgent,
    timeline,
    runs,
    activeRun,
    messagesLoading: sessionQuery.isLoading || eventsQuery.isLoading,
    messagesError: sessionQuery.error || eventsQuery.error,
    bodyRef,
    draft,
    pending: createMessageMutation.isPending,
    disabled: !session,
    setDraft,
    submitMessage,
  }
}
