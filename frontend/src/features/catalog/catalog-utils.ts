import type { AgentModelView } from '@/features/catalog/AgentModelView'
import { includesSearch, naturalNameCompare } from '@/shared/lib/search-utils'
import type { ResourceModal } from '@/features/catalog/ai-console-types'
import { translate } from '@/shared/i18n'
import type { AgentDefinitionDTO, AgentProviderDTO } from '@/shared/api/generated/contracts'

export function resourceTitle(modal: ResourceModal): string {
  const prefix = modal.mode === 'create'
    ? translate('ai.catalog.createPrefix')
    : translate('ai.catalog.editPrefix')
  if (modal.kind === 'provider') {
    return `${prefix} ${translate('ai.catalog.resourceType.provider')}`
  }
  if (modal.kind === 'model') {
    return `${prefix} ${
      modal.resourceKind === 'GROUP'
        ? translate('ai.catalog.resourceType.modelGroup')
        : translate('ai.catalog.resourceType.model')
    }`
  }
  return `${prefix} ${translate('ai.catalog.resourceType.agent')}`
}

export function filterAgents(agents: AgentDefinitionDTO[], search: string): AgentDefinitionDTO[] {
  return agents
    .filter((agent) => includesSearch(agent.name, search))
    .sort((left, right) => naturalNameCompare(left.name, right.name))
}

export function filterModels(models: AgentModelView[], search: string): AgentModelView[] {
  return models
    .filter((model) => includesSearch(model.name, search))
    .sort((left, right) => naturalNameCompare(left.name, right.name))
}

export function filterProviders(
  providers: AgentProviderDTO[],
  search: string,
): AgentProviderDTO[] {
  return providers
    .filter((provider) => includesSearch(provider.name, search))
    .sort((left, right) => naturalNameCompare(left.name, right.name))
}
