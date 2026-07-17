import { useQuery, useQueryClient } from '@tanstack/react-query'
import { hasActiveRun } from '@/features/ai/session-events'
import { mergeRunEventLists } from '@/features/ai/harness-run-event-stream'
import { agentService } from '@/shared/api/agent-service'
import { harnessService } from '@/shared/api/harness-service'
import type { HarnessRunDTO, RunEventDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

export function useAgentSessionQueries(sessionId: string) {
  const queryClient = useQueryClient()
  const agentsQuery = useQuery({
    queryKey: queryKeys.agents.list,
    queryFn: () => agentService.listAgents(),
  })
  const sessionsQuery = useQuery({
    queryKey: queryKeys.sessions.list,
    queryFn: () => harnessService.listSessions(),
  })
  const sessionQuery = useQuery({
    queryKey: queryKeys.sessions.detail(sessionId),
    queryFn: () => harnessService.getSession(sessionId),
    enabled: Boolean(sessionId),
  })
  const entriesQuery = useQuery({
    queryKey: queryKeys.sessions.entries(sessionId),
    queryFn: () => harnessService.listEntries(sessionId),
    enabled: Boolean(sessionId),
  })
  const runsQuery = useQuery({
    queryKey: queryKeys.sessions.runs(sessionId),
    queryFn: () => harnessService.listRuns(sessionId),
    enabled: Boolean(sessionId),
    refetchInterval: (query) => (hasActiveRun((query.state.data as HarnessRunDTO[] | undefined) ?? []) ? 1200 : false),
  })
  const runs = runsQuery.data ?? []
  const activeRun = runs.find((run) => ['QUEUED', 'RUNNING', 'WAITING_TOOLS'].includes(run.status))
  const runEventsQuery = useQuery({
    queryKey: queryKeys.runs.events(activeRun?.runId ?? ''),
    queryFn: async () => {
      const runId = activeRun?.runId
      if (!runId) {
        return []
      }
      const snapshot = await harnessService.listRunEvents(runId)
      const cached = queryClient.getQueryData<RunEventDTO[]>(queryKeys.runs.events(runId)) ?? []
      return mergeRunEventLists(snapshot, cached)
    },
    enabled: Boolean(activeRun),
  })

  return {
    agentsQuery,
    sessionsQuery,
    sessionQuery,
    entriesQuery,
    runsQuery,
    runEventsQuery,
    agents: agentsQuery.data?.results ?? [],
    sessions: sessionsQuery.data ?? [],
    session: sessionQuery.data,
    entries: entriesQuery.data ?? [],
    runs,
    activeRun,
    runEvents: runEventsQuery.data ?? [],
  }
}
