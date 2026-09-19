import { useDeferredValue, useMemo, useState } from 'react'
import { toUserFacingErrorMessage } from '@/features/ai/ai-user-facing-error'
import {
  filterAgents,
  filterModels,
  filterProviders,
} from '@/features/ai/catalog/catalog-utils'
import type { AgentModelView } from '@/features/ai/catalog/AgentModelView'
import { useAiConsoleResourceController } from '@/features/ai/catalog/useAiConsoleResourceController'
import type { AiConsoleResourceQueryEnabled } from '@/features/ai/catalog/useAiConsoleResourceQueries'
import type {
  AgentDefinitionDTO,
  AgentProviderDTO,
} from '@/shared/api/contracts/ai-catalog'
import { useI18n } from '@/shared/i18n'

export type CatalogPageScope = 'agents' | 'models' | 'providers'

const resourceQueryEnabledByScope: Record<
  CatalogPageScope,
  AiConsoleResourceQueryEnabled
> = {
  agents: {
    providers: true,
    models: true,
    agents: true,
    tools: true,
    skills: true,
    environments: false,
  },
  models: {
    providers: true,
    models: true,
    agents: false,
    tools: false,
    skills: false,
    environments: false,
  },
  providers: {
    providers: true,
    models: false,
    agents: false,
    tools: false,
    skills: false,
    environments: false,
  },
}

export function useCatalogPageController(scope: CatalogPageScope) {
  const { locale } = useI18n()
  const [search, setSearch] = useState('')
  const deferredSearch = useDeferredValue(search.trim().toLowerCase())
  const resourceQueryEnabled = resourceQueryEnabledByScope[scope]
  const resourceController = useAiConsoleResourceController(resourceQueryEnabled)
  const agents = useMemo(
    () => filterAgents(resourceController.agents, deferredSearch),
    [deferredSearch, resourceController.agents],
  )
  const models = useMemo(
    () => filterModels(resourceController.models, deferredSearch),
    [deferredSearch, resourceController.models],
  )
  const providers = useMemo(
    () => filterProviders(resourceController.providers, deferredSearch),
    [deferredSearch, resourceController.providers],
  )
  const queryResults = [
    ...(resourceQueryEnabled.providers ? [resourceController.providersQuery] : []),
    ...(resourceQueryEnabled.models ? [resourceController.modelsQuery] : []),
    ...(resourceQueryEnabled.agents ? [resourceController.agentsQuery] : []),
    ...(resourceQueryEnabled.tools ? [resourceController.toolsQuery] : []),
    ...(resourceQueryEnabled.skills ? [resourceController.skillsQuery] : []),
    ...(resourceQueryEnabled.environments
      ? [resourceController.environmentsQuery]
      : []),
  ]
  const resourceModalOpen = Boolean(resourceController.resourceEditorModal.modal)
  const rawMutationError = resourceModalOpen
    ? null
    : resourceController.resourceMutationError
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
    busy: queryResults.some((query) => query.isLoading),
    error: queryResults.find((query) => query.error)?.error ?? null,
    mutationError,
    agentPanelProps: {
      agents,
      models: resourceController.models,
      deletePending: resourceController.agentDeletePending,
      onCreate: resourceController.openCreateAgent,
      onEdit: (agent: AgentDefinitionDTO) =>
        resourceController.openEditAgent(agent.name),
      onDelete: (agent: AgentDefinitionDTO) =>
        resourceController.deleteAgent(agent.name, agent.version),
    },
    modelPanelProps: {
      models,
      deletePending: resourceController.modelDeletePending,
      onCreate: resourceController.openCreateModel,
      onEdit: (model: AgentModelView) =>
        resourceController.openEditModel(model.providerName, model.name),
      onDelete: (model: AgentModelView) =>
        resourceController.deleteModel(
          model.providerName,
          model.name,
          model.version,
        ),
    },
    providerPanelProps: {
      providers,
      deletePending: resourceController.providerDeletePending,
      onCreate: resourceController.openCreateProvider,
      onEdit: (provider: AgentProviderDTO) =>
        resourceController.openEditProvider(provider.name),
      onDelete: (provider: AgentProviderDTO) =>
        resourceController.deleteProvider(
          provider.name,
          provider.version,
        ),
    },
    resourceEditorModal: resourceController.resourceEditorModal,
    resourceDeleteConfirmModal: resourceController.deleteConfirmModal,
  }
}

export type CatalogPageController = ReturnType<typeof useCatalogPageController>
