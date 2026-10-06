import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useRef, useState } from 'react'
import type { InteractionDTO } from '@/shared/api/contracts/ai-interaction'
import { interactionService } from '@/shared/api/interaction-service'
import { queryKeys } from '@/shared/lib/query-keys'

export interface UseInteractionsControllerResult {
  items: InteractionDTO[]
  isLoading: boolean
  isFetchingMore: boolean
  isError: boolean
  error: Error | null
  nextCursor: string | null
  hasMore: boolean
  refresh: () => Promise<void>
  loadMore: () => Promise<void>
  removeItem: (interactionId: string) => void
}

export function useInteractionsController(
  rootThreadId: string | null = null,
  limit = 20,
): UseInteractionsControllerResult {
  const queryClient = useQueryClient()
  const [accumulatedItems, setAccumulatedItems] = useState<InteractionDTO[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [isFetchingMore, setIsFetchingMore] = useState(false)
  const [loadMoreError, setLoadMoreError] = useState<Error | null>(null)
  const isFetchingMoreRef = useRef(false)
  const generationRef = useRef(0)
  const lastAppliedInitialPageRef = useRef<unknown>(null)
  const lastDataUpdatedAtRef = useRef(0)

  // 初始第一页查询；rootThreadId 变化即视为新的过滤代际。
  const {
    data: initialPage,
    dataUpdatedAt,
    isLoading,
    isError: isInitialError,
    error: initialError,
    refetch,
  } = useQuery({
    queryKey: queryKeys.interactions.list(rootThreadId, null, limit),
    queryFn: () => interactionService.listInteractions(rootThreadId, null, limit),
  })

  // 当第一页数据更新时（包括外部 invalidate 后重新拉取到新数据），重置累加列表、游标与分页代际
  useEffect(() => {
    if (!initialPage) {
      return
    }
    const isNewData = initialPage !== lastAppliedInitialPageRef.current
    const isUpdatedTimestamp = dataUpdatedAt > 0 && dataUpdatedAt !== lastDataUpdatedAtRef.current
    if (isNewData) {
      lastAppliedInitialPageRef.current = initialPage
      lastDataUpdatedAtRef.current = dataUpdatedAt
      generationRef.current += 1
      setAccumulatedItems(initialPage.items || [])
      setNextCursor(initialPage.nextCursor || null)
      isFetchingMoreRef.current = false
      setIsFetchingMore(false)
      setLoadMoreError(null)
    } else if (isUpdatedTimestamp) {
      // 结构共享导致 initialPage 为同一引用（无新 data 但数据更新时间更新，如外部 invalidate 新页与旧页完全相同且 cursor 一样）
      // 每次接受新的 dataUpdatedAt 都必须废弃旧 request generation（列表可保留），游标与加载状态严格由新代拥有
      lastDataUpdatedAtRef.current = dataUpdatedAt
      generationRef.current += 1
      isFetchingMoreRef.current = false
      setIsFetchingMore(false)
      setLoadMoreError(null)
    }
  }, [initialPage, dataUpdatedAt])

  // 恢复刷新：清空下游游标并重新拉取第一页，开启新世代
  const refresh = useCallback(async () => {
    const generation = ++generationRef.current
    isFetchingMoreRef.current = false
    setIsFetchingMore(false)
    setLoadMoreError(null)
    await queryClient.invalidateQueries({ queryKey: queryKeys.interactions.all })
    const res = await refetch()
    if (generation === generationRef.current && res.data) {
      lastAppliedInitialPageRef.current = res.data
      setAccumulatedItems(res.data.items || [])
      setNextCursor(res.data.nextCursor || null)
    }
  }, [queryClient, refetch])

  // 加载更多（游标翻页）：绑定 generation 与当前游标，旧代迟到响应与 finally 均做栅栏拦截
  const loadMore = useCallback(async () => {
    const generation = generationRef.current
    const cursor = nextCursor
    if (!cursor || isFetchingMoreRef.current) {
      return
    }
    isFetchingMoreRef.current = true
    setIsFetchingMore(true)
    try {
      const nextPage = await interactionService.listInteractions(rootThreadId, cursor, limit)
      if (generation !== generationRef.current) {
        return
      }
      setAccumulatedItems((prev) => {
        const existingIds = new Set(prev.map((it) => it.interactionId))
        const newItems = (nextPage.items || []).filter(
          (it) => !existingIds.has(it.interactionId),
        )
        return [...prev, ...newItems]
      })
      setNextCursor(nextPage.nextCursor || null)
    } catch (err) {
      if (generation === generationRef.current) {
        setLoadMoreError(err instanceof Error ? err : new Error(String(err)))
      }
      // 不直接向外抛出未捕获异常，避免 UI 层触发未处理的 unhandled rejection
      // stale 世代静默 resolve，current 世代写入受控 loadMoreError 供 UI 呈现
    } finally {
      // 旧代 finally 不得重置新状态
      if (generation === generationRef.current) {
        isFetchingMoreRef.current = false
        setIsFetchingMore(false)
      }
    }
  }, [nextCursor, limit, rootThreadId])

  // 某项交互完成后的乐观移除
  const removeItem = useCallback((interactionId: string) => {
    setAccumulatedItems((prev) => prev.filter((it) => it.interactionId !== interactionId))
  }, [])

  return {
    items: accumulatedItems,
    isLoading,
    isFetchingMore,
    isError: isInitialError || Boolean(loadMoreError),
    error: (initialError instanceof Error ? initialError : null) ?? loadMoreError,
    nextCursor,
    hasMore: Boolean(nextCursor),
    refresh,
    loadMore,
    removeItem,
  }
}
