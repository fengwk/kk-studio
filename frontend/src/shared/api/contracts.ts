export interface ResultEnvelope<T> {
  status: number
  code: string
  message: string
  data: T
  errors?: Record<string, unknown>
}

export interface PageResult<T> {
  pageNumber: number
  pageSize: number
  totalCount: number | string
  results: T[]
}

export type BackendDateTime = string | number[] | null

/** Java {@code Instant} timestamp emitted by the backend's Jackson configuration. */
export type InstantTimestamp = number | string | null

/** Backend resource ids are positive decimal strings and must remain strings on the wire. */
export type AgentResourceId = string

export type BackendLong = number | string

export type BackendBigDecimal = number | string

/** Non-negative decimal optimistic-lock token. */
export type CatalogVersion = string

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

/** Flat Session query projection; Sessions are only created as a side effect of Thread bootstrap. */
export interface HarnessSessionDTO {
  sessionId: string
  title: string | null
  createTime: BackendDateTime
  /** Observable last-entry time; not a stored column. */
  updateTime: BackendDateTime
}

/** Persistent Chat collection; defaultAgentId may be stale after Agent deletion. */
export interface ChatDTO {
  id: string
  title: string | null
  defaultAgentId: string | null
  version: CatalogVersion
  createTime: InstantTimestamp
  updateTime: InstantTimestamp
}

export interface ChatCreateDTO {
  title?: string
  defaultAgentId?: string
}

/** Partial update: null preserves; title must be non-blank when supplied; blank defaultAgentId clears it. */
export interface ChatUpdateDTO {
  title?: string | null
  defaultAgentId?: string | null
  expectedVersion: CatalogVersion
}

export interface LiveEnvironmentToolDTO {
  name: string
  version: string | null
  description: string | null
}

export interface LiveEnvironmentSkillDTO {
  name: string
  description: string | null
}

/** Read-only live Environment registry entry. */
export interface LiveEnvironmentDTO {
  name: string
  status: string
  lastSeen: string | null
  tools: LiveEnvironmentToolDTO[]
  skills: LiveEnvironmentSkillDTO[]
}

export type EntryType =
  | 'ROOT'
  | 'RUNTIME_CONFIG'
  | 'MESSAGE'
  | 'CUSTOM_MESSAGE'
  | 'ASSISTANT_ERROR'
  | 'ASSISTANT_ABORTED'

export interface HarnessSessionEntryDTO {
  entryId: string
  parentEntryId: string | null
  entryType: EntryType
  payloadJson: string
  createTime: BackendDateTime
}

/**
 * HarnessThread query projection; ids are decimal strings.
 * `sessionId` / `sessionTitle` / `headEntryId` are null while the Thread is UNBOUND.
 */
export interface HarnessThreadDTO {
  threadId: string
  sessionId: string | null
  sessionTitle: string | null
  headEntryId: string | null
  /** CAS fencing token; every external mutation must echo the currently known value. */
  executionEpoch: BackendLong
  /** Derived display status: RUNNING > WAITING > RUNNABLE > UNBOUND/IDLE. */
  status: ThreadStatus
  inputSequence: BackendLong
  activeAgentDefinitionId: string | null
  activeAgentName: string | null
  modelId: string | null
  variant: string | null
  yoloEnabled: boolean | null
  processing: boolean
  createTime: BackendDateTime
  updateTime: BackendDateTime
}

/** Every external Thread mutation carries the expected epoch; a stale value yields 409. */
export interface HarnessThreadEpochGuardDTO {
  expectedExecutionEpoch: BackendLong
}

/** Atomically creates Session/ROOT/RUNTIME_CONFIG for an UNBOUND Thread and binds its head. */
export interface HarnessThreadBootstrapDTO extends HarnessThreadEpochGuardDTO {
  title?: string
  agentDefinitionId: string
  yoloEnabled: boolean
}

export interface HarnessThreadBootstrapResultDTO {
  session: HarnessSessionDTO
  thread: HarnessThreadDTO
}

/** Binds, moves across Sessions, or clears (null) the Thread head. */
export interface HarnessThreadHeadUpdateDTO extends HarnessThreadEpochGuardDTO {
  headEntryId: string | null
}

export interface HarnessThreadMessageCreateDTO extends HarnessThreadEpochGuardDTO {
  content: string
  clientMessageId: string
}

export interface HarnessThreadCustomMessageCreateDTO extends HarnessThreadEpochGuardDTO {
  role: 'SYSTEM' | 'USER'
  content: string
  clientMessageId: string
}

export interface HarnessThreadYoloSetDTO extends HarnessThreadEpochGuardDTO {
  yoloEnabled: boolean
  clientMessageId: string
}

export interface HarnessThreadAgentSetDTO extends HarnessThreadEpochGuardDTO {
  agentDefinitionId: string
  clientMessageId: string
}

export interface HarnessThreadModelSetDTO extends HarnessThreadEpochGuardDTO {
  modelId: string
  variant: string
  clientMessageId: string
}

export type HarnessThreadStopDTO = HarnessThreadEpochGuardDTO

/**
 * Query-derived Thread status only. Invocation retry waits are projected as WAITING.
 * UNBOUND means the Thread has no head Entry yet and accepts bind/bootstrap only.
 */
export type ThreadStatus = 'UNBOUND' | 'IDLE' | 'RUNNING' | 'WAITING' | 'RUNNABLE'

/** Global automatic retry policy; PUT bodies are complete replacements. */
export interface HarnessRetryPolicyDTO {
  /** Extra attempts after the initial provider request. */
  maxRetries: number
  backoffStrategy: 'FIXED' | 'EXPONENTIAL'
  baseDelayMillis: number
  maxDelayMillis: number
}

/** Global per-Thread Redis realtime Stream capacity policy. */
export interface HarnessRealtimeStreamPolicyDTO {
  maxLength: number
}

export type ThreadInputType =
  | 'USER_MESSAGE'
  | 'CUSTOM_MESSAGE'
  | 'SET_AGENT'
  | 'SET_MODEL'
  | 'SET_YOLO'

export type ThreadInputStatus = 'QUEUED' | 'APPLIED' | 'CANCELLED'

export interface HarnessThreadInputDTO {
  inputId: string
  threadId: string
  sequence: number
  inputType: ThreadInputType
  payloadJson: string
  clientMessageId: string
  status: ThreadInputStatus
  resolvedAt: BackendDateTime
  createTime: BackendDateTime
}

export interface HarnessThreadStopResultDTO {
  /** convention4j serializes Java Long as decimal string on the wire. */
  executionEpoch: BackendLong
  cancelledInputs: HarnessThreadInputDTO[]
}

export interface ToolInvocationDTO {
  id: string
  threadId: string
  assistantEntryId: string
  ordinal: number
  toolCallId: string
  toolName: string
  toolVersion: string
  location: 'PLATFORM' | 'ENVIRONMENT'
  environmentName: string | null
  argumentsJson: string
  status: string
  permissionAction: string
  permissionDecision: string | null
  deadlineAt: BackendDateTime
  leaseOwner: string | null
  leaseUntil: BackendDateTime
  cancelRequestedAt: BackendDateTime
  resultJson: string | null
  errorMessage: string | null
  createTime: BackendDateTime
  startedAt: BackendDateTime
  finishedAt: BackendDateTime
  updateTime: BackendDateTime
}

export interface ToolArtifactRefDTO {
  artifactId: string
  mediaType: string
  sizeBytes: BackendLong
}

export type ComfyuiWorkflowId = string

export type ComfyuiBindingKind = 'parameter' | 'file'

export type ComfyuiBindingValueType = 'string' | 'integer' | 'number' | 'boolean' | 'json'

export interface ComfyuiInputBinding {
  name: string
  kind: ComfyuiBindingKind
  nodeId: string
  inputName: string
  required?: boolean
  description?: string
  valueType?: ComfyuiBindingValueType
  defaultValue?: unknown
}

export interface ComfyuiWorkflowApiDTO {
  id: ComfyuiWorkflowId
  apiName: string
  name: string
  description: string | null
  workflowJson: string
  inputBindingsJson: string
  defaultSelector: string | null
  enabled: boolean
  createTime: BackendDateTime
  updateTime: BackendDateTime
}

export interface ComfyuiWorkflowApiEditablePropertiesDTO {
  apiName: string
  name: string
  description?: string | null
  workflowJson: string
  inputBindingsJson: string
  defaultSelector?: string | null
  enabled: boolean
}

export type ComfyuiWorkflowApiCreateDTO = ComfyuiWorkflowApiEditablePropertiesDTO
export type ComfyuiWorkflowApiUpdateDTO = ComfyuiWorkflowApiEditablePropertiesDTO

export interface S3PresignedRequestDTO {
  key: string
  contentType?: string
  expiresInSeconds?: number
}

export interface S3PresignedResponseDTO {
  bucket: string
  key: string
  method: string
  url: string
  headers: Record<string, string>
  expiresAt: BackendDateTime
}

export interface ComfyuiWorkflowRunFileDTO {
  key: string
  filename: string
  contentType: string
}

export interface ComfyuiWorkflowRunRequestDTO {
  parameters: Record<string, unknown>
  files: Record<string, ComfyuiWorkflowRunFileDTO>
}

export interface ComfyuiWorkflowRunDTO {
  runId: string
  status: string
  defaultSelector: string | null
}

export interface ComfyuiWorkflowCancelDTO {
  runId: string
  cancelled: boolean
}

export interface ComfyuiWorkflowJobDTO {
  runId: string
  status: string
  priority: number | null
  createTime: BackendDateTime
  updateTime: BackendDateTime
  workflowId: string | null
  executionStartTime: BackendDateTime
  executionEndTime: BackendDateTime
  outputsCount: number | null
  executionError: unknown
  executionStatus: unknown
  workflow: unknown
  previewOutput: unknown
  result: unknown
}
