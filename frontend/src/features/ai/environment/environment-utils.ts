import { includesSearch, naturalNameCompare } from '@/shared/lib/search-utils'
import type { EnvironmentCardDTO } from '@/shared/api/contracts/ai-environment'

export function filterEnvironments(
  environments: EnvironmentCardDTO[],
  search: string,
): EnvironmentCardDTO[] {
  return environments
    .filter((environment) => includesSearch(environment.name, search) || includesSearch(environment.id, search))
    .sort((left, right) => naturalNameCompare(left.name, right.name))
}

/** 统一可用性规则（服务端 ready 标记：READY + 连接打开 + 心跳未过期）；过期/未连接一律不可选。 */
export function filterReadyEnvironments(environments: EnvironmentCardDTO[]): EnvironmentCardDTO[] {
  return environments.filter((environment) => environment.ready)
}
