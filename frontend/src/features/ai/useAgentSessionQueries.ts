import { useQuery, useQueryClient } from '@tanstack/react-query'
import { hasActiveRun } from '@/features/ai/session-events'
import { loadMergedSessionEvents } from '@/features/ai/agent-session-query-support'
import { agentService, createWorkspaceSessionApi } from '@/shared/api/agent-service'
import type { AgentRunDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

export function useAgentSessionQueries(workspaceId: string, sessionId: string) {
  const queryClient = useQueryClient()
  const sessionApi = createWorkspaceSessionApi(workspaceId)
  const agentsQuery = useQuery({
    queryKey: queryKeys.agents.list(workspaceId),
    queryFn: () => agentService.listAgents(workspaceId),
  })
  const sessionsQuery = useQuery({
    queryKey: queryKeys.sessions.list(workspaceId),
    queryFn: sessionApi.list,
  })
  const sessionQuery = useQuery({
    queryKey: queryKeys.sessions.detail(workspaceId, sessionId),
    queryFn: () => sessionApi.get(sessionId),
    enabled: Boolean(sessionId),
  })
  const eventsQuery = useQuery({
    queryKey: queryKeys.sessions.events(workspaceId, sessionId),
    queryFn: () => loadMergedSessionEvents(queryClient, workspaceId, sessionId),
    enabled: Boolean(sessionId),
  })
  const runsQuery = useQuery({
    queryKey: queryKeys.sessions.runs(workspaceId, sessionId),
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
