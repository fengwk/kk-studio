import { useQuery } from '@tanstack/react-query'
import { agentService } from '@/shared/api/agent-service'
import { harnessService } from '@/shared/api/harness-service'
import { toAgentModelViews, type AgentModelView } from '@/features/ai/AgentModelView'
import { queryKeys } from '@/shared/lib/query-keys'

export function useAgentThreadQueries(threadId: string) {
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
  // Pane only stores threadId; the Session is derived from the Thread head and is null when UNBOUND.
  const sessionId = thread?.sessionId ?? ''
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
      return inputs.some((input) => input.status === 'QUEUED') || isThreadActive(thread?.status) ? 1000 : false
    },
  })
  const inputs = inputsQuery.data ?? []
  const workingHint =
    isThreadActive(thread?.status)
    || inputs.some((input) => input.status === 'QUEUED')

  const entriesQuery = useQuery({
    queryKey: queryKeys.threads.entries(threadId),
    queryFn: () => harnessService.listThreadEntries(threadId),
    enabled: Boolean(threadId),
    refetchInterval: workingHint ? 1000 : false,
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
    sessionQuery,
    threadQuery,
    entriesQuery,
    inputsQuery,
    agents: agentsQuery.data?.results ?? [],
    models,
    providers,
    session: sessionQuery.data,
    thread,
    sessionId,
    entries: entriesQuery.data ?? [],
    inputs,
  }
}

/** RUNNING / WAITING / RUNNABLE remain polled until the Thread settles to IDLE. */
export function isThreadActive(status: string | undefined): boolean {
  return status === 'RUNNING' || status === 'WAITING' || status === 'RUNNABLE'
}
