import { useQuery } from '@tanstack/react-query'
import { environmentService } from '@/shared/api/environment-service'
import type { EnvironmentBindingDTO } from '@/shared/api/contracts/ai-environment'
import { queryKeys } from '@/shared/lib/query-keys'

/** 复用目录查询缓存读取当前 Workspace 的可选 Git 分支事实。 */
export function useEnvironmentWorkspaceMetadata(
  binding: EnvironmentBindingDTO | null,
  ready: boolean | undefined,
) {
  const query = useQuery({
    queryKey: queryKeys.environments.directory(
      binding?.name ?? '',
      binding?.workspacePath ?? '.',
    ),
    queryFn: () => environmentService.listDirectories(binding!.name, binding!.workspacePath),
    enabled: binding != null && ready !== false,
    retry: false,
    staleTime: 30_000,
  })
  const data =
    binding != null && query.data?.path === binding.workspacePath
      ? query.data
      : undefined
  return {
    gitBranch: data?.gitBranch ?? null,
  }
}
