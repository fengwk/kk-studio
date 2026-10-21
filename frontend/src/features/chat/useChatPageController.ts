import { useDeferredValue, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { toUserFacingErrorMessage } from '@/shared/lib/user-facing-error'
import { filterChats } from '@/features/chat/chat-utils'
import { useChatListController } from '@/features/chat/useChatListController'
import { agentService } from '@/shared/api/agent-service'
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
  // 已有可用 Agent 即说明 Provider / Model 齐备；否则才查询它们来渲染首次使用引导（与 Catalog 页共享缓存）。
  const hasAgent = agents.some((agent) => agent.model !== null)
  const needsSetupFacts = agentsQuery.isSuccess && !hasAgent
  const providersQuery = useQuery({
    queryKey: queryKeys.providers.list,
    queryFn: () => agentService.listProviders(),
    enabled: needsSetupFacts,
  })
  const modelsQuery = useQuery({
    queryKey: queryKeys.models.list,
    queryFn: () => agentService.listModels(),
    enabled: needsSetupFacts,
  })
  const setup = !agentsQuery.isSuccess
    ? undefined
    : hasAgent
      ? { hasProvider: true, hasModel: true, hasAgent: true }
      : providersQuery.data && modelsQuery.data
        ? {
            hasProvider: providersQuery.data.results.length > 0,
            hasModel: modelsQuery.data.results.length > 0,
            hasAgent: false,
          }
        : undefined
  const chatController = useChatListController(agents, true)
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
      setup,
    },
    createChatModal: chatController.createChatModal,
    deleteConfirmModal: chatController.deleteConfirmModal,
  }
}

export type ChatPageController = ReturnType<typeof useChatPageController>
