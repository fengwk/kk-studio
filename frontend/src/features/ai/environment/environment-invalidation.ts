import type { QueryClient } from '@tanstack/react-query'
import { queryKeys } from '@/shared/lib/query-keys'

export interface InvalidateEnvironmentOptions {
  list?: boolean
}

/**
 * 统一环境相关资源缓存失效工具函数
 */
export function invalidateEnvironmentArtifacts(
  queryClient: QueryClient,
  _environmentId?: string,
  options?: InvalidateEnvironmentOptions,
): Promise<void[]> {
  const promises: Promise<void>[] = []

  if (!options || options.list) {
    promises.push(
      queryClient.invalidateQueries({
        queryKey: queryKeys.environments.list,
        exact: true,
      }),
    )
  }

  return Promise.all(promises)
}
