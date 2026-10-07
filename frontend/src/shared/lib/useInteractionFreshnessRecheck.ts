import { useQueryClient, type QueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import type { InstantTimestamp } from '@/shared/api/contracts/base'
import { queryKeys } from '@/shared/lib/query-keys'

/** 服务端给出的失效时刻只是上界：稍微越过再回读，避免与服务端时钟在边界上竞争。 */
const FRESHNESS_RECHECK_SLACK_MS = 250

const coordinators = new WeakMap<QueryClient, ReturnType<typeof createCoordinator>>()

/** 每个缓存只有一个计时器；订阅释放不删除已消费记录，后来挂载的视图也不会重复回读。 */
function createCoordinator(queryClient: QueryClient) {
  const subscriptions = new Map<symbol, number>()
  const consumed = new Set<number>()
  let timer: ReturnType<typeof setTimeout> | undefined

  function schedule() {
    clearTimeout(timer)
    timer = undefined
    let next = Infinity
    for (const deadline of subscriptions.values()) {
      if (!consumed.has(deadline)) {
        next = Math.min(next, deadline)
      }
    }
    if (!Number.isFinite(next)) {
      return
    }
    timer = setTimeout(() => {
      consumed.add(next)
      schedule()
      void queryClient.invalidateQueries({ queryKey: queryKeys.interactions.all })
    }, Math.max(next + FRESHNESS_RECHECK_SLACK_MS - Date.now(), 0))
  }

  return {
    subscribe(deadline: number) {
      const token = Symbol()
      subscriptions.set(token, deadline)
      schedule()
      return () => {
        subscriptions.delete(token)
        schedule()
      }
    },
  }
}

/**
 * 交互读模型的时效对账：`freshnessAt` 是服务端算出的最早可能变更时刻（环境连接租约到期、
 * 工具可领取时间或 Work 租约到期）。这些变化没有数据库写事件，所以只在该时刻安排一次回读，
 * 不轮询、也不维护客户端过期集合。
 *
 * 续租或推送更新后注销旧截止点；同一 QueryClient 的消费者共用定时器与已消费截止点，
 * 避免无变化时反复回读。全局角标、全局列表与所有根面板共享同一
 * `queryKeys.interactions.all` 前缀，因此一次失效覆盖全部待处理视图。
 */
export function useInteractionFreshnessRecheck(
  freshnessAt: InstantTimestamp | null | undefined,
): void {
  const queryClient = useQueryClient()
  // InstantTimestamp 在 wire 上是 epoch seconds 数字，字符串形式按 ISO 时刻解析；两者都归一到毫秒。
  const deadline =
    freshnessAt == null
      ? null
      : typeof freshnessAt === 'number'
        ? freshnessAt * 1000
        : Date.parse(freshnessAt)
  useEffect(() => {
    if (deadline == null || !Number.isFinite(deadline)) {
      return
    }
    let coordinator = coordinators.get(queryClient)
    if (!coordinator) {
      coordinator = createCoordinator(queryClient)
      coordinators.set(queryClient, coordinator)
    }
    return coordinator.subscribe(deadline)
  }, [deadline, queryClient])
}
