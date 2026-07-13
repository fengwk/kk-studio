import type { AgentProviderCreateDTO, AgentProviderDTO, AgentProviderUpdateDTO } from '@/shared/api/contracts'
import type { ProviderDraft } from '@/features/ai/ai-console-types'
import { numberToNull, trimToNull } from '@/features/ai/ai-resource-draft-primitives'

export function emptyProviderDraft(): ProviderDraft {
  return {
    name: '',
    description: '',
    providerType: 'openai',
    baseUrl: '',
    apiKey: '',
    timeoutMillis: '60000',
  }
}

export function toProviderDraft(provider: AgentProviderDTO): ProviderDraft {
  return {
    name: provider.name,
    description: provider.description || '',
    providerType: provider.providerType || 'openai',
    baseUrl: provider.baseUrl || '',
    apiKey: provider.apiKey || '',
    timeoutMillis: provider.timeoutMillis ? String(provider.timeoutMillis) : '',
  }
}

export function toEditableProvider(draft: ProviderDraft): AgentProviderCreateDTO {
  return {
    name: draft.name.trim(),
    description: trimToNull(draft.description),
    providerType: draft.providerType.trim(),
    baseUrl: trimToNull(draft.baseUrl),
    apiKey: trimToNull(draft.apiKey),
    timeoutMillis: numberToNull(draft.timeoutMillis),
  }
}

export function toEditableProviderUpdate(draft: ProviderDraft): AgentProviderUpdateDTO {
  return toEditableProvider(draft)
}
