import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'
import type {
  HarnessModelRequestDebugDTO,
  ProviderRequestPreviewDTO,
} from '@/shared/api/contracts/ai-runtime'
import type { ThreadModelRequestDebugData } from '@/features/ai/runtime/thread-timeline-types'

/**
 * 仅在 Debug 视图启用。进入 `/debug` 拉取一次，并跟随当前 Thread 快照的调用身份 / phase /
 * requestHead、head 与规划 settings / 目录事实刷新。
 *
 * <p>它绝不依赖 `working` 的一次 true/false：连续 model -> tool -> 下一 model 期间 `working`
 * 一直为 true，但活动 invocation 的身份已经改变，旧冻结输入必须随新的调用身份换成新的读取，
 * 不能残留。`revision` 就是这些事实的稳定摘要，作为查询身份的一部分：身份变化即更换读取，
 * 加载 / 读取错误独立呈现，不被吞成空态。
 */
export function useModelRequestDebug(
  threadId: string,
  enabled: boolean,
  revision: string,
) {
  const client = useQueryClient()
  const [catalogRevision, setCatalogRevision] = useState(0)
  useEffect(() => {
    if (!enabled || !threadId) return
    // Catalog mutations already invalidate these query roots. No credentials or definitions
    // enter the debug query key, and this listener exists only while Debug is visible.
    return client.getQueryCache().subscribe((event) => {
      if (event.type !== 'updated') return
      const root = event.query.queryKey[0]
      if (!['agents', 'models', 'providers', 'tools', 'skills', 'mcp-servers'].includes(String(root))) return
      if (event.action.type === 'invalidate' || event.action.type === 'success') {
        setCatalogRevision((value) => value + 1)
      }
    })
  }, [client, enabled, threadId])
  const query = useQuery<HarnessModelRequestDebugDTO>({
    // revision 是查询身份而非缓存提示：换调用/换设置就换一次读取，绝不沿用上一身份的冻结输入。
    queryKey: [...queryKeys.threads.modelRequestDebug(threadId), revision, catalogRevision],
    queryFn: () => harnessService.getModelRequestDebug(threadId),
    enabled: Boolean(threadId) && enabled,
    staleTime: 0,
    gcTime: 0,
  })

  const debug: ThreadModelRequestDebugData | null = enabled ? query.data ?? null : null
  return {
    debug,
    loading: Boolean(threadId) && enabled && query.isLoading,
    error: query.error,
    refetch: query.refetch,
  }
}

/**
 * 选中历史 Entry 时按需拉取其 provider request 重放预览。
 *
 * <p>只有显式 `request(entryId)` 才会发 GET；重复选择同一条目也会重新拉取一次（不复用
 * `staleTime: Infinity` 的永久缓存），确保当前目录 / Provider 配置的变化能被重新读取。
 * 每次 request 都用新的读取身份，因此迟到的旧结果只会落在自己的身份上，不会替换当前查看。
 *
 * <p>`identityKey`（session + viewKey）变化即丢弃旧选择；`dismiss()` 清空后不残留历史 JSON。
 * 这是纯读取：不触发 transport、不消费附件、不入队命令、不改游标、不持久化。
 */
export function useHistoricalRequestPreview(
  sessionId: string | null | undefined,
  identityKey: string,
) {
  const activeSession = sessionId ?? null
  const identity = `${activeSession ?? ''}|${identityKey}`
  const [selection, setSelection] = useState<{ entryId: string; token: number } | null>(null)
  const nextTokenRef = useRef(0)

  // 来源身份围栏：切换 Session/viewKey/条目时先作废旧选择，绝不把旧目标的预览带到新身份。
  const [lastIdentity, setLastIdentity] = useState(identity)
  if (lastIdentity !== identity) {
    setLastIdentity(identity)
    if (selection != null) {
      setSelection(null)
    }
  }

  const entryId = selection?.entryId ?? null
  const enabled = Boolean(activeSession) && lastIdentity === identity && selection != null
  const query = useQuery<ProviderRequestPreviewDTO>({
    queryKey: [
      ...queryKeys.sessions.historicalRequestPreview(activeSession ?? '', entryId ?? ''),
      identityKey,
      selection?.token ?? 0,
    ],
    queryFn: () => harnessService.previewHistoricalRequest(activeSession as string, entryId as string),
    enabled,
    staleTime: 0,
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    retry: false,
  })

  return {
    /** 请求某个历史 Entry 的调用前请求前缀；再次操作同一条目也会重新拉取。 */
    request: (target: string) => {
      nextTokenRef.current += 1
      setSelection({ entryId: target, token: nextTokenRef.current })
    },
    dismiss: () => setSelection(null),
    entryId,
    preview: enabled ? (query.data ?? null) : null,
    loading: enabled && query.isFetching,
    error: enabled ? query.error : null,
  }
}
