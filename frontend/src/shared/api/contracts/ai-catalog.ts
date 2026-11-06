import type {
  AgentUpdateDTO,
  AgentModelUpdateDTO,
  AgentProviderDTO,
  AgentProviderUpdateDTO,
  PromptCacheRetention,
} from '@/shared/api/generated/contracts'

export type { PromptCacheRetention } from '@/shared/api/generated/contracts'

/** Provider 类型的稳定 wire 值；DTO 声明为字符串，表单与草稿按这里收窄。 */
export const AGENT_PROVIDER_TYPES = ['openai', 'openai_responses', 'anthropic', 'google'] as const
export type AgentProviderType = (typeof AGENT_PROVIDER_TYPES)[number]

export function isAgentProviderType(value: string): value is AgentProviderType {
  return (AGENT_PROVIDER_TYPES as readonly string[]).includes(value)
}

/**
 * 依据 Provider 只读能力取得支持的缓存档位列表。
 * 严格只从 provider.promptCacheRetentions 获取；尚未选择 Provider 时不提供选项。
 */
export function resolvePromptCacheRetentions(
  provider?: Pick<AgentProviderDTO, 'promptCacheRetentions'> | null,
): readonly PromptCacheRetention[] {
  return provider?.promptCacheRetentions ?? []
}

/**
 * 新建 Variant 时的预填档位：协议支持 SHORT 就预填 SHORT，否则 NONE。
 */
export function defaultPromptCacheRetention(
  provider?: Pick<AgentProviderDTO, 'promptCacheRetentions'> | null,
): PromptCacheRetention {
  const supported = resolvePromptCacheRetentions(provider)
  return supported.includes('SHORT') ? 'SHORT' : 'NONE'
}

/** 更新请求体去掉乐观锁版本后的可编辑部分；编辑表单整体替换这些字段。 */
export type AgentProviderEditablePropertiesDTO = Omit<AgentProviderUpdateDTO, 'expectedVersion'>
export type AgentModelEditablePropertiesDTO = Omit<AgentModelUpdateDTO, 'expectedVersion'>
export type AgentEditablePropertiesDTO = Omit<AgentUpdateDTO, 'expectedVersion'>
