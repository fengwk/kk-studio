import type { QueryClient } from '@tanstack/react-query'
import { queryKeys } from '@/shared/lib/query-keys'
import type { ProjectsChangedEventPayload } from './types'

/**
 * 失效 Project 相关的 TanStack Query 缓存。
 * - 当携带 projectId 时：精准失效项目列表、该项目的 detail 与 snapshot、以及该项目下的所有 issue。
 * - 当未携带 projectId 时（如 subscribed / resync / error）：失效 projects 全量查询族。
 */
export async function invalidateProjectQueries(
  queryClient: QueryClient,
  payload?: ProjectsChangedEventPayload,
): Promise<void> {
  if (payload?.projectId) {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.projects.lists() }),
      queryClient.invalidateQueries({ queryKey: queryKeys.projects.detail(payload.projectId) }),
      queryClient.invalidateQueries({ queryKey: queryKeys.projects.snapshot(payload.projectId) }),
      queryClient.invalidateQueries({ queryKey: queryKeys.projects.issues(payload.projectId) }),
    ])
  } else {
    await queryClient.invalidateQueries({ queryKey: queryKeys.projects.all })
  }
}
