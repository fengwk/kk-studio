import { useQuery, useQueryClient } from '@tanstack/react-query'
import { hasActiveRun } from '@/features/ai/session-events'
import { loadMergedSessionEvents } from '@/features/ai/agent-session-query-support'
import { agentService, createSessionApi } from '@/shared/api/agent-service'
import type { AgentRunDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

export function useAgentSessionQueries(sessionId: string) {
  const queryClient = useQueryClient()
  const sessionApi = createSessionApi()
  const agentsQuery = useQuery({
    queryKey: queryKeys.agents.list,
    queryFn: () => agentService.listAgents(),
  })
  const sessionsQuery = useQuery({
    queryKey: queryKeys.sessions.list,
    queryFn: sessionApi.list,
  })
  const sessionQuery = useQuery({
    queryKey: queryKeys.sessions.detail(sessionId),
    queryFn: () => sessionApi.get(sessionId),
    enabled: Boolean(sessionId),
  })
  const eventsQuery = useQuery({
    queryKey: queryKeys.sessions.events(sessionId),
    queryFn: () => loadMergedSessionEvents(queryClient, sessionId),
    enabled: Boolean(sessionId),
  })
  const runsQuery = useQuery({
    queryKey: queryKeys.sessions.runs(sessionId),
    queryFn: () => sessionApi.listRuns(sessionId),
    enabled: Boolean(sessionId),
    refetchInterval: (query) => (hasActiveRun((query.state.data as AgentRunDTO[] | undefined) ?? []) ? 1200 : false),
  })

  return {
    agentsQuery,
    sessionsQuery,
    sessionQuery,
    eventsQuery,
    runsQuery,
    agents: agentsQuery.data?.results ?? [],
    sessions: sessionsQuery.data?.results ?? [],
    session: sessionQuery.data,
    events: eventsQuery.data ?? [],
    runs: runsQuery.data ?? [],
  }
}
