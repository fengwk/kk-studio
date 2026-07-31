import type {
  AgentResourceId,
  BackendBigDecimal,
  BackendLong,
  CatalogVersion,
  InstantTimestamp,
} from '@/shared/api/contracts/base'

export interface ModelUsageCostSummaryDTO {
  currency: string
  input: BackendBigDecimal
  output: BackendBigDecimal
  cacheRead: BackendBigDecimal
  cacheWrite: BackendBigDecimal
  cacheWriteLong: BackendBigDecimal
  reasoning: BackendBigDecimal
  total: BackendBigDecimal
}

export interface ModelUsageSummaryDTO {
  scopeType: 'thread' | 'session' | 'model'
  scopeId: string
  recordCount: BackendLong
  inputTokens: BackendLong
  outputTokens: BackendLong
  cacheReadTokens: BackendLong
  cacheWriteTokens: BackendLong
  cacheWriteLongTokens: BackendLong
  reasoningTokens: BackendLong
  providerTotalTokens: BackendLong
  cacheEligibleRecordCount: BackendLong
  cacheHitRecordCount: BackendLong
  cacheHitRatio: BackendBigDecimal
  tokenReadRatio: BackendBigDecimal
  unamortizedCacheWriteTokens: BackendLong
  costs: ModelUsageCostSummaryDTO[]
}

export interface AgentProviderDTO {
  id: AgentResourceId
  name: string
  description: string | null
  providerType: string
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
  providerType: string
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

/** Stable enum mirroring the backend {@code AgentModelInputModality}. */
export type AgentModelInputModality =
  | 'TEXT'
  | 'IMAGE'
  | 'AUDIO'
  | 'VIDEO'
  | 'DOCUMENT'

export interface AgentModelLimitDTO {
  /** Positive integer count of model context window tokens. */
  context: number
  /** Positive integer {@code <= context}. */
  output: number
}

export interface AgentModelAbilitiesDTO {
  tools: boolean
  reasoning: boolean
  /** Non-empty subset of {@link AgentModelInputModality}. */
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

export interface AgentModelVariantDTO {
  id: string
  reasoningEffort?: string | null
  maxOutputTokens?: number | null
  temperature?: number | null
  topP?: number | null
  topK?: number | null
  frequencyPenalty?: number | null
  presencePenalty?: number | null
  stopSequences?: string[] | null
}

/**
 * Structured Agent model configuration. Every sub-shape is required on the wire; missing or
 * malformed configs must be rejected by the backend rather than silently repaired.
 */
export interface AgentModelConfigDTO {
  limit: AgentModelLimitDTO
  abilities: AgentModelAbilitiesDTO
  pricing: AgentModelPricingDTO
  defaultVariant: string
  variants: AgentModelVariantDTO[]
}

/** Public Agent model resource with one structured executable config. */
export interface AgentModelDTO {
  id: AgentResourceId
  providerId: AgentResourceId
  name: string
  description: string | null
  config: AgentModelConfigDTO
  version: CatalogVersion
  createTime: InstantTimestamp
  updateTime: InstantTimestamp
}

/**
 * Editable portion of an Agent model. The frontend sends full replacements: {@code name},
 * {@code description}, and {@code config} are all required when a request body is issued.
 */
export interface AgentModelEditablePropertiesDTO {
  name: string
  description: string | null
  config: AgentModelConfigDTO
}

export interface AgentModelCreateDTO extends AgentModelEditablePropertiesDTO {
  providerId: string
}

export interface AgentModelUpdateDTO extends AgentModelEditablePropertiesDTO {
  expectedVersion: CatalogVersion
}

export interface AgentDefinitionConfigDTO {
  /** Optional live Environment name; an omitted or null value means none. */
  environmentName?: string | null
  tools: string[]
  skills: string[]
}

/** Public global Agent definition; model/variant + config are Thread-runtime inputs. */
export interface AgentDefinitionDTO {
  id: AgentResourceId
  name: string
  description: string | null
  systemPrompt: string | null
  modelId: string
  /** Optional override; null means use the selected Model's defaultVariant. */
  variant: string | null
  config: AgentDefinitionConfigDTO
  version: CatalogVersion
  createTime: InstantTimestamp
  updateTime: InstantTimestamp
}

/** Complete Agent Definition create/PUT body. */
export interface AgentDefinitionEditablePropertiesDTO {
  name: string
  description: string | null
  systemPrompt: string | null
  modelId: string
  /** Optional override; null means use the selected Model's defaultVariant. */
  variant: string | null
  config: AgentDefinitionConfigDTO
}

export type AgentDefinitionCreateDTO = AgentDefinitionEditablePropertiesDTO

export interface AgentDefinitionUpdateDTO extends AgentDefinitionEditablePropertiesDTO {
  expectedVersion: CatalogVersion
}
