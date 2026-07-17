import { useDeferredValue, useMemo, useState } from 'react'
import { filterAgents, filterModels, filterProviders, filterSessions } from '@/features/ai/ai-console-utils'
import type { AgentDefinitionDTO, AgentModelDTO, AgentProviderDTO } from '@/shared/api/contracts'
import { useAiConsoleResourceController } from '@/features/ai/useAiConsoleResourceController'
import { useAiConsoleSessionController } from '@/features/ai/useAiConsoleSessionController'

export function useAiConsoleController() {
  const [search, setSearch] = useState('')
  const deferredSearch = useDeferredValue(search.trim().toLowerCase())

  const resourceController = useAiConsoleResourceController()
  const sessionController = useAiConsoleSessionController(resourceController.agents)

  const agentsById = useMemo(() => new Map(resourceController.agents.map((agent) => [String(agent.id), agent])), [resourceController.agents])
  const filteredSessions = useMemo(
    () => filterSessions(sessionController.sessions, agentsById, deferredSearch),
    [agentsById, deferredSearch, sessionController.sessions],
  )
  const filteredAgents = useMemo(() => filterAgents(resourceController.agents, deferredSearch), [deferredSearch, resourceController.agents])
  const filteredModels = useMemo(() => filterModels(resourceController.models, deferredSearch), [deferredSearch, resourceController.models])
  const filteredProviders = useMemo(() => filterProviders(resourceController.providers, deferredSearch), [deferredSearch, resourceController.providers])

  const queryResults = [
    resourceController.providersQuery,
    resourceController.modelsQuery,
    resourceController.agentsQuery,
    sessionController.sessionsQuery,
  ]
  const mutationErrors = [resourceController.resourceMutationError, sessionController.sessionMutationError]

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
      sessions: filteredSessions,
      agentsById,
      onCreate: () => sessionController.openCreateSession(),
    },
    agentPanelProps: {
      agents: filteredAgents,
      deletePending: resourceController.agentDeletePending,
      onCreate: resourceController.openCreateAgent,
      onStart: (agent: AgentDefinitionDTO) => sessionController.openCreateSession(String(agent.id)),
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
    createSessionModal: sessionController.createSessionModal,
    resourceEditorModal: resourceController.resourceEditorModal,
    resourceDeleteConfirmModal: resourceController.deleteConfirmModal,
  }
}
