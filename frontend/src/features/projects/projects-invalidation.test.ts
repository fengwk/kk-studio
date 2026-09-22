import { describe, expect, it, vi } from 'vitest'
import { QueryClient } from '@tanstack/react-query'
import { queryKeys } from '@/shared/lib/query-keys'
import { invalidateProjectQueries } from './projects-invalidation'

describe('invalidateProjectQueries', () => {
  it('invalidates targeted project lists, detail, snapshot, and issues when projectId is provided', async () => {
    // 测试意图：验证单一 Query 失效机制，在接收到携带 projectId 的变更时精准失效 lists、detail、snapshot 与该项目的 issues
    const queryClient = new QueryClient()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    await invalidateProjectQueries(queryClient, { projectId: 'proj-1' })

    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: queryKeys.projects.lists(),
    })
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: queryKeys.projects.detail('proj-1'),
    })
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: queryKeys.projects.snapshot('proj-1'),
    })
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: queryKeys.projects.issues('proj-1'),
    })
    expect(invalidateSpy).toHaveBeenCalledTimes(4)
  })

  it('invalidates all project queries when no projectId is provided', async () => {
    // 测试意图：验证未提供 projectId 时（如 subscribed / resync / error）失效整个 projects 查询族
    const queryClient = new QueryClient()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    await invalidateProjectQueries(queryClient)

    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: queryKeys.projects.all,
    })
    expect(invalidateSpy).toHaveBeenCalledTimes(1)
  })

  it('does not refetch active issue queries belonging to a different project on targeted invalidation', async () => {
    // 测试意图：验证携带 projectId 的精准失效仅触发目标项目的 issue query 重新请求，不触发无关项目的 issue query
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })

    const fetchProj1Issue = vi.fn().mockResolvedValue({ id: 'issue-1', title: 'Issue 1' })
    const fetchProj2Issue = vi.fn().mockResolvedValue({ id: 'issue-2', title: 'Issue 2' })

    // 预热两个项目的 issue query
    await queryClient.prefetchQuery({
      queryKey: queryKeys.projects.issue('proj-1', 'issue-1'),
      queryFn: fetchProj1Issue,
    })
    await queryClient.prefetchQuery({
      queryKey: queryKeys.projects.issue('proj-2', 'issue-2'),
      queryFn: fetchProj2Issue,
    })

    expect(fetchProj1Issue).toHaveBeenCalledTimes(1)
    expect(fetchProj2Issue).toHaveBeenCalledTimes(1)

    // 针对 proj-1 发起精准失效
    await invalidateProjectQueries(queryClient, { projectId: 'proj-1' })

    const stateProj1 = queryClient.getQueryState(queryKeys.projects.issue('proj-1', 'issue-1'))
    const stateProj2 = queryClient.getQueryState(queryKeys.projects.issue('proj-2', 'issue-2'))

    // proj-1 的 issue query 应被标记为失效（isInvalidated = true）
    expect(stateProj1?.isInvalidated).toBe(true)
    // proj-2 的 issue query 不受影响（isInvalidated = false）
    expect(stateProj2?.isInvalidated).toBe(false)
  })
})
