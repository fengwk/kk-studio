import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState, type RefObject } from 'react'
import { buildSessionTimeline, hasActiveRun } from '@/features/ai/session-events'
import { mergeSessionEvent, mergeSessionEventLists, parseSessionEvent } from '@/features/ai/session-event-stream'
import { agentService } from '@/shared/api/agent-service'
import type { AgentRunDTO, AgentSessionEventDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

export function useAgentSessionController(sessionId: string) {
  const queryClient = useQueryClient()
  const [draft, setDraft] = useState('')
  const bodyRef = useRef<HTMLDivElement>(null)

  const agentsQuery = useQuery({
    queryKey: queryKeys.agents.list,
    queryFn: () => agentService.listAgents(),
  })

  const sessionsQuery = useQuery({
    queryKey: queryKeys.sessions.list,
    queryFn: () => agentService.listSessions(),
  })

  const sessionQuery = useQuery({
    queryKey: queryKeys.sessions.detail(sessionId),
    queryFn: () => agentService.getSession(sessionId),
    enabled: Boolean(sessionId),
  })

  const eventsQuery = useQuery({
    queryKey: queryKeys.sessions.events(sessionId),
    queryFn: async () => {
      const snapshot = await agentService.listEvents(sessionId)
      const cachedEvents = queryClient.getQueryData<AgentSessionEventDTO[]>(queryKeys.sessions.events(sessionId)) ?? []
      return mergeSessionEventLists(snapshot, cachedEvents)
    },
    enabled: Boolean(sessionId),
  })

  const runsQuery = useQuery({
    queryKey: queryKeys.sessions.runs(sessionId),
    queryFn: () => agentService.listRuns(sessionId),
    enabled: Boolean(sessionId),
    refetchInterval: (query) => (hasActiveRun((query.state.data as AgentRunDTO[] | undefined) ?? []) ? 1200 : false),
  })

  const createMessageMutation = useMutation({
    mutationFn: (content: string) => agentService.createMessage(sessionId, { content }),
    onSuccess: async () => {
      setDraft('')
      await invalidateSessionQueries(queryClient, sessionId)
    },
  })

  const sessions = sessionsQuery.data?.results ?? []
  const agents = agentsQuery.data?.results ?? []
  const agentsByName = new Map(agents.map((agent) => [agent.name, agent]))
  const session = sessionQuery.data
  const timeline = buildSessionTimeline(eventsQuery.data ?? [])
  const runs = runsQuery.data ?? []
  const activeRun = hasActiveRun(runs)
  const currentAgent = session ? agentsByName.get(session.agentName) : undefined

  useChatTranscriptAutoScroll(bodyRef, timeline.messages.length, eventsQuery.data?.length)
  useSessionEventStream(queryClient, sessionId, eventsQuery.isSuccess)

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

async function invalidateSessionQueries(queryClient: QueryClient, sessionId: string) {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: queryKeys.sessions.detail(sessionId) }),
    queryClient.invalidateQueries({ queryKey: queryKeys.sessions.events(sessionId) }),
    queryClient.invalidateQueries({ queryKey: queryKeys.sessions.runs(sessionId) }),
    queryClient.invalidateQueries({ queryKey: queryKeys.sessions.list }),
  ])
}

function useChatTranscriptAutoScroll(bodyRef: RefObject<HTMLDivElement | null>, messageCount: number, eventCount?: number) {
  useEffect(() => {
    const chatBody = bodyRef.current
    if (chatBody) {
      chatBody.scrollTop = chatBody.scrollHeight
    }
  }, [bodyRef, eventCount, messageCount])
}

function useSessionEventStream(queryClient: QueryClient, sessionId: string, enabled: boolean) {
  useEffect(() => {
    if (!sessionId || !enabled) {
      return undefined
    }

    const eventSource = agentService.createEventStream(sessionId)
    const handleSessionEvent = (event: MessageEvent<string>) => {
      const sessionEvent = parseSessionEvent(event.data)
      if (!sessionEvent) {
        return
      }
      queryClient.setQueryData<AgentSessionEventDTO[]>(queryKeys.sessions.events(sessionId), (events = []) => mergeSessionEvent(events, sessionEvent))
    }

    eventSource.addEventListener('session_event', handleSessionEvent as EventListener)
    return () => {
      eventSource.close()
    }
  }, [enabled, queryClient, sessionId])
}
