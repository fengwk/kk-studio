import { useDeferredValue, useMemo, useState } from 'react'
import { filterAgents, filterModels, filterProviders, filterThreads } from '@/features/ai/ai-console-utils'
import type { AgentDefinitionDTO, AgentModelDTO, AgentProviderDTO } from '@/shared/api/contracts'
import { useAiConsoleResourceController } from '@/features/ai/useAiConsoleResourceController'
import { useAiConsoleThreadController } from '@/features/ai/useAiConsoleThreadController'

export function useAiConsoleController() {
  const [search, setSearch] = useState('')
  const deferredSearch = useDeferredValue(search.trim().toLowerCase())

  const resourceController = useAiConsoleResourceController()
  const threadController = useAiConsoleThreadController(resourceController.agents)

  const agentsById = useMemo(() => new Map(resourceController.agents.map((agent) => [String(agent.id), agent])), [resourceController.agents])
  const filteredThreads = useMemo(
    () => filterThreads(threadController.threads, agentsById, deferredSearch),
    [agentsById, deferredSearch, threadController.threads],
  )
  const filteredAgents = useMemo(() => filterAgents(resourceController.agents, deferredSearch), [deferredSearch, resourceController.agents])
  const filteredModels = useMemo(() => filterModels(resourceController.models, deferredSearch), [deferredSearch, resourceController.models])
  const filteredProviders = useMemo(() => filterProviders(resourceController.providers, deferredSearch), [deferredSearch, resourceController.providers])

  const queryResults = [
    resourceController.providersQuery,
    resourceController.modelsQuery,
    resourceController.agentsQuery,
    threadController.threadsQuery,
  ]
  const mutationErrors = [resourceController.resourceMutationError, threadController.threadMutationError]

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
      threads: filteredThreads,
      agentsById,
      onCreate: () => threadController.openCreateThread(),
    },
    agentPanelProps: {
      agents: filteredAgents,
      deletePending: resourceController.agentDeletePending,
      onCreate: resourceController.openCreateAgent,
      onStart: (agent: AgentDefinitionDTO) => threadController.openCreateThread(String(agent.id)),
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
    createThreadModal: threadController.createThreadModal,
    resourceEditorModal: resourceController.resourceEditorModal,
    resourceDeleteConfirmModal: resourceController.deleteConfirmModal,
  }
}
