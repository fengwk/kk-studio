import type { QueryClient } from '@tanstack/react-query'
import type { ApplicationEventManager } from '@/shared/app-events/manager'
import type { InteractionDTO, InteractionPageDTO } from '@/shared/api/generated/contracts'
import { queryKeys } from '@/shared/lib/query-keys'
import { requestThreadSnapshotRefresh } from '@/features/thread/runtime/state/thread-snapshot-cache'

/** 409 回读后该事项的去向：已不在待处理事实里、仍待处理、或本次没有可判断的回读证据。 */
export type InteractionConflictOutcome = 'resolved' | 'pending' | 'unverified'

/**
 * 审批/问卷 409 后的回读闭环。
 *
 * <p>与成功路径一致地失效 interactions 列表并请求 Thread 快照刷新，然后只用本次真正回读到
 * 的待处理列表判断该事项的去向：任何已刷新的列表仍包含它就是 `pending`；所有已刷新的列表都
 * 不含它、且覆盖该事项的查询返回完整首屏（`nextCursor` 为空）才判定 `resolved`；没有本次回读证据
 * （列表未刷新、全部被截断或读取失败）则是 `unverified`，由调用方保留只读围栏。
 */
export async function reconcileInteractionConflict({
  queryClient,
  applicationEvents,
  threadId,
  toolInvocationId,
}: {
  queryClient: QueryClient
  applicationEvents: ApplicationEventManager
  threadId: string
  toolInvocationId: string
}): Promise<InteractionConflictOutcome> {
  const pagesBefore = new Map(
    queryClient
      .getQueryCache()
      .findAll({ queryKey: queryKeys.interactions.all })
      .filter((query) => query.queryKey[1] === 'list')
      .map((query) => [query.queryHash, {
        count: query.state.dataUpdateCount,
        page: query.state.data as InteractionPageDTO | undefined,
      }] as const),
  )
  // 失效回读：active 列表在这个 Promise 内真正重读完成；单个列表读取失败只说明它没有新数据。
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: queryKeys.interactions.all }),
    requestThreadSnapshotRefresh(queryClient, applicationEvents, threadId),
  ])

  let sawCompletePage = false
  for (const query of queryClient.getQueryCache().findAll({ queryKey: queryKeys.interactions.all })) {
    const refreshedCount = query.state.dataUpdateCount
    const before = pagesBefore.get(query.queryHash)
    const data = query.state.data as InteractionPageDTO | undefined
    // 同一前缀下只有待处理列表分页能回答这个问题（如计数等其他键一律跳过）。
    if (query.queryKey[1] !== 'list' || data == null || refreshedCount <= (before?.count ?? 0)) {
      continue
    }
    if (data.items.some((item: InteractionDTO) => isSameInvocation(item, toolInvocationId))) {
      return 'pending'
    }
    const [, , rootThreadId, cursor] = query.queryKey
    const coversInvocation = rootThreadId == null || rootThreadId === threadId
      || before?.page?.items.some((item) => isSameInvocation(item, toolInvocationId))
    if (coversInvocation && cursor == null && data.nextCursor == null) {
      sawCompletePage = true
    }
  }
  return sawCompletePage ? 'resolved' : 'unverified'
}

function isSameInvocation(item: InteractionDTO, toolInvocationId: string): boolean {
  return item.type !== 'ENVIRONMENT_WAIT' && item.toolInvocationId === toolInvocationId
}
