import { useEffect, useState } from 'react'
import { useInfiniteQuery } from '@tanstack/react-query'
import type { HarnessThreadDTO, ThreadListSort } from '@/shared/api/contracts/ai-runtime'
import { chatService } from '@/shared/api/chat-service'
import { harnessService } from '@/shared/api/harness-service'
import { queryKeys } from '@/shared/lib/query-keys'

export type ChatThreadPickerScope = 'current' | 'global'

const PAGE_SIZE = 20

/** Paginated Thread data for the current Chat and the global historical picker. */
export function useChatThreadPicker(
  chatId: string,
  open: boolean,
  sort: ThreadListSort,
) {
  const [scope, setScope] = useState<ChatThreadPickerScope>('current')
  useEffect(() => {
    if (open) {
      setScope('current')
    }
  }, [chatId, open])
  const query = useInfiniteQuery({
    queryKey: queryKeys.threads.page(scope, sort, scope === 'current' ? chatId : undefined),
    queryFn: ({ pageParam }: { pageParam: string | undefined }) =>
      scope === 'current'
        ? chatService.listChatThreads(chatId, { sort, cursor: pageParam, limit: PAGE_SIZE })
        : harnessService.listThreads({ sort, cursor: pageParam, limit: PAGE_SIZE }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    enabled: open && Boolean(chatId),
  })

  const items: HarnessThreadDTO[] = query.data?.pages.flatMap((page) => page.items) ?? []

  return {
    scope,
    setScope,
    items,
    error: query.error,
    isLoading: query.isLoading,
    isFetchingNextPage: query.isFetchingNextPage,
    hasNextPage: Boolean(query.hasNextPage),
    loadMore: () => query.fetchNextPage(),
  }
}
