import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { agentService } from '@/shared/api/agent-service'
import { queryKeys } from '@/shared/lib/query-keys'

/**
 * 递归/分页拉取全部 Catalog Agent 名称列表，支持 >50 个 Agent 的完整枚举。
 * 针对拉取失败进行防御性捕获，避免抛出或生成非法幻影值。
 */
export async function fetchAllCatalogAgentNames(): Promise<string[]> {
  const pageSize = 50
  let pageNumber = 1
  const names: string[] = []

  try {
    while (true) {
      const page = await agentService.listAgents(pageNumber, pageSize)
      const list = page?.results ?? []
      for (const item of list) {
        if (item?.name && typeof item.name === 'string') {
          names.push(item.name.trim())
        }
      }
      const totalCount = Number(page?.totalCount ?? 0)
      if (
        list.length < pageSize ||
        (totalCount > 0 && names.length >= totalCount) ||
        list.length === 0
      ) {
        break
      }
      pageNumber += 1
    }
  } catch {
    // 网络或接口失败时防御性降级为空，避免产生虚假错误值
    return names.length > 0
      ? Array.from(new Set(names)).sort((a, b) => a.localeCompare(b))
      : []
  }

  return Array.from(new Set(names)).sort((a, b) => a.localeCompare(b))
}

export interface UseCatalogAgentNamesOptions {
  /**
   * 需透明保全的当前已分配 Agent 名称列表（如已下线但仍分配在单据上的历史 Agent）。
   * 即使不再存在于 catalog 中，也将保留在选项中，防止表单静默覆盖。
   */
  preserveNames?: (string | null | undefined)[]
  enabled?: boolean
}

export function useCatalogAgentNames(options: UseCatalogAgentNamesOptions = {}) {
  const { preserveNames = [], enabled = true } = options

  const {
    data: catalogAgents,
    isLoading,
    isError,
  } = useQuery({
    queryKey: [...queryKeys.agents.list, 'all-names'] as const,
    queryFn: fetchAllCatalogAgentNames,
    staleTime: 30_000,
    enabled,
  })

  const catalogNames = useMemo(() => catalogAgents ?? [], [catalogAgents])

  const agentOptions = useMemo(() => {
    const set = new Set<string>(catalogNames)
    for (const name of preserveNames) {
      if (name && typeof name === 'string' && name.trim()) {
        set.add(name.trim())
      }
    }
    return Array.from(set).sort((a, b) => a.localeCompare(b))
  }, [catalogNames, preserveNames])

  return {
    catalogNames,
    agentOptions,
    isLoading,
    isError,
  }
}
