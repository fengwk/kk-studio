import { useDeferredValue, useMemo, useState } from 'react'
import { filterAgents, filterModels, filterProviders } from '@/features/ai/ai-console-utils'
import type { AgentDefinitionDTO, AgentModelDTO, AgentProviderDTO, ChatDTO } from '@/shared/api/contracts'
import { useAiConsoleResourceController } from '@/features/ai/useAiConsoleResourceController'
import { useChatListController } from '@/features/ai/useChatListController'

export function useAiConsoleController() {
  const [search, setSearch] = useState('')
  const deferredSearch = useDeferredValue(search.trim().toLowerCase())

  const resourceController = useAiConsoleResourceController()
  const chatController = useChatListController(resourceController.agents)

  const filteredChats = useMemo(
    () =>
      chatController.chats.filter(
        (chat) => !deferredSearch || (chat.title ?? '').toLowerCase().includes(deferredSearch) || chat.id.includes(deferredSearch),
      ),
    [chatController.chats, deferredSearch],
  )
  const filteredAgents = useMemo(() => filterAgents(resourceController.agents, deferredSearch), [deferredSearch, resourceController.agents])
  const filteredModels = useMemo(() => filterModels(resourceController.models, deferredSearch), [deferredSearch, resourceController.models])
  const filteredProviders = useMemo(() => filterProviders(resourceController.providers, deferredSearch), [deferredSearch, resourceController.providers])

  const queryResults = [
    resourceController.providersQuery,
    resourceController.modelsQuery,
    resourceController.agentsQuery,
    resourceController.environmentsQuery,
    chatController.chatsQuery,
  ]
  const mutationErrors = [resourceController.resourceMutationError, chatController.chatMutationError]

  const busy = queryResults.some((query) => query.isLoading)
  const error = queryResults.find((query) => query.error)?.error ?? null
  const mutationError = mutationErrors.find((candidate) => candidate != null) ?? null

  return {
    search,
    setSearch,
    busy,
    error,
    mutationError,
    chatPanelProps: {
      chats: filteredChats,
      agents: resourceController.agents,
      onCreate: () => chatController.openCreateChat(),
    },
    agentPanelProps: {
      agents: filteredAgents,
      models: resourceController.models,
      deletePending: resourceController.agentDeletePending,
      onCreate: resourceController.openCreateAgent,
      onEdit: (agent: AgentDefinitionDTO) => resourceController.openEditAgent(agent.id),
      onDelete: (agent: AgentDefinitionDTO) => resourceController.deleteAgent(agent.name, agent.id),
    },
    modelPanelProps: {
      models: filteredModels,
      deletePending: resourceController.modelDeletePending,
      onCreate: resourceController.openCreateModel,
      onEdit: (model: AgentModelDTO) => resourceController.openEditModel(model.id),
      onDelete: (model: AgentModelDTO) => resourceController.deleteModel(model.providerName, model.name, model.id),
    },
    providerPanelProps: {
      providers: filteredProviders,
      deletePending: resourceController.providerDeletePending,
      onCreate: resourceController.openCreateProvider,
      onEdit: (provider: AgentProviderDTO) => resourceController.openEditProvider(provider.id),
      onDelete: (provider: AgentProviderDTO) => resourceController.deleteProvider(provider.name, provider.id),
    },
    createChatModal: chatController.createChatModal,
    resourceEditorModal: resourceController.resourceEditorModal,
    resourceDeleteConfirmModal: resourceController.deleteConfirmModal,
  }
}

export type { ChatDTO }
