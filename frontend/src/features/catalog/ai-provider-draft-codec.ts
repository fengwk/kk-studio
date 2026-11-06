import type { ProviderDraft } from '@/features/catalog/ai-console-types'
import { numberToNull, trimToNull } from '@/features/catalog/ai-resource-draft-primitives'
import { assembleHttpStatusCodeList } from '@/shared/lib/integer-list'
import type {
  ProviderCreateDTO,
  ProviderDTO,
  ProviderUpdateDTO,
} from '@/shared/api/generated/contracts'

export function emptyProviderDraft(): ProviderDraft {
  return {
    name: '',
    description: '',
    providerType: 'openai',
    baseUrl: '',
    credential: '',
    modelCallTimeoutMillis: '1800000',
    modelCallIdleTimeoutMillis: '120000',
    modelHttpRetryStatusCodes: null,
  }
}

export function toProviderDraft(provider: ProviderDTO): ProviderDraft {
  return {
    name: provider.name,
    description: provider.description || '',
    providerType: provider.providerType,
    baseUrl: provider.baseUrl || '',
    credential: '',
    modelCallTimeoutMillis: String(provider.modelCallTimeoutMillis),
    modelCallIdleTimeoutMillis: String(provider.modelCallIdleTimeoutMillis),
    modelHttpRetryStatusCodes:
      provider.modelHttpRetryStatusCodes == null
        ? null
        : [...provider.modelHttpRetryStatusCodes],
  }
}

export function toEditableProvider(draft: ProviderDraft): ProviderCreateDTO {
  return {
    name: draft.name.trim(),
    description: trimToNull(draft.description),
    providerType: draft.providerType,
    baseUrl: trimToNull(draft.baseUrl),
    credential: trimToNull(draft.credential),
    modelCallTimeoutMillis: numberToNull(draft.modelCallTimeoutMillis),
    modelCallIdleTimeoutMillis: numberToNull(draft.modelCallIdleTimeoutMillis),
    modelHttpRetryStatusCodes:
      draft.modelHttpRetryStatusCodes === null
        ? null
        : (Array.isArray(draft.modelHttpRetryStatusCodes)
            ? assembleHttpStatusCodeList(draft.modelHttpRetryStatusCodes)
            : null),
  }
}

export function toEditableProviderUpdate(
  draft: ProviderDraft,
): Omit<ProviderUpdateDTO, 'expectedVersion'> {
  const credential = trimToNull(draft.credential)
  const modelCallTimeoutMillis = numberToNull(draft.modelCallTimeoutMillis)
  const modelCallIdleTimeoutMillis = numberToNull(draft.modelCallIdleTimeoutMillis)
  if (modelCallTimeoutMillis === null || modelCallIdleTimeoutMillis === null) {
    throw new Error('provider timeouts are required')
  }
  return {
    description: trimToNull(draft.description),
    baseUrl: trimToNull(draft.baseUrl),
    credential:
      credential === null ? { operation: 'KEEP' } : { operation: 'SET', value: credential },
    modelCallTimeoutMillis,
    modelCallIdleTimeoutMillis,
    modelHttpRetryStatusCodes:
      draft.modelHttpRetryStatusCodes === null
        ? null
        : assembleHttpStatusCodeList(draft.modelHttpRetryStatusCodes),
  }
}
