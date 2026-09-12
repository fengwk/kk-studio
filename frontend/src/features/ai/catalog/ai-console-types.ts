import type {
  AgentModelInputModality,
  AgentProviderType,
} from '@/shared/api/contracts/ai-catalog'
import type { CatalogVersion } from '@/shared/api/contracts/base'

export interface ProviderDraft {
  name: string
  description: string
  providerType: AgentProviderType
  baseUrl: string
  credential: string
  modelCallTimeoutMillis: string
  modelCallIdleTimeoutMillis: string
}

/**
 * 一个 Agent model 变体。表单输入使用字符串类型，以便空字段能干净地映射到 wire {@link AgentModelVariantDTO}
 * 上的 {@code null}/{@code undefined}。
 */
export interface VariantDraft {
  /** 仅客户端使用的 React key。 */
  draftId: string
  id: string
  /** 合法取值 high/medium/low/off；空串表示协议默认。 */
  reasoningEffort: string
}

/**
 * 表单传递的每百万 token 价格。共享的 {@code config.pricing} 子结构在 wire 边界承载币种 / 档位元数据；
 * 表单仅编辑每个单位的价格。
 */
export interface ModelPricingDraft {
  currency: string
  pricingTier: string
  serviceTier: string
  serviceTierMultiplier: string
  version: string
  inputPerMillionTokens: string
  outputPerMillionTokens: string
  cacheReadPerMillionTokens: string
  cacheWritePerMillionTokens: string
  cacheWriteLongPerMillionTokens: string
  reasoningPerMillionTokens: string
}

/** model 资源的表单草稿。输入保持字符串类型以获得可预测的编辑体验。 */
export interface ModelDraft {
  providerName: string
  /** 不可变的模型逻辑名。 */
  name: string
  /** 发往上游 Provider 的真实模型标识；可编辑且必填。 */
  modelId: string
  description: string
  contextWindow: string
  maxOutputTokens: string
  tools: boolean
  reasoning: boolean
  /**
   * 提交时非空。新建 model 默认始终包含 {@code TEXT}；切换时通过不可变数组新增/移除项（不进行原地变更）。
   */
  inputModalities: AgentModelInputModality[]
  defaultVariant: string
  variants: VariantDraft[]
  pricing: ModelPricingDraft
}

export interface AgentDraft {
  name: string
  description: string
  systemPrompt: string
  model: string
  variant: string
  environmentId: string
  toolIds: string[]
  skills: string[]
  subagents: string[]
}

export type ResourceModal =
  | { kind: 'provider'; mode: 'create' }
  | { kind: 'provider'; mode: 'edit'; name: string; expectedVersion: CatalogVersion }
  | { kind: 'model'; mode: 'create' }
  | {
      kind: 'model'
      mode: 'edit'
      providerName: string
      name: string
      expectedVersion: CatalogVersion
    }
  | { kind: 'agent'; mode: 'create' }
  | {
      kind: 'agent'
      mode: 'edit'
      name: string
      model: string
      expectedVersion: CatalogVersion
    }

export const providerTypes = ['openai', 'openai_response', 'anthropic', 'google'] as const satisfies
  readonly AgentProviderType[]
