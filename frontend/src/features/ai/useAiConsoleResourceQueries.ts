import { useQuery } from '@tanstack/react-query'
import { agentService } from '@/shared/api/agent-service'
import { queryKeys } from '@/shared/lib/query-keys'

export function useAiConsoleResourceQueries() {
  const providersQuery = useQuery({
    queryKey: queryKeys.providers.list,
    queryFn: () => agentService.listProviders(),
  })

  const modelsQuery = useQuery({
    queryKey: queryKeys.models.list,
    queryFn: () => agentService.listModels(),
  })

  const agentsQuery = useQuery({
    queryKey: queryKeys.agents.list,
    queryFn: () => agentService.listAgents(),
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
