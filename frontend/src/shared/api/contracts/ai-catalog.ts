import type { PromptCacheRetention, ProviderDTO } from '@/shared/api/generated/contracts'

export type { PromptCacheRetention } from '@/shared/api/generated/contracts'

/**
 * 依据 Provider 只读能力取得支持的缓存档位列表。
 * 严格只从 provider.promptCacheRetentions 获取；尚未选择 Provider 时不提供选项。
 */
export function resolvePromptCacheRetentions(
  provider?: Pick<ProviderDTO, 'promptCacheRetentions'> | null,
): readonly PromptCacheRetention[] {
  return provider?.promptCacheRetentions ?? []
}

/**
 * 新建 Variant 时的预填档位：协议支持 SHORT 就预填 SHORT，否则 NONE。
 */
export function defaultPromptCacheRetention(
  provider?: Pick<ProviderDTO, 'promptCacheRetentions'> | null,
): PromptCacheRetention {
  const supported = resolvePromptCacheRetentions(provider)
  return supported.includes('SHORT') ? 'SHORT' : 'NONE'
}
