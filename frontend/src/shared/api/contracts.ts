export interface ResultEnvelope<T> {
  status: number
  code: string
  message: string
  data: T
}

export interface PageResult<T> {
  pageNumber: number
  pageSize: number
  totalCount: number | string
  results: T[]
}

export type BackendDateTime = string | number[] | null

export type AgentResourceId = number | string

export type BackendLong = number | string

export type BackendBigDecimal = number | string

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
  scopeType: 'run' | 'session' | 'model'
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
  apiKey: string | null
  timeoutMillis: number | null
  createTime: BackendDateTime
  updateTime: BackendDateTime
}

export interface AgentProviderEditablePropertiesDTO {
  name?: string
  description?: string | null
  providerType: string
  baseUrl?: string | null
  apiKey?: string | null
  timeoutMillis?: number | null
}

export interface AgentProviderCreateDTO extends AgentProviderEditablePropertiesDTO {
  name: string
}

export type AgentProviderUpdateDTO = AgentProviderEditablePropertiesDTO

export interface AgentModelDTO {
  id: AgentResourceId
  providerId: AgentResourceId
  providerName: string
  name: string
  description: string | null
  defaultVariant: string
  variantsJson: string | null
  createTime: BackendDateTime
  updateTime: BackendDateTime
}

export interface AgentModelEditablePropertiesDTO {
  description?: string | null
  defaultVariant?: string | null
  variantsJson?: string | null
}

export interface AgentModelCreateDTO extends AgentModelEditablePropertiesDTO {
  provider: string
  name: string
}

export interface AgentModelUpdateDTO extends AgentModelEditablePropertiesDTO {
  name?: string
}

export interface AgentDefinitionDTO {
  id: AgentResourceId
  name: string
  description: string | null
  systemPrompt: string | null
  defaultProviderId: AgentResourceId
  defaultProviderName: string
  defaultModelId: AgentResourceId
  defaultModelName: string
  defaultVariant: string
  toolsJson: string | null
  createTime: BackendDateTime
  updateTime: BackendDateTime
}

export interface AgentDefinitionEditablePropertiesDTO {
  description?: string | null
  systemPrompt?: string | null
  defaultVariant?: string | null
  toolsJson?: string | null
}

export interface AgentDefinitionCreateDTO extends AgentDefinitionEditablePropertiesDTO {
  name: string
  defaultProvider: string
  defaultModel: string
}

export interface AgentDefinitionUpdateDTO extends AgentDefinitionEditablePropertiesDTO {
  name?: string
  defaultProvider?: string
  defaultModel?: string
}

export interface AgentSessionDTO {
  sessionId: string
  agentId: AgentResourceId
  agentName: string
  title: string | null
  createTime: BackendDateTime
  updateTime: BackendDateTime
}

export interface AgentSessionCreateDTO {
  agentName: string
  title?: string
}

export interface AgentSessionUpdateDTO {
  title?: string | null
}

export interface AgentSessionMessageCreateDTO {
  content: string
}

export interface AgentSessionEventDTO {
  eventId: string
  sessionId: string
  parentEventId: string
  runId: string | null
  eventType: string
  payloadJson: string | null
  createTime: BackendDateTime
}

export interface AgentRunDTO {
  runId: string
  sessionId: string
  triggerEventId: string
  status: 'queued' | 'running' | 'succeeded' | 'failed' | string
  createTime: BackendDateTime
  updateTime: BackendDateTime
}
