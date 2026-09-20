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

export interface SkillRefDTO {
  /** Package 名（不可变路由身份）。 */
  packageName: string
  /** Package 内的 Skill 名。 */
  name: string
}

export interface SkillManifestEntryDTO {
  /** Skill 名。 */
  name: string
  /** Skill 描述。 */
  description: string
}

export type SkillPackageCheckStatus =
  | 'UNCHECKED'
  | 'UP_TO_DATE'
  | 'UPDATE_AVAILABLE'
  | 'CHECK_FAILED'

export interface SkillPackageDTO {
  packageName: string
  description: string | null
  repositoryUrl: string
  branch: string
  currentCommit: string
  observedHeadCommit: string | null
  headCheckedAt: string | null
  headCheckError: string | null
  checkStatus: SkillPackageCheckStatus
  skills: SkillManifestEntryDTO[]
  version: string
  createTime: string
  updateTime: string
}

export interface SkillPackageCreateDTO {
  packageName: string
  description?: string | null
  repositoryUrl: string
  branch: string
}

export interface SkillPackageEditDTO {
  expectedVersion: string
  description?: string | null
  branch: string
}

export interface SkillPackageCheckDTO {
  expectedVersion: string
}

export interface SkillPackagePublishDTO {
  expectedVersion: string
  targetCommit: string
}

export interface AgentDefinitionConfigDTO {
  /** 子 Agent 被委派时是否继承父会话当前的 Environment。 */
  inheritParentEnvironment: boolean
  /** 有序且唯一的模型可见 tool name 列表；候选来自离线 tool catalog。 */
  tools: string[]
  /** 选中的 Skill 引用列表 (packageName, name)；按声明顺序且不可重复。 */
  skills: SkillRefDTO[]
  /** 可通过 task 委派的 Agent 名称 allowlist；只接受短名。 */
  subagents: string[]
}

export type EnvironmentSupport = 'NONE' | 'OPTIONAL' | 'REQUIRED'

/** 可离线选择的统一运行时 tool catalog 条目。 */
export interface ToolCatalogEntryDTO {
  /** 模型可见工具名：全局唯一。 */
  name: string
  description: string
  environmentSupport?: EnvironmentSupport
  requiredEnvironmentId?: string | null
  environmentRequired?: boolean
  environmentId?: string | null
}

/** 公开的全局 Agent definition；model/variant 与 config 是 Thread 运行时的输入。 */
export interface AgentDefinitionDTO {
  name: string
  description: string | null
  systemPrompt: string | null
  model: string
  /** 可选覆盖；为 null 表示使用所选 Model 的 defaultVariant。 */
  variant: string | null
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
  config: AgentDefinitionConfigDTO
}

export interface AgentDefinitionCreateDTO extends AgentDefinitionEditablePropertiesDTO {
  name: string
}

export interface AgentDefinitionUpdateDTO extends AgentDefinitionEditablePropertiesDTO {
  expectedVersion: CatalogVersion
}
