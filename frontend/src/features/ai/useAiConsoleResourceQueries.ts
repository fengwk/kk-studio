import { useQuery } from '@tanstack/react-query'
import { agentService } from '@/shared/api/agent-service'
import { queryKeys } from '@/shared/lib/query-keys'

export function useAiConsoleResourceQueries(workspaceId: string) {
  const providersQuery = useQuery({
    queryKey: queryKeys.providers.list(workspaceId),
    queryFn: () => agentService.listProviders(workspaceId),
  })

  const modelsQuery = useQuery({
    queryKey: queryKeys.models.list(workspaceId),
    queryFn: () => agentService.listModels(workspaceId),
  })

  const agentsQuery = useQuery({
    queryKey: queryKeys.agents.list(workspaceId),
    queryFn: () => agentService.listAgents(workspaceId),
  })

  return {
    providersQuery,
    modelsQuery,
    agentsQuery,
    providers: providersQuery.data?.results ?? [],
    models: modelsQuery.data?.results ?? [],
    agents: agentsQuery.data?.results ?? [],
  }
}
