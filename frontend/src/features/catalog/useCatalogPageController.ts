import { useDeferredValue, useMemo, useState } from 'react'
import { toUserFacingErrorMessage } from '@/shared/lib/user-facing-error'
import {
  filterAgents,
  filterModels,
  filterProviders,
} from '@/features/catalog/catalog-utils'
import { useAiConsoleResourceController } from '@/features/catalog/useAiConsoleResourceController'
import type { AiConsoleResourceQueryEnabled } from '@/features/catalog/useAiConsoleResourceQueries'
import { useI18n } from '@/shared/i18n'
import type { AgentDTO, ModelDTO, ProviderDTO } from '@/shared/api/generated/contracts'

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
  /** 页面级加载/失败只由当前作用域的主列表决定；附属目录失败由对应面板就地降级。 */
  const primaryQuery = queryByScope(scope, queries)
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
    busy: primaryQuery.isLoading,
    error: primaryQuery.error ?? null,
    retry: () => {
      void primaryQuery.refetch()
    },
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
      onEdit: (model: ModelDTO) => editorActions.editModel(model.name),
      onDelete: (model: ModelDTO) =>
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

/** 作用域对应的主列表查询：页面栅格直接渲染的那一份资源。 */
function queryByScope(
  scope: CatalogPageScope,
  queries: ReturnType<typeof useAiConsoleResourceController>['queries'],
) {
  if (scope === 'agents') {
    return queries.agents
  }
  return scope === 'models' ? queries.models : queries.providers
}
