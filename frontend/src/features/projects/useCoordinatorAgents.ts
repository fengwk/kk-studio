import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { agentService } from '@/shared/api/agent-service'
import { queryKeys } from '@/shared/lib/query-keys'
import type { AgentDefinitionDTO } from '@/shared/api/contracts/ai-catalog'
import type { SelectOption } from '@/shared/ui/console/Select'

export function useCoordinatorAgents(currentCoordinatorName?: string, enabled = true) {
  const query = useQuery({
    queryKey: queryKeys.agents.list,
    queryFn: () => agentService.listAgents(),
    enabled,
  })

  const agents: AgentDefinitionDTO[] = useMemo(() => {
    const raw = query.data?.results ?? []
    const seen = new Set<string>()
    const deduped: AgentDefinitionDTO[] = []
    for (const item of raw) {
      if (item.name && !seen.has(item.name)) {
        seen.add(item.name)
        deduped.push(item)
      }
    }
    return deduped.sort((a, b) => a.name.localeCompare(b.name))
  }, [query.data])

  const options: SelectOption[] = useMemo(() => {
    const list: SelectOption[] = agents.map((agent) => ({
      value: agent.name,
      label: agent.name,
    }))

    // 防御性孤儿 Agent：当传入已有 coordinator 且该 agent 不在当前返回列表中时，
    // 以 disabled "(不可用)" 形式保留在选项顶部，防止表单显示空白；
    // 但禁止提交此项（通过 isCoordinatorValid 校验）。
    if (
      currentCoordinatorName &&
      !agents.some((agent) => agent.name === currentCoordinatorName)
    ) {
      list.unshift({
        value: currentCoordinatorName,
        label: `${currentCoordinatorName} (不可用)`,
        disabled: true,
      })
    }

    return list
  }, [agents, currentCoordinatorName])

  const isCoordinatorValid = (name: string): boolean =>
    Boolean(name.trim()) && agents.some((agent) => agent.name === name.trim())

  return {
    agents,
    options,
    isLoading: query.isLoading,
    isError: query.isError,
    error: query.error,
    isCoordinatorValid,
    refetch: query.refetch,
  }
}
