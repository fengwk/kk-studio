import { useQuery } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import type { CommandCompactionState } from '@/features/thread/runtime/thread-panel/thread-commands'

/**
 * 压缩命令行情的可用性投影：未绑定 Thread 时不提供状态，查询关闭时不额外请求。
 *
 * <p>错误与「尚未返回」是两种不同的可用性：前者让命令面板呈现失败态，后者呈现检查中。
 */
export function useCompactionAvailability({
  bound,
  boundThreadId,
  enabled,
}: {
  /** 当前目标是否为已绑定 Thread；草稿目标没有可查询的 Thread。 */
  bound: boolean
  boundThreadId: string
  /** 命令面板或可重试的压缩卡存在时才查询。 */
  enabled: boolean
}): CommandCompactionState | null {
  const query = useQuery({
    queryKey: queryKeys.threads.compactionAvailability(boundThreadId),
    queryFn: ({ signal }) => harnessService.getCompactionAvailability(boundThreadId, { signal }),
    enabled: enabled && bound && Boolean(boundThreadId),
  })

  if (!bound) {
    return null
  }
  if (query.isError) {
    return { status: 'failed' }
  }
  if (query.data) {
    return { status: 'ready', availability: query.data }
  }
  return { status: 'checking' }
}
