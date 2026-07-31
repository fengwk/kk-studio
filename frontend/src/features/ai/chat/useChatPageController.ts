import { useDeferredValue, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { toUserFacingErrorMessage } from '@/features/ai/ai-user-facing-error'
import { filterChats } from '@/features/ai/chat/chat-utils'
import { useChatListController } from '@/features/ai/chat/useChatListController'
import { agentService } from '@/shared/api/agent-service'
import { queryKeys } from '@/shared/lib/query-keys'

/** Chat 页面仅加载 Chat 列表和创建 Chat 所需的 Agent 选项。 */
export function useChatPageController() {
  const [search, setSearch] = useState('')
  const deferredSearch = useDeferredValue(search.trim().toLowerCase())
  const agentsQuery = useQuery({
    queryKey: queryKeys.agents.list,
    queryFn: () => agentService.listAgents(),
  })
  const agents = agentsQuery.data?.results ?? []
  const chatController = useChatListController(agents, true)
  const chats = useMemo(
    () => filterChats(chatController.chats, deferredSearch),
    [chatController.chats, deferredSearch],
  )
  const rawMutationError = chatController.chatMutationError

  return {
    search,
    setSearch,
    busy: agentsQuery.isLoading || chatController.chatsQuery.isLoading,
    error: agentsQuery.error ?? chatController.chatsQuery.error ?? null,
    mutationError: rawMutationError
      ? new Error(toUserFacingErrorMessage(rawMutationError))
      : null,
    chatPanelProps: {
      chats,
      agents,
      onCreate: chatController.openCreateChat,
    },
    createChatModal: chatController.createChatModal,
  }
}

export type ChatPageController = ReturnType<typeof useChatPageController>
