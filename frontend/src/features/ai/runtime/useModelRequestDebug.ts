import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import type {
  HarnessModelRequestDebugDTO,
  ProviderRequestPreviewDTO,
} from '@/shared/api/contracts/ai-runtime'
import type { ThreadModelRequestDebugData } from '@/features/ai/runtime/thread-timeline-types'

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

  const debug: ThreadModelRequestDebugData | null = query.data ?? null
  return {
    debug,
    loading: query.isLoading,
    error: query.error,
    refetch: query.refetch,
  }
}

/**
 * 选中历史 Entry 时按需拉取其 provider request 重放预览。
 *
 * 只有显式 `request(entryId)` 才会发 GET；切换目标即换一次查询，`dismiss()` 清空。
 * 这是纯读取：不触发 transport、不消费附件、不入队命令、不改游标、不持久化。
 */
export function useHistoricalRequestPreview(sessionId: string | null | undefined) {
  const [entryId, setEntryId] = useState<string | null>(null)
  const enabled = Boolean(sessionId && entryId)
  const query = useQuery<ProviderRequestPreviewDTO>({
    queryKey: queryKeys.sessions.historicalRequestPreview(sessionId ?? '', entryId ?? ''),
    queryFn: () => harnessService.previewHistoricalRequest(sessionId as string, entryId as string),
    enabled,
    staleTime: Infinity,
  })

  return {
    /** 请求某个历史 Entry 的调用前请求前缀。 */
    request: (target: string) => setEntryId(target),
    dismiss: () => setEntryId(null),
    entryId,
    preview: enabled ? (query.data ?? null) : null,
    loading: enabled && query.isFetching,
    error: enabled ? query.error : null,
  }
}
