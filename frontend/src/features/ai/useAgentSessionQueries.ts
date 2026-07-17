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
  // Always materialize events for the latest run (including terminal failed details). SSE stays
  // gated on an active run in the controller.
  const latestRun = runs.at(-1)
  const activeRun = runs.find((run) => ['QUEUED', 'RUNNING', 'WAITING_TOOLS'].includes(run.status))
  const eventRunId = latestRun?.runId ?? ''
  const runEventsQuery = useQuery({
    queryKey: queryKeys.runs.events(eventRunId),
    queryFn: async () => {
      if (!eventRunId) {
        return []
      }
      const snapshot = await harnessService.listRunEvents(eventRunId)
      const cached = queryClient.getQueryData<RunEventDTO[]>(queryKeys.runs.events(eventRunId)) ?? []
      return mergeRunEventLists(snapshot, cached)
    },
    enabled: Boolean(eventRunId),
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
    latestRun,
    activeRun,
    runEvents: runEventsQuery.data ?? [],
  }
}
