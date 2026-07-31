import { useDeferredValue, useMemo, useState } from 'react'
import {
  modelRef,
  useAiConsoleResourceController,
  type AgentModelView,
} from '@/features/ai/catalog'
import type { AiConsoleResourceQueryEnabled } from '@/features/ai/catalog/useAiConsoleResourceQueries'
import {
  filterAgents,
  filterModels,
  filterProviders,
} from '@/features/ai/catalog/catalog-utils'
import { toUserFacingErrorMessage } from '@/features/ai/catalog/ai-resource-form-validation'
import { filterChats } from '@/features/ai/chat/chat-utils'
import { useChatListController } from '@/features/ai/chat'
import type {
  AgentDefinitionDTO,
  AgentProviderDTO,
} from '@/shared/api/contracts/ai-catalog'
import type { ChatDTO } from '@/shared/api/contracts/ai-chat'

export type AiConsolePageScope = 'chats' | 'agents' | 'models' | 'providers'

const resourceQueryEnabledByScope: Record<
  AiConsolePageScope,
  AiConsoleResourceQueryEnabled
> = {
  chats: {
    providers: false,
    models: false,
    agents: true,
    environments: false,
  },
  agents: {
    providers: true,
    models: true,
    agents: true,
    environments: true,
  },
  models: {
    providers: true,
    models: true,
    agents: false,
    environments: false,
  },
  providers: {
    providers: true,
    models: false,
    agents: false,
    environments: false,
  },
}

export function useAiConsoleController(scope: AiConsolePageScope) {
  const [search, setSearch] = useState('')
  const deferredSearch = useDeferredValue(search.trim().toLowerCase())

  const resourceQueryEnabled = resourceQueryEnabledByScope[scope]
  const chatsEnabled = scope === 'chats'
  const resourceController = useAiConsoleResourceController(resourceQueryEnabled)
  const chatController = useChatListController(resourceController.agents, chatsEnabled)

  const filteredChats = useMemo(
    () => filterChats(chatController.chats, deferredSearch),
    [chatController.chats, deferredSearch],
  )
  const filteredAgents = useMemo(
    () => filterAgents(resourceController.agents, deferredSearch),
    [deferredSearch, resourceController.agents],
  )
  const filteredModels = useMemo(
    () => filterModels(resourceController.models, deferredSearch),
    [deferredSearch, resourceController.models],
  )
  const filteredProviders = useMemo(
    () => filterProviders(resourceController.providers, deferredSearch),
    [deferredSearch, resourceController.providers],
  )

  const queryResults = [
    ...(resourceQueryEnabled.providers ? [resourceController.providersQuery] : []),
    ...(resourceQueryEnabled.models ? [resourceController.modelsQuery] : []),
    ...(resourceQueryEnabled.agents ? [resourceController.agentsQuery] : []),
    ...(resourceQueryEnabled.environments
      ? [resourceController.environmentsQuery]
      : []),
    ...(chatsEnabled ? [chatController.chatsQuery] : []),
  ]
  const resourceModalOpen = Boolean(resourceController.resourceEditorModal.modal)
  // 资源编辑模态打开时：本地校验/API 错误都只在模态内展示，页面外层不重复报错。
  const mutationErrors = [
    resourceModalOpen ? null : resourceController.resourceMutationError,
    chatController.chatMutationError,
  ]

  const busy = queryResults.some((query) => query.isLoading)
  const error = queryResults.find((query) => query.error)?.error ?? null
  const rawMutationError = resourceModalOpen
    ? null
    : (mutationErrors.find((candidate) => candidate != null) ?? null)
  const mutationError = rawMutationError
    ? new Error(toUserFacingErrorMessage(rawMutationError))
    : null

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
      onDelete: (agent: AgentDefinitionDTO) =>
        resourceController.deleteAgent(agent.name, agent.id, agent.version),
    },
    modelPanelProps: {
      models: filteredModels,
      deletePending: resourceController.modelDeletePending,
      onCreate: resourceController.openCreateModel,
      onEdit: (model: AgentModelView) => resourceController.openEditModel(model.id),
      onDelete: (model: AgentModelView) =>
        resourceController.deleteModel(
          model.providerName || String(model.providerId),
          modelRef(model),
          model.id,
          model.version,
        ),
    },
    providerPanelProps: {
      providers: filteredProviders,
      deletePending: resourceController.providerDeletePending,
      onCreate: resourceController.openCreateProvider,
      onEdit: (provider: AgentProviderDTO) => resourceController.openEditProvider(provider.id),
      onDelete: (provider: AgentProviderDTO) =>
        resourceController.deleteProvider(provider.name, provider.id, provider.version),
    },
    createChatModal: chatController.createChatModal,
    resourceEditorModal: resourceController.resourceEditorModal,
    resourceDeleteConfirmModal: resourceController.deleteConfirmModal,
  }
}

export type { ChatDTO }
