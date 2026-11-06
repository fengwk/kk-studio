import { useDeferredValue, useMemo, useState } from 'react'
import { toUserFacingErrorMessage } from '@/shared/lib/user-facing-error'
import {
  filterAgents,
  filterModels,
  filterProviders,
} from '@/features/catalog/catalog-utils'
import type { AgentModelView } from '@/features/catalog/AgentModelView'
import { useAiConsoleResourceController } from '@/features/catalog/useAiConsoleResourceController'
import type { AiConsoleResourceQueryEnabled } from '@/features/catalog/useAiConsoleResourceQueries'
import { useI18n } from '@/shared/i18n'
import type { AgentDTO, ProviderDTO } from '@/shared/api/generated/contracts'

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
  const { queries, resources, deletePending, editorActions, deleteResource } = resourceController
  const agents = useMemo(
    () => filterAgents(resources.agents, deferredSearch),
    [deferredSearch, resources.agents],
  )
  const models = useMemo(
    () => filterModels(resources.models, deferredSearch),
    [deferredSearch, resources.models],
  )
  const providers = useMemo(
    () => filterProviders(resources.providers, deferredSearch),
    [deferredSearch, resources.providers],
  )
  const queryResults = [
    ...(resourceQueryEnabled.providers ? [queries.providers] : []),
    ...(resourceQueryEnabled.models ? [queries.models] : []),
    ...(resourceQueryEnabled.agents ? [queries.agents] : []),
    ...(resourceQueryEnabled.tools ? [queries.tools] : []),
    ...(resourceQueryEnabled.skills ? [queries.skills] : []),
    ...(resourceQueryEnabled.environments ? [queries.environments] : []),
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
      models: resources.models,
      deletePending: deletePending.agent,
      onCreate: editorActions.createAgent,
      onEdit: (agent: AgentDTO) => editorActions.editAgent(agent.name),
      onDelete: (agent: AgentDTO) =>
        deleteResource.agent(agent.name, agent.version),
    },
    modelPanelProps: {
      models,
      deletePending: deletePending.model,
      onCreate: editorActions.createModel,
      onEdit: (model: AgentModelView) => editorActions.editModel(model.name),
      onDelete: (model: AgentModelView) =>
        deleteResource.model(model.name, model.version),
    },
    providerPanelProps: {
      providers,
      deletePending: deletePending.provider,
      onCreate: editorActions.createProvider,
      onEdit: (provider: ProviderDTO) => editorActions.editProvider(provider.name),
      onDelete: (provider: ProviderDTO) =>
        deleteResource.provider(provider.name, provider.version),
    },
    resourceEditorModal: resourceController.resourceEditorModal,
    resourceDeleteConfirmModal: resourceController.deleteConfirmModal,
  }
}

export type CatalogPageController = ReturnType<typeof useCatalogPageController>
