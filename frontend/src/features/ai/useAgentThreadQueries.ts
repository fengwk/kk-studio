import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  lastEventIdCursor,
  loadThreadEventHistory,
  mergeThreadEventLists,
} from '@/features/ai/harness-thread-event-stream'
import { agentService } from '@/shared/api/agent-service'
import { harnessService } from '@/shared/api/harness-service'
import { toAgentModelViews, type AgentModelView } from '@/features/ai/AgentModelView'
import type { ThreadEventDTO } from '@/shared/api/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

export function useAgentThreadQueries(threadId: string, sessionIdHint = '') {
  const queryClient = useQueryClient()
  const agentsQuery = useQuery({
    queryKey: queryKeys.agents.list,
    queryFn: () => agentService.listAgents(),
  })
  const modelsQuery = useQuery({
    queryKey: queryKeys.models.list,
    queryFn: () => agentService.listModels(),
  })
  const providersQuery = useQuery({
    queryKey: queryKeys.providers.list,
    queryFn: () => agentService.listProviders(),
  })
  const threadQuery = useQuery({
    queryKey: queryKeys.threads.detail(threadId),
    queryFn: () => harnessService.getThread(threadId),
    enabled: Boolean(threadId),
    refetchInterval: (query) => {
      const thread = query.state.data
      return isThreadActive(thread?.status) ? 1200 : false
    },
  })
  const thread = threadQuery.data
  // Pane only stores threadId; session is derived from Thread (hint is optional bootstrap).
  const sessionId = thread?.sessionId || sessionIdHint
  const sessionThreadsQuery = useQuery({
    queryKey: queryKeys.sessions.threads(sessionId),
    queryFn: () => harnessService.listSessionThreads(sessionId),
    enabled: Boolean(sessionId),
  })
  const sessionQuery = useQuery({
    queryKey: queryKeys.sessions.detail(sessionId),
    queryFn: () => harnessService.getSession(sessionId),
    enabled: Boolean(sessionId),
  })
  const inputsQuery = useQuery({
    queryKey: queryKeys.threads.inputs(threadId),
    queryFn: () => harnessService.listThreadInputs(threadId),
    enabled: Boolean(threadId),
    refetchInterval: (query) => {
      const inputs = query.state.data ?? []
      return (thread?.status !== 'FAILED' && inputs.some((input) => input.status === 'QUEUED')) || isThreadActive(thread?.status) ? 1000 : false
    },
  })
  const inputs = inputsQuery.data ?? []
  const workingHint =
    isThreadActive(thread?.status)
    || (thread?.status !== 'FAILED' && inputs.some((input) => input.status === 'QUEUED'))

  const entriesQuery = useQuery({
    queryKey: queryKeys.threads.entries(threadId),
    queryFn: () => harnessService.listThreadEntries(threadId),
    enabled: Boolean(threadId),
    refetchInterval: workingHint ? 1000 : false,
  })

  const eventsQuery = useQuery({
    queryKey: queryKeys.threads.events(threadId),
    queryFn: async () => {
      if (!threadId) {
        return []
      }
      const cached = queryClient.getQueryData<ThreadEventDTO[]>(queryKeys.threads.events(threadId)) ?? []
      // The cache preserves backend journal order, so resume after its last event.
      const afterEventId = lastEventIdCursor(cached)
      const page = await loadThreadEventHistory(
        (cursor, limit) => harnessService.listThreadEvents(threadId, cursor, limit),
        200,
        afterEventId,
      )
      return mergeThreadEventLists(cached, page)
    },
    enabled: Boolean(threadId),
  })
  // Mirror the console join so the thread panel surfaces the same enriched label without leaking
  // the join field through the wire contract.
  const providers = providersQuery.data?.results ?? []
  const models: AgentModelView[] = toAgentModelViews(
    modelsQuery.data?.results ?? [],
    providers,
  )
  return {
    agentsQuery,
    modelsQuery,
    providersQuery,
    sessionThreadsQuery,
    sessionQuery,
    threadQuery,
    entriesQuery,
    inputsQuery,
    eventsQuery,
    agents: agentsQuery.data?.results ?? [],
    models,
    providers,
    session: sessionQuery.data,
    threads: sessionThreadsQuery.data ?? [],
    thread,
    entries: entriesQuery.data ?? [],
    inputs,
    events: eventsQuery.data ?? [],
  }
}

function isThreadActive(status: string | undefined): boolean {
  return status === 'RUNNING' || status === 'WAITING' || status === 'RETRYING'
}