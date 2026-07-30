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
  const snapshotQuery = useQuery({
    queryKey: queryKeys.threads.snapshot(threadId),
    queryFn: () => harnessService.getThreadSnapshot(threadId),
    enabled: Boolean(threadId),
  })
  const snapshot = snapshotQuery.data
  const thread = snapshot?.thread
  const sessionId = thread?.sessionId ?? ''

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
    snapshotQuery,
    agents: agentsQuery.data?.results ?? [],
    models,
    providers,
    session: undefined,
    thread,
    sessionId,
    entries: snapshot?.entries ?? [],
    inputs: snapshot?.inputs ?? [],
    modelInvocations: snapshot?.modelInvocations ?? [],
    toolInvocations: snapshot?.toolInvocations ?? [],
    openInteractions: snapshot?.openInteractions ?? [],
    usage: snapshot?.usage,
  }
}
