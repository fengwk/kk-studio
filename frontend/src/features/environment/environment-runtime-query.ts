import type { EnvironmentDTO } from '@/shared/api/generated/contracts'
import { environmentService } from '@/features/environment/environment-service'
import { queryKeys } from '@/shared/lib/query-keys'

/**
 * 运行进度只存在于 owner 内存，服务端不持久化也不为每条进度广播事件；
 * 因此对当前有租约的卡片按固定周期回读快照，连接状态本身仍由 changed 事件驱动。
 */
export const ENVIRONMENT_RUNTIME_REFRESH_INTERVAL_MS = 5_000

/**
 * CONNECTING 期间已有有效 owner 与配置错误，runtime 端点允许读取；
 * 只有 OFFLINE（没有有效 owner 租约）才不发出请求。
 */
export function environmentRuntimeQueryOptions(environment: EnvironmentDTO) {
  return {
    queryKey: queryKeys.environments.runtime(environment.id),
    queryFn: () => environmentService.getEnvironmentRuntime(environment.id),
    enabled: environment.status !== 'OFFLINE',
    refetchInterval: ENVIRONMENT_RUNTIME_REFRESH_INTERVAL_MS,
  }
}
