import { useQuery } from '@tanstack/react-query'
import { agentService } from '@/shared/api/agent-service'
import { harnessService } from '@/shared/api/harness-service'
import { toAgentModelViews, type AgentModelView } from '@/features/ai/catalog'
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
  const snapshotQuery = useQuery({
    queryKey: queryKeys.threads.snapshot(threadId),
    queryFn: () => harnessService.getThreadSnapshot(threadId),
    enabled: Boolean(threadId),
  })
  const snapshot = snapshotQuery.data
  const thread = snapshot?.thread
  const sessionId = thread?.sessionId ?? ''

  const models: AgentModelView[] = toAgentModelViews(modelsQuery.data?.results ?? [])
  return {
    agentsQuery,
    modelsQuery,
    snapshotQuery,
    agents: agentsQuery.data?.results ?? [],
    models,
    thread,
    sessionId,
    entries: snapshot?.entries ?? [],
    inputs: snapshot?.inputs ?? [],
    modelInvocations: snapshot?.modelInvocations ?? [],
    toolInvocations: snapshot?.toolInvocations ?? [],
    usage: snapshot?.usage,
  }
}
