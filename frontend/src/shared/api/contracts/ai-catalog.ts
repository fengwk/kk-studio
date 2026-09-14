import type { BackendLong, CatalogVersion, InstantTimestamp } from '@/shared/api/contracts/base'

/** Provider Catalog / HTTP 使用的稳定 wire 值。 */
export type AgentProviderType = 'openai' | 'openai_response' | 'anthropic' | 'google'

export interface AgentProviderDTO {
  name: string
  description: string | null
  providerType: AgentProviderType
  baseUrl: string | null
  configured: boolean
  modelCallTimeoutMillis: BackendLong
  modelCallIdleTimeoutMillis: BackendLong
  version: CatalogVersion
  createTime: InstantTimestamp
  updateTime: InstantTimestamp
}

export interface AgentProviderEditablePropertiesDTO {
  name?: string | null
  description?: string | null
  providerType: AgentProviderType
  baseUrl?: string | null
  credential?: string | null
  modelCallTimeoutMillis?: BackendLong | null
  modelCallIdleTimeoutMillis?: BackendLong | null
}

export interface AgentProviderCreateDTO extends AgentProviderEditablePropertiesDTO {
  name: string
}

export interface AgentProviderUpdateDTO extends AgentProviderEditablePropertiesDTO {
  expectedVersion: CatalogVersion
}

/** 与后端 {@code AgentModelInputModality} 对应的稳定枚举。 */
export type AgentModelInputModality =
  | 'TEXT'
  | 'IMAGE'
  | 'AUDIO'
  | 'VIDEO'
  | 'DOCUMENT'

interface AgentModelLimitDTO {
  /** 模型上下文窗口的 token 数量（正整数）。 */
  context: number
  /** 正整数，且 {@code <= context}。 */
  output: number
}

interface AgentModelAbilitiesDTO {
  tools: boolean
  reasoning: boolean
  /** {@link AgentModelInputModality} 的非空子集。 */
  inputModalities: AgentModelInputModality[]
}

export interface AgentModelPricingDTO {
  currency: string
  pricingTier: string
  serviceTier: string
  serviceTierMultiplier: number | string
  version: string
  inputPerMillionTokens: number | string
  outputPerMillionTokens: number | string
  cacheReadPerMillionTokens: number | string
  cacheWritePerMillionTokens: number | string
  cacheWriteLongPerMillionTokens: number | string
  reasoningPerMillionTokens: number | string
}

/** Variant 只表达 reasoning effort；`off` 为显式关闭，缺省表示协议默认。 */
export interface AgentModelVariantDTO {
  id: string
  reasoningEffort?: string | null
}

/**
 * 结构化的 Agent model 配置。传输时每个子结构都是必需的；缺失或格式错误的配置必须由后端拒绝，
 * 而不是静默修复。
 */
export interface AgentModelConfigDTO {
  limit: AgentModelLimitDTO
  abilities: AgentModelAbilitiesDTO
  pricing: AgentModelPricingDTO
  defaultVariant: string
  variants: AgentModelVariantDTO[]
}

/** 携带单一结构化可执行配置的公开 Agent model 资源。 */
export interface AgentModelDTO {
  providerName: string
  /** 模型逻辑名。 */
  name: string
  /** 发往上游 Provider 的真实模型标识；与 name 独立且不唯一。 */
  modelId: string
  description: string | null
  config: AgentModelConfigDTO
  version: CatalogVersion
  createTime: InstantTimestamp
  updateTime: InstantTimestamp
}

/**
 * Agent model 的可编辑部分。前端发送完整替换：发出请求体时 {@code name}、
 * {@code modelId}、{@code description} 与 {@code config} 均为必需字段。
 */
export interface AgentModelEditablePropertiesDTO {
  /** 模型逻辑名。 */
  name: string
  /** 发往上游 Provider 的真实模型标识。 */
  modelId: string
  description: string | null
  config: AgentModelConfigDTO
}

export interface AgentModelCreateDTO extends AgentModelEditablePropertiesDTO {
  providerName: string
}

export interface AgentModelUpdateDTO extends AgentModelEditablePropertiesDTO {
  expectedVersion: CatalogVersion
}

export interface AgentSkillRefDTO {
  sourceId: string
  name: string
}

export interface AgentDefinitionConfigDTO {
  /** Stable AgentToolId values selected from the tool catalog. */
  toolIds: string[]
  skills: AgentSkillRefDTO[]
  /** 可通过 task 委派的 Agent 名称 allowlist；只接受短名。 */
  subagents: string[]
}

/** 可离线选择的统一运行时 tool catalog 条目。 */
export interface ToolCatalogEntryDTO {
  id: string
  name: string
  version: string
  description: string
  environmentRequired: boolean
  environmentId: string | null
}

/** 公开的全局 Agent definition；model/variant 与 config 是 Thread 运行时的输入。 */
export interface AgentDefinitionDTO {
  name: string
  description: string | null
  systemPrompt: string | null
  model: string
  /** 可选覆盖；为 null 表示使用所选 Model 的 defaultVariant。 */
  variant: string | null
  /** 绑定的 Environment UUID 字符串（可空；null 表示未绑定环境）。 */
  environmentId: string | null
  config: AgentDefinitionConfigDTO
  version: CatalogVersion
  createTime: InstantTimestamp
  updateTime: InstantTimestamp
}

/** 完整的 Agent Definition 创建/PUT 请求体。 */
export interface AgentDefinitionEditablePropertiesDTO {
  description: string | null
  systemPrompt: string | null
  /** 必填模型引用，序列化形式为 {@code providerName/modelName}。 */
  model: string
  /** 可选覆盖；为 null 表示使用所选 Model 的 defaultVariant。 */
  variant: string | null
  /** 可空绑定的 Environment UUID 字符串（null/空白表示清除环境绑定）。 */
  environmentId?: string | null
  config: AgentDefinitionConfigDTO
}

export interface AgentDefinitionCreateDTO extends AgentDefinitionEditablePropertiesDTO {
  name: string
}

export interface AgentDefinitionUpdateDTO extends AgentDefinitionEditablePropertiesDTO {
  expectedVersion: CatalogVersion
}
