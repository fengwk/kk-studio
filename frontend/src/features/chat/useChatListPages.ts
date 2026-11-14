import { useEffect, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { appendUniqueChats, mergeChatPage } from '@/features/chat/chat-utils'
import { chatService } from '@/features/chat/chat-service'
import { queryKeys } from '@/shared/lib/query-keys'
import type { ChatDTO, ChatPageDTO } from '@/shared/api/generated/contracts'

/**
 * Chat 列表的首页查询与追加分页。
 *
 * 追加页只存在于当前列表会话：首页重新取数（创建/更新/删除失效或窗口聚焦）后回到第一页。
 */
export function useChatListPages(enabled: boolean) {
  const queryClient = useQueryClient()
  const chatsQuery = useQuery({
    queryKey: queryKeys.chats.list,
    queryFn: async () => {
      const incoming = await chatService.listChats()
      const current = queryClient.getQueryData<ChatPageDTO>(queryKeys.chats.list)
      return mergeChatPage(current, incoming)
    },
    enabled,
  })
  const [extraChats, setExtraChats] = useState<{ items: ChatDTO[]; cursor: string | null }>({
    items: [],
    cursor: null,
  })
  const [chatsLoadingMore, setChatsLoadingMore] = useState(false)
  const [chatsLoadMoreError, setChatsLoadMoreError] = useState<unknown>(null)
  useEffect(() => {
    setExtraChats({ items: [], cursor: null })
    setChatsLoadMoreError(null)
  }, [chatsQuery.data])

  const chats = appendUniqueChats(chatsQuery.data?.items ?? [], extraChats.items)
  const nextChatsCursor =
    extraChats.items.length > 0 ? extraChats.cursor : chatsQuery.data?.nextCursor ?? null

  async function loadMoreChats() {
    if (nextChatsCursor == null || chatsLoadingMore) {
      return
    }
    setChatsLoadingMore(true)
    try {
      const page = await chatService.listChats(nextChatsCursor)
      setExtraChats((current) => ({
        items: appendUniqueChats(current.items, page.items),
        cursor: page.nextCursor ?? null,
      }))
      setChatsLoadMoreError(null)
    } catch (error) {
      setChatsLoadMoreError(error)
    } finally {
      setChatsLoadingMore(false)
    }
  }

  return {
    chatsQuery,
    chats,
    hasMoreChats: nextChatsCursor != null,
    chatsLoadingMore,
    chatsLoadMoreError,
    loadMoreChats,
  }
}
