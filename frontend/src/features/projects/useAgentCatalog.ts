import { useQuery } from '@tanstack/react-query'
import { agentService } from '@/shared/api/agent-service'
import type { AgentDTO } from '@/shared/api/generated/contracts'
import { queryKeys } from '@/shared/lib/query-keys'

/** 目录中的 Agent 条目：名称是标识，描述是选择列表的次行，均来自同一次目录读取。 */
export interface CatalogAgent {
  name: string
  description: string | null
}

/**
 * Agent 目录读取结果。
 *
 * `ready` 只在目录成功读取后为真。读取中或读取失败时目录内容未知，
 * 不得据此认定已保存的 Agent 不可用，也不得改写已保存的名称。
 */
export interface AgentCatalog {
  agents: readonly CatalogAgent[]
  loading: boolean
  error: boolean
  ready: boolean
}

function sortCatalog(agents: AgentDTO[]): CatalogAgent[] {
  const catalog = new Map<string, CatalogAgent>()
  for (const agent of agents ?? []) {
    const name = agent?.name?.trim()
    if (!name || catalog.has(name)) {
      continue
    }
    const description = agent.description?.trim()
    catalog.set(name, { name, description: description ? description : null })
  }
  return Array.from(catalog.values()).sort((left, right) => left.name.localeCompare(right.name))
}

export function useAgentCatalog(): AgentCatalog {
  const { data, isLoading, isError, isSuccess } = useQuery({
    queryKey: queryKeys.agents.list,
    queryFn: () => agentService.listAgents(),
    select: sortCatalog,
    staleTime: 30_000,
  })

  return {
    agents: data ?? [],
    loading: isLoading,
    error: isError,
    ready: isSuccess,
  }
}
