import type { ProviderDraft } from '@/features/catalog/ai-console-types'
import { trimToNull } from '@/features/catalog/ai-resource-draft-primitives'
import { assembleHttpStatusCodeList } from '@/shared/lib/integer-list'
import type {
  ProviderCreateDTO,
  ProviderDTO,
  ProviderUpdateDTO,
} from '@/shared/api/generated/contracts'

function timeoutToNull(value: string): string | null {
  const text = trimToNull(value)
  if (text === null) return null
  if (!/^(0|[1-9][0-9]*)$/.test(text) || BigInt(text) > 9223372036854775807n) {
    throw new Error('provider timeout must be a canonical non-negative integer')
  }
  return text
}

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
  const credential = trimToNull(draft.credential)
  return {
    name: draft.name.trim(),
    description: trimToNull(draft.description),
    providerType: draft.providerType,
    baseUrl: trimToNull(draft.baseUrl),
    // 创建只接受 SET：空凭据就是“不配置”，不存在需要保留或清除的既有值。
    credential: credential === null ? null : { operation: 'SET', value: credential },
    modelCallTimeoutMillis: timeoutToNull(draft.modelCallTimeoutMillis),
    modelCallIdleTimeoutMillis: timeoutToNull(draft.modelCallIdleTimeoutMillis),
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
  const modelCallTimeoutMillis = timeoutToNull(draft.modelCallTimeoutMillis)
  const modelCallIdleTimeoutMillis = timeoutToNull(draft.modelCallIdleTimeoutMillis)
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
