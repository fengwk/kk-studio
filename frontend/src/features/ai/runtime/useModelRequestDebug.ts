import { useEffect, useRef } from 'react'
import { useQuery } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import type { HarnessModelRequestDebugDTO } from '@/shared/api/contracts/ai-runtime'

/**
 * 仅在 Debug 视图启用。进入 `/debug` 拉一次；turn 开始与结束时（working 变化时）刷新，
 * 使同批 SET_AGENT/SET_MODEL 等设置与模型运行状态及时反映到结构化预览。
 */
export function useModelRequestDebug(
  threadId: string,
  enabled: boolean,
  working: boolean,
) {
  const query = useQuery<HarnessModelRequestDebugDTO>({
    queryKey: queryKeys.threads.modelRequestDebug(threadId),
    queryFn: () => harnessService.getModelRequestDebug(threadId),
    enabled: Boolean(threadId) && enabled,
    staleTime: Infinity,
  })
  const wasWorkingRef = useRef(working)

  useEffect(() => {
    const workingChanged = wasWorkingRef.current !== working
    wasWorkingRef.current = working
    if (!enabled || !threadId || !workingChanged) {
      return
    }
    void query.refetch()
  }, [enabled, query, threadId, working])

  return {
    debug: query.data ?? null,
    loading: query.isLoading,
    error: query.error,
    refetch: query.refetch,
  }
}
