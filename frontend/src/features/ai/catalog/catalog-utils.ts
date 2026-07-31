import { modelRef, type AgentModelView } from '@/features/ai/catalog/AgentModelView'
import { includesSearch, naturalNameCompare } from '@/shared/lib/search-utils'
import type {
  AgentDefinitionDTO,
  AgentProviderDTO,
} from '@/shared/api/contracts/ai-catalog'
import type { ResourceModal } from '@/features/ai/catalog/ai-console-types'
import { translate } from '@/shared/i18n'

export function resourceTitle(modal: ResourceModal): string {
  const prefix = modal.mode === 'create'
    ? translate('ai.catalog.createPrefix')
    : translate('ai.catalog.editPrefix')
  if (modal.kind === 'provider') {
    return `${prefix} ${translate('ai.nav.providers')}`
  }
  if (modal.kind === 'model') {
    return `${prefix} ${translate('ai.nav.models')}`
  }
  return `${prefix} ${translate('ai.nav.agents')}`
}

export function filterAgents(agents: AgentDefinitionDTO[], search: string): AgentDefinitionDTO[] {
  return agents
    .filter((agent) => includesSearch(agent.name, search))
    .sort((left, right) => naturalNameCompare(left.name, right.name))
}

export function filterModels(models: AgentModelView[], search: string): AgentModelView[] {
  return models
    .filter((model) => includesSearch(modelRef(model), search))
    .sort((left, right) => naturalNameCompare(modelRef(left), modelRef(right)))
}

export function filterProviders(
  providers: AgentProviderDTO[],
  search: string,
): AgentProviderDTO[] {
  return providers
    .filter((provider) => includesSearch(provider.name, search))
    .sort((left, right) => naturalNameCompare(left.name, right.name))
}
