import { useQuery } from '@tanstack/react-query'
import { agentService } from '@/shared/api/agent-service'
import { environmentService } from '@/shared/api/environment-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { toAgentModelViews, type AgentModelView } from '@/features/ai/catalog/AgentModelView'

export interface AiConsoleResourceQueries {
  providersQuery: ReturnType<typeof useQuery>
  modelsQuery: ReturnType<typeof useQuery>
  agentsQuery: ReturnType<typeof useQuery>
  toolsQuery: ReturnType<typeof useQuery>
  environmentsQuery: ReturnType<typeof useQuery>
  providers: Awaited<ReturnType<typeof agentService.listProviders>>['results']
  models: AgentModelView[]
  agents: Awaited<ReturnType<typeof agentService.listAgents>>['results']
  toolCatalog: Awaited<ReturnType<typeof agentService.listTools>>
  environments: Awaited<ReturnType<typeof environmentService.listEnvironments>>
}

export interface AiConsoleResourceQueryEnabled {
  providers: boolean
  models: boolean
  agents: boolean
  tools: boolean
  environments: boolean
}

export function useAiConsoleResourceQueries(
  enabled: AiConsoleResourceQueryEnabled,
): AiConsoleResourceQueries {
  const providersQuery = useQuery({
    queryKey: queryKeys.providers.list,
    queryFn: () => agentService.listProviders(),
    enabled: enabled.providers,
  })

  const modelsQuery = useQuery({
    queryKey: queryKeys.models.list,
    queryFn: () => agentService.listModels(),
    enabled: enabled.models,
  })

  const agentsQuery = useQuery({
    queryKey: queryKeys.agents.list,
    queryFn: () => agentService.listAgents(),
    enabled: enabled.agents,
  })

  const toolsQuery = useQuery({
    queryKey: queryKeys.tools.list,
    queryFn: () => agentService.listTools(),
    enabled: enabled.tools,
  })

  const environmentsQuery = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => environmentService.listEnvironments(),
    enabled: enabled.environments,
  })

  return {
    providersQuery,
    modelsQuery,
    agentsQuery,
    toolsQuery,
    environmentsQuery,
    providers: providersQuery.data?.results ?? [],
    models: toAgentModelViews(modelsQuery.data?.results ?? []),
    agents: agentsQuery.data?.results ?? [],
    toolCatalog: toolsQuery.data ?? [],
    environments: environmentsQuery.data ?? [],
  }
}