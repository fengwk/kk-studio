import type { EnvironmentDTO } from '@/shared/api/generated/contracts'
import { queryKeys } from '@/shared/lib/query-keys'
import { testRuntimeStatus } from '@/test-support/environment-test-fixtures'

/** Use the actual configuration section with a local, deterministic query result. */
export function environmentRuntimeQueryOptions(environment: EnvironmentDTO) {
  return {
    queryKey: queryKeys.environments.runtime(environment.id),
    queryFn: async () => testRuntimeStatus({ daemonVersion: environment.daemonVersion ?? '1.2.7' }),
    staleTime: Infinity,
  }
}
