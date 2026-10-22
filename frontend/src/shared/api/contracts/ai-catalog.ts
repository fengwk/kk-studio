import type { BackendLong, CatalogVersion, InstantTimestamp } from '@/shared/api/contracts/base'

/** Provider Catalog / HTTP 使用的稳定 wire 值。 */
export type AgentProviderType = 'openai' | 'openai_responses' | 'anthropic' | 'google'

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
  modelHttpRetryStatusCodes: number[] | null
}

export interface AgentProviderEditablePropertiesDTO {
  name?: string | null
  description?: string | null
  providerType: AgentProviderType
  baseUrl?: string | null
  credential?: string | null
  modelCallTimeoutMillis?: BackendLong | null
  modelCallIdleTimeoutMillis?: BackendLong | null
  modelHttpRetryStatusCodes?: number[] | null
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

/** 精确十进制文本；价格与倍率在 wire 上从不经过 JS 浮点数。 */
export type AgentModelDecimal = string

/** 每百万 token 的基础费率；cacheWriteByTtl 键为正整数秒。 */
export interface AgentModelRateSetDTO {
  input: AgentModelDecimal
  output: AgentModelDecimal
  cacheRead: AgentModelDecimal
  cacheWrite: AgentModelDecimal
  cacheWriteByTtl: Record<string, AgentModelDecimal>
  /** null 表示跟随 output；显式值（包括 0）表示独立思考价。 */
  reasoning: AgentModelDecimal | null
}

/** 覆盖规则的费率 patch；缺省字段表示继承，显式字符串（包括 "0"）表示覆盖。 */
export interface AgentModelRatePatchDTO {
  input?: AgentModelDecimal
  output?: AgentModelDecimal
  cacheRead?: AgentModelDecimal
  cacheWrite?: AgentModelDecimal
  cacheWriteByTtl?: Record<string, AgentModelDecimal>
  /** null 表示跟随 output；缺省表示不修改。 */
  reasoning?: AgentModelDecimal | null
}

export interface AgentModelContextConditionDTO {
  gt?: number
  gte?: number
  lt?: number
  lte?: number
}

export interface AgentModelDateConditionDTO {
  gte?: string
  lt?: string
}

export interface AgentModelTimeConditionDTO {
  start: string
  end: string
  /** IANA 时区名。 */
  timezone: string
  /** ISO 星期 1（周一）到 7（周日）。 */
  weekdays?: number[]
}

/** 条件对象内的多个字段按 AND 组合；空对象恒真。 */
export interface AgentModelConditionDTO {
  context?: AgentModelContextConditionDTO
  serviceTier?: string[]
  date?: AgentModelDateConditionDTO
  time?: AgentModelTimeConditionDTO
  all?: AgentModelConditionDTO[]
  any?: AgentModelConditionDTO[]
  not?: AgentModelConditionDTO
}

export interface AgentModelPricingOverrideDTO {
  id: string
  when: AgentModelConditionDTO
  rates: AgentModelRatePatchDTO
}

export interface AgentModelFactorSetDTO {
  input?: AgentModelDecimal
  output?: AgentModelDecimal
  cacheRead?: AgentModelDecimal
  cacheWrite?: AgentModelDecimal
  reasoning?: AgentModelDecimal
}

export interface AgentModelPricingMultiplierDTO {
  id: string
  when: AgentModelConditionDTO
  factors: AgentModelFactorSetDTO
}

/** 完整价格定义：基础费率 + 有序覆盖/倍率规则。 */
export interface AgentModelPricingDefinitionDTO {
  base: AgentModelRateSetDTO
  overrides: AgentModelPricingOverrideDTO[]
  multipliers: AgentModelPricingMultiplierDTO[]
}

/** Variant 表达 reasoning effort 与厂商原生协议请求选项；`off` 为显式关闭，缺省表示协议默认。 */
export interface AgentModelVariantDTO {
  id: string
  reasoningEffort?: string | null
  protocolOptionsJson?: string | null
}

/**
 * 结构化的 Agent model 配置。传输时每个子结构都是必需的；缺失或格式错误的配置必须由后端拒绝，
 * 而不是静默修复。
 */
export interface AgentModelConfigDTO {
  limit: AgentModelLimitDTO
  abilities: AgentModelAbilitiesDTO
  pricing: AgentModelPricingDefinitionDTO
  defaultVariant: string
  variants: AgentModelVariantDTO[]
}

/** 组成员引用具体逻辑 Model 与 Variant；组本身不定义 Variant 或价格。 */
export interface AgentModelGroupMemberDTO {
  modelName: string
  variant: string
}

/** 组的固定选择配置；成员顺序即 Fill First 顺序。 */
export interface AgentModelGroupConfigDTO {
  members: AgentModelGroupMemberDTO[]
  fallbackStatusCodes: number[]
  cooldownSeconds: number
}

export type AgentModelKind = 'MODEL' | 'GROUP'

/**
 * 统一 AgentModel 资源：MODEL 携带 Provider/ModelID/config，GROUP 携带有序成员配置。
 * 名称为全局唯一逻辑名；GROUP 响应中的 config 只是派生能力摘要，不能回写为组价格。
 */
export interface AgentModelDTO {
  /** 全局唯一逻辑名；也是 Agent/Thread/聊天的统一引用。 */
  name: string
  kind: AgentModelKind
  description: string | null
  providerName?: string | null
  /** 发往上游 Provider 的真实模型标识；只属于 MODEL。 */
  modelId?: string | null
  /** MODEL 的完整配置；GROUP 的派生摘要可能缺省。 */
  config?: AgentModelConfigDTO | null
  /** 仅 GROUP 存在。 */
  group?: AgentModelGroupConfigDTO | null
  version: CatalogVersion
  createTime: InstantTimestamp
  updateTime: InstantTimestamp
}

/**
 * Agent model 的可编辑部分。前端发送完整替换；MODEL 必须携带
 * providerName/modelId/config 且不得携带 group，GROUP 必须携带 group 且不得携带单模型字段。
 */
export interface AgentModelEditablePropertiesDTO {
  name: string
  kind: AgentModelKind
  description: string | null
  providerName?: string | null
  modelId?: string | null
  config?: AgentModelConfigDTO | null
  group?: AgentModelGroupConfigDTO | null
}

export interface AgentModelCreateDTO extends AgentModelEditablePropertiesDTO {}

export interface AgentModelUpdateDTO extends AgentModelEditablePropertiesDTO {
  expectedVersion: CatalogVersion
}

/** 系统设置中的目录来源；只在新模型参数候选区读取展示。 */
export type ModelCatalogSource = 'models_dev_v2' | 'models_dev_v1'

/** 目录候选；缺能力/limit 表示未知，明确的 false 才是有效能力事实。 */
export interface ModelDefaultCandidateDTO {
  id: string
  provider: string
  modelId: string
  name: string
  limit?: { context?: number; output?: number } | null
  abilities?: {
    tools?: boolean
    reasoning?: boolean
    inputModalities?: AgentModelInputModality[]
  } | null
  pricing?: AgentModelPricingDefinitionDTO | null
  variants?: AgentModelVariantDTO[] | null
  defaultVariant?: string | null
  supportIssues: string[]
  pricingSupported: boolean
}

/** GET/POST /ai/catalog/model-defaults 的分页响应；generation 用于丢弃迟到混源结果。 */
export interface ModelDefaultsPageDTO {
  source: ModelCatalogSource
  generation: string
  fetchedAt?: InstantTimestamp
  loading: boolean
  error?: string | null
  items: ModelDefaultCandidateDTO[]
  nextCursor?: string | null
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
  hasToken: boolean
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
  token?: string | null
}

export interface SkillPackageEditDTO {
  expectedVersion: string
  description?: string | null
  branch: string
  token?: string | null
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

/**
 * 系统持有的 Agent 生命周期类型：`USER` 由用户创建，`BUILTIN` 由系统初始化并保护身份。
 * 该类型只出现在读取 DTO；创建/更新请求体永不携带，也不能由用户切换。
 */
export type AgentDefinitionType = 'USER' | 'BUILTIN'

/** 公开的全局 Agent definition；model/variant 与 config 是 Thread 运行时的输入。 */
export interface AgentDefinitionDTO {
  name: string
  description: string | null
  systemPrompt: string | null
  /** 系统持有的不可变类型；`BUILTIN` 不能被删除或改名。 */
  type: AgentDefinitionType
  /**
   * 生效模型引用，序列化形式为 {@code providerName/modelName}。
   * `BUILTIN` 支持显式的未配置状态（null）；`USER` 始终已配置。
   */
  model: string | null
  /** 可选覆盖；为 null 表示使用所选 Model 的 defaultVariant。 */
  variant: string | null
  config: AgentDefinitionConfigDTO
  version: CatalogVersion
  createTime: InstantTimestamp
  updateTime: InstantTimestamp
}

/** 完整的 Agent Definition 创建/PUT 请求体；不包含系统持有的 {@link AgentDefinitionType}。 */
export interface AgentDefinitionEditablePropertiesDTO {
  description: string | null
  systemPrompt: string | null
  /**
   * 模型引用，序列化形式为 {@code providerName/modelName}。
   * `USER` 必填；`BUILTIN` 允许 null 以表达未配置状态。
   */
  model: string | null
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
