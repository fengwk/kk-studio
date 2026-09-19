import type { QueryClient } from '@tanstack/react-query'
import { queryKeys } from '@/shared/lib/query-keys'
import { DEFAULT_OPERATION_LIMIT } from '@/shared/api/environment-service'

export interface InvalidateEnvironmentOptions {
  operations?: boolean
  list?: boolean
}

/**
 * 统一环境相关资源缓存失效工具函数
 */
export function invalidateEnvironmentArtifacts(
  queryClient: QueryClient,
  environmentId: string,
  options: InvalidateEnvironmentOptions,
): Promise<void[]> {
  const promises: Promise<void>[] = []

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
