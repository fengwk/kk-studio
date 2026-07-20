import { useQuery } from '@tanstack/react-query'
import { agentService } from '@/shared/api/agent-service'
import { environmentService } from '@/shared/api/environment-service'
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

  const environmentsQuery = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => environmentService.listEnvironments(),
  })

  const providers = providersQuery.data?.results ?? []
  const models = (modelsQuery.data?.results ?? []).map((model) => {
    const provider = providers.find((item) => String(item.id) === String(model.providerId))
    return {
      ...model,
      providerName: provider?.name ?? model.providerName ?? null,
    }
  })

  return {
    providersQuery,
    modelsQuery,
    agentsQuery,
    environmentsQuery,
    providers,
    models,
    agents: agentsQuery.data?.results ?? [],
    environments: environmentsQuery.data ?? [],
  }
}
