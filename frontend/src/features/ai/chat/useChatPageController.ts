import { useDeferredValue, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { toUserFacingErrorMessage } from '@/features/ai/ai-user-facing-error'
import { filterChats } from '@/features/ai/chat/chat-utils'
import { useChatListController } from '@/features/ai/chat/useChatListController'
import { agentService } from '@/shared/api/agent-service'
import { environmentService } from '@/shared/api/environment-service'
import { queryKeys } from '@/shared/lib/query-keys'
import { useI18n } from '@/shared/i18n'

/** Chat 页面仅加载 Chat 列表和创建 Chat 所需的 Agent 选项。 */
export function useChatPageController() {
  const { locale } = useI18n()
  const [search, setSearch] = useState('')
  const deferredSearch = useDeferredValue(search.trim().toLowerCase())
  const agentsQuery = useQuery({
    queryKey: queryKeys.agents.list,
    queryFn: () => agentService.listAgents(),
  })
  const agents = agentsQuery.data?.results ?? []
  const environmentsQuery = useQuery({
    queryKey: queryKeys.environments.list,
    queryFn: () => environmentService.listEnvironments(),
  })
  const environments = environmentsQuery.data ?? []
  const chatController = useChatListController(agents, true, environments)
  const chats = useMemo(
    () => filterChats(chatController.chats, deferredSearch),
    [chatController.chats, deferredSearch],
  )
  const rawMutationError = chatController.chatMutationError
  const mutationError = useMemo(
    () => {
      void locale
      return rawMutationError ? new Error(toUserFacingErrorMessage(rawMutationError)) : null
    },
    [locale, rawMutationError],
  )

  return {
    search,
    setSearch,
    busy: agentsQuery.isLoading || chatController.chatsQuery.isLoading,
    error: agentsQuery.error ?? chatController.chatsQuery.error ?? null,
    mutationError,
    chatPanelProps: {
      chats,
      agents,
      onCreate: chatController.openCreateChat,
      onEdit: chatController.openEditChat,
      onDelete: chatController.openDeleteChat,
      deletePending: chatController.deleteConfirmModal.pending,
    },
    createChatModal: chatController.createChatModal,
    deleteConfirmModal: chatController.deleteConfirmModal,
  }
}

export type ChatPageController = ReturnType<typeof useChatPageController>
