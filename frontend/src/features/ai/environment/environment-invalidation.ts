import type { QueryClient } from '@tanstack/react-query'
import { queryKeys } from '@/shared/lib/query-keys'
import { DEFAULT_OPERATION_LIMIT } from '@/shared/api/environment-service'

export interface InvalidateEnvironmentOptions {
  sources?: boolean
  inventoryHeader?: boolean
  inventorySkills?: boolean
  operations?: boolean
  list?: boolean
}

/**
 * 统一环境相关资源缓存失效工具函数：
 * - 来源 create/delete：失效 sources + inventory header + both inventory skill keys (usableOnly: true/false)
 * - 来源 update：失效 sources + both inventory skill keys (usableOnly: true/false)
 * - 终态转换：失效 sources + inventory header + both inventory skill keys + list
 * - 冲突刷新：失效 sources + inventory header + both inventory skill keys + operations + list
 */
export function invalidateEnvironmentArtifacts(
  queryClient: QueryClient,
  environmentId: string,
  options: InvalidateEnvironmentOptions,
): Promise<void[]> {
  const promises: Promise<void>[] = []

  if (options.sources) {
    promises.push(
      queryClient.invalidateQueries({
        queryKey: queryKeys.environments.skillSources(environmentId),
        exact: true,
      }),
    )
  }

  if (options.inventoryHeader) {
    promises.push(
      queryClient.invalidateQueries({
        queryKey: queryKeys.environments.inventory(environmentId),
        exact: true,
      }),
    )
  }

  if (options.inventorySkills) {
    promises.push(
      queryClient.invalidateQueries({
        queryKey: queryKeys.environments.inventorySkills(environmentId, true),
        exact: true,
      }),
    )
    promises.push(
      queryClient.invalidateQueries({
        queryKey: queryKeys.environments.inventorySkills(environmentId, false),
        exact: true,
      }),
    )
  }

  if (options.operations) {
    promises.push(
      queryClient.invalidateQueries({
        queryKey: queryKeys.environments.operations(environmentId, DEFAULT_OPERATION_LIMIT),
        exact: true,
      }),
    )
  }

  if (options.list) {
    promises.push(
      queryClient.invalidateQueries({
        queryKey: queryKeys.environments.list,
        exact: true,
      }),
    )
  }

  return Promise.all(promises)
}
