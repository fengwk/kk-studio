import type {
  AgentProviderCreateDTO,
  AgentProviderDTO,
  AgentProviderEditablePropertiesDTO,
  AgentProviderType,
} from '@/shared/api/contracts/ai-catalog'
import type { ProviderDraft } from '@/features/ai/catalog/ai-console-types'
import { numberToNull, trimToNull } from '@/features/ai/catalog/ai-resource-draft-primitives'

export function emptyProviderDraft(): ProviderDraft {
  return {
    name: '',
    description: '',
    providerType: 'openai',
    baseUrl: '',
    credential: '',
    modelCallTimeoutMillis: '1800000',
    modelCallIdleTimeoutMillis: '120000',
  }
}

export function toProviderDraft(provider: AgentProviderDTO): ProviderDraft {
  return {
    name: provider.name,
    description: provider.description || '',
    providerType: provider.providerType || 'openai',
    baseUrl: provider.baseUrl || '',
    credential: '',
    modelCallTimeoutMillis: String(provider.modelCallTimeoutMillis),
    modelCallIdleTimeoutMillis: String(provider.modelCallIdleTimeoutMillis),
  }
}

export function toEditableProvider(draft: ProviderDraft): AgentProviderCreateDTO {
  return {
    name: draft.name.trim(),
    description: trimToNull(draft.description),
    providerType: draft.providerType.trim() as AgentProviderType,
    baseUrl: trimToNull(draft.baseUrl),
    credential: trimToNull(draft.credential),
    modelCallTimeoutMillis: numberToNull(draft.modelCallTimeoutMillis),
    modelCallIdleTimeoutMillis: numberToNull(draft.modelCallIdleTimeoutMillis),
  }
}

export function toEditableProviderUpdate(draft: ProviderDraft): AgentProviderEditablePropertiesDTO {
  return {
    description: trimToNull(draft.description),
    providerType: draft.providerType.trim() as AgentProviderType,
    baseUrl: trimToNull(draft.baseUrl),
    credential: trimToNull(draft.credential),
    modelCallTimeoutMillis: numberToNull(draft.modelCallTimeoutMillis),
    modelCallIdleTimeoutMillis: numberToNull(draft.modelCallIdleTimeoutMillis),
  }
}
