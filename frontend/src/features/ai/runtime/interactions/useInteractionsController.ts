import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback, useEffect, useState } from 'react'
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

export function useInteractionsController(limit = 20): UseInteractionsControllerResult {
  const queryClient = useQueryClient()
  const [accumulatedItems, setAccumulatedItems] = useState<InteractionDTO[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [isFetchingMore, setIsFetchingMore] = useState(false)

  // 初始第一页查询
  const {
    data: initialPage,
    isLoading,
    isError,
    error,
    refetch,
  } = useQuery({
    queryKey: queryKeys.interactions.list(null, limit),
    queryFn: () => interactionService.listInteractions(null, limit),
  })

  // 当第一页数据更新时，重置累加列表和游标
  useEffect(() => {
    if (initialPage) {
      setAccumulatedItems(initialPage.items || [])
      setNextCursor(initialPage.nextCursor || null)
    }
  }, [initialPage])

  // 恢复刷新：清空下游游标并重新拉取第一页
  const refresh = useCallback(async () => {
    await queryClient.invalidateQueries({ queryKey: queryKeys.interactions.all })
    const res = await refetch()
    if (res.data) {
      setAccumulatedItems(res.data.items || [])
      setNextCursor(res.data.nextCursor || null)
    }
  }, [queryClient, refetch])

  // 加载更多（游标翻页）
  const loadMore = useCallback(async () => {
    if (!nextCursor || isFetchingMore) {
      return
    }
    setIsFetchingMore(true)
    try {
      const nextPage = await interactionService.listInteractions(nextCursor, limit)
      setAccumulatedItems((prev) => {
        const existingIds = new Set(prev.map((it) => it.interactionId))
        const newItems = (nextPage.items || []).filter(
          (it) => !existingIds.has(it.interactionId),
        )
        return [...prev, ...newItems]
      })
      setNextCursor(nextPage.nextCursor || null)
    } finally {
      setIsFetchingMore(false)
    }
  }, [nextCursor, isFetchingMore, limit])

  // 某项交互完成后的乐观移除
  const removeItem = useCallback((interactionId: string) => {
    setAccumulatedItems((prev) => prev.filter((it) => it.interactionId !== interactionId))
  }, [])

  return {
    items: accumulatedItems,
    isLoading,
    isFetchingMore,
    isError,
    error: error instanceof Error ? error : null,
    nextCursor,
    hasMore: Boolean(nextCursor),
    refresh,
    loadMore,
    removeItem,
  }
}
