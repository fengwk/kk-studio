import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef } from 'react'
import type { InstantTimestamp } from '@/shared/api/contracts/base'
import { queryKeys } from '@/shared/lib/query-keys'

/** 服务端给出的失效时刻只是上界：稍微越过再回读，避免与服务端时钟在边界上竞争。 */
const FRESHNESS_RECHECK_SLACK_MS = 250

/**
 * 交互读模型的时效对账：`freshnessAt` 是服务端算出的最早可能变更时刻（环境连接租约到期、
 * 工具可领取时间或 Work 租约到期）。这些变化没有数据库写事件，所以只在该时刻安排一次回读，
 * 不轮询、也不维护客户端过期集合。
 *
 * <p>续租或任何推送更新权威数据后，旧定时器被取消并按新截止点重排；同一个已消费的截止点
 * 不再排任务，避免无变化时反复回读。全局角标、全局列表与所有根面板共享同一
 * `queryKeys.interactions.all` 前缀，因此一次失效覆盖全部待处理视图。
 */
export function useInteractionFreshnessRecheck(
  freshnessAt: InstantTimestamp | null | undefined,
): void {
  const queryClient = useQueryClient()
  const consumedDeadline = useRef<number | null>(null)
  // InstantTimestamp 在 wire 上是 epoch seconds 数字，字符串形式按 ISO 时刻解析；两者都归一到毫秒。
  const deadline =
    freshnessAt == null
      ? null
      : typeof freshnessAt === 'number'
        ? freshnessAt * 1000
        : Date.parse(freshnessAt)
  useEffect(() => {
    if (deadline == null || !Number.isFinite(deadline) || consumedDeadline.current === deadline) {
      return
    }
    const timer = setTimeout(() => {
      consumedDeadline.current = deadline
      void queryClient.invalidateQueries({ queryKey: queryKeys.interactions.all })
    }, Math.max(deadline - Date.now(), 0) + FRESHNESS_RECHECK_SLACK_MS)
    return () => clearTimeout(timer)
  }, [deadline, queryClient])
}
