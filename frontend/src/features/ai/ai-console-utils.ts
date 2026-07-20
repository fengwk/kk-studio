import { extractDefaultVariantFromModel } from '@/features/ai/ai-model-draft-codec'
import type {
  AgentDefinitionDTO,
  AgentModelWithProviderDTO,
  AgentProviderDTO,
  BackendDateTime,
} from '@/shared/api/contracts'
import type { ResourceModal } from '@/features/ai/ai-console-types'

export function resourceTitle(modal: ResourceModal): string {
  const prefix = modal.mode === 'create' ? '新建' : '编辑'
  if (modal.kind === 'provider') {
    return `${prefix} Provider`
  }
  if (modal.kind === 'model') {
    return `${prefix} Model`
  }
  return `${prefix} Agent`
}

export function filterAgents(agents: AgentDefinitionDTO[], search: string): AgentDefinitionDTO[] {
  return agents.filter((agent) =>
    includesSearch(
      `${agent.name} ${agent.description ?? ''} ${agent.modelId ?? ''} ${agent.variant ?? ''} ${agent.config?.environmentName ?? ''}`,
      search,
    ),
  )
}

export function filterModels(models: AgentModelWithProviderDTO[], search: string): AgentModelWithProviderDTO[] {
  return models.filter((model) =>
    includesSearch(
      `${model.providerName ?? ''} ${model.name} ${model.description ?? ''} ${extractDefaultVariantFromModel(model)}`,
      search,
    ),
  )
}

export function filterProviders(providers: AgentProviderDTO[], search: string): AgentProviderDTO[] {
  return providers.filter((provider) => includesSearch(`${provider.name} ${provider.description ?? ''} ${provider.providerType} ${provider.baseUrl ?? ''}`, search))
}

export function includesSearch(value: string, search: string): boolean {
  return !search || value.toLowerCase().includes(search)
}

export function formatBackendDate(value: BackendDateTime): string {
  if (!value) {
    return '-'
  }
  if (Array.isArray(value)) {
    const [year, month = 1, day = 1, hour = 0, minute = 0] = value
    if (!Number.isFinite(year)) {
      return '-'
    }
    return `${String(year).padStart(4, '0')}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')} ${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`
  }
  return value.replace('T', ' ').slice(0, 16)
}
