import { useDeferredValue, useMemo, useState } from 'react'
import { filterAgents, filterModels, filterProviders, filterSessions } from '@/features/ai/ai-console-utils'
import type { AgentDefinitionDTO, AgentModelDTO, AgentProviderDTO } from '@/shared/api/contracts'
import { useAiConsoleResourceController } from '@/features/ai/useAiConsoleResourceController'
import { useAiConsoleSessionController } from '@/features/ai/useAiConsoleSessionController'

export function useAiConsoleController(workspaceId: string) {
  const [search, setSearch] = useState('')
  const deferredSearch = useDeferredValue(search.trim().toLowerCase())

  const resourceController = useAiConsoleResourceController(workspaceId)
  const sessionController = useAiConsoleSessionController(workspaceId, resourceController.agents)

  const agentsByName = useMemo(() => new Map(resourceController.agents.map((agent) => [agent.name, agent])), [resourceController.agents])
  const filteredSessions = useMemo(
    () => filterSessions(sessionController.sessions, agentsByName, deferredSearch),
    [agentsByName, deferredSearch, sessionController.sessions],
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
      workspaceId,
      sessions: filteredSessions,
      agentsByName,
      deletePending: sessionController.sessionDeletePending,
      onCreate: () => sessionController.openCreateSession(),
      onEdit: sessionController.openEditSession,
      onDelete: sessionController.deleteSession,
    },
    agentPanelProps: {
      agents: filteredAgents,
      deletePending: resourceController.agentDeletePending,
      onCreate: resourceController.openCreateAgent,
      onStart: (agent: AgentDefinitionDTO) => sessionController.openCreateSession(agent.name),
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
    editSessionModal: sessionController.editSessionModal,
    sessionDeleteConfirmModal: sessionController.deleteConfirmModal,
    resourceEditorModal: resourceController.resourceEditorModal,
    resourceDeleteConfirmModal: resourceController.deleteConfirmModal,
  }
}
