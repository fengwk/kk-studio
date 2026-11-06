import type {
  AgentUpdateDTO,
  PromptCacheRetention,
  ProviderDTO,
  ProviderType,
} from '@/shared/api/generated/contracts'
import { PROVIDER_TYPE_VALUES } from '@/shared/api/generated/contracts'

export type { PromptCacheRetention } from '@/shared/api/generated/contracts'

/** Provider wire 枚举的唯一来源；表单与草稿直接复用生成值。 */
export const AGENT_PROVIDER_TYPES = PROVIDER_TYPE_VALUES
export type AgentProviderType = ProviderType

export function isAgentProviderType(value: string): value is AgentProviderType {
  return (AGENT_PROVIDER_TYPES as readonly string[]).includes(value)
}

/** 模型联合的判别值；仅用于界面分支，wire 字段名是 `type`。 */
export type ModelKind = 'MODEL' | 'GROUP'

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

/** 更新请求体去掉乐观锁版本后的可编辑部分；编辑表单整体替换这些字段。 */
export type AgentEditablePropertiesDTO = Omit<AgentUpdateDTO, 'expectedVersion'>
