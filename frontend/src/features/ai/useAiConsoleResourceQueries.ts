import { useQuery } from '@tanstack/react-query'
import { agentService } from '@/shared/api/agent-service'
import { environmentService } from '@/shared/api/environment-service'
import { queryKeys } from '@/shared/lib/query-keys'
import type {
  AgentModelDTO,
  AgentModelWithProviderDTO,
} from '@/shared/api/contracts'

export interface AiConsoleResourceQueries {
  providersQuery: ReturnType<typeof useQuery>
  modelsQuery: ReturnType<typeof useQuery>
  agentsQuery: ReturnType<typeof useQuery>
  environmentsQuery: ReturnType<typeof useQuery>
  providers: Awaited<ReturnType<typeof agentService.listProviders>>['results']
  models: AgentModelWithProviderDTO[]
  agents: Awaited<ReturnType<typeof agentService.listAgents>>['results']
  environments: Awaited<ReturnType<typeof environmentService.listEnvironments>>
}

export function useAiConsoleResourceQueries(): Omit<AiConsoleResourceQueries, 'models'> & {
  models: AgentModelWithProviderDTO[]
  rawModels: AgentModelDTO[]
} {
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
  const rawModels = modelsQuery.data?.results ?? []
  // Client-side enrichment: join model.providerId with providers[].name to surface a
  // view-only display label without leaking the join field through the backend contract.
  const models = rawModels.map(
    (model): AgentModelWithProviderDTO => {
      const provider = providers.find((item) => String(item.id) === String(model.providerId))
      return {
        ...model,
        providerName: provider?.name ?? null,
      }
    },
  )

  return {
    providersQuery,
    modelsQuery,
    agentsQuery,
    environmentsQuery,
    providers,
    models,
    rawModels,
    agents: agentsQuery.data?.results ?? [],
    environments: environmentsQuery.data ?? [],
  }
}
