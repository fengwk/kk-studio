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
  modelCallTimeoutMillis: number
  modelCallIdleTimeoutMillis: number
  createTime: BackendDateTime
  updateTime: BackendDateTime
}

export interface AgentProviderEditablePropertiesDTO {
  name?: string
  description?: string | null
  providerType: string
  baseUrl?: string | null
  credential?: string | null
  modelCallTimeoutMillis?: number | null
  modelCallIdleTimeoutMillis?: number | null
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

/** Session tree container; shared Entry root for Threads/tasks/activities. */
export interface HarnessSessionDTO {
  sessionId: string
  title: string | null
  mainThreadId: string
  rootSessionId: string
  parentSessionId: string | null
  parentInvocationId: string | null
  depth: number
  createTime: BackendDateTime
  updateTime: BackendDateTime
}

export interface HarnessSessionCreateDTO {
  agentDefinitionId: string
  title?: string
  yoloEnabled?: boolean
}

export interface HarnessSessionEntryDTO {
  entryId: string
  sessionId: string
  parentEntryId: string | null
  entryType: string
  payloadJson: string
  createTime: BackendDateTime
}

/** AgentThread query projection; ids are decimal strings. */
export interface HarnessThreadDTO {
  threadId: string
  sessionId: string
  sessionTitle: string | null
  headEntryId: string | null
  status: ThreadStatus
  inputSequence: BackendLong
  processing: boolean
  createTime: BackendDateTime
  updateTime: BackendDateTime
}

export interface HarnessThreadCreateDTO {
  fromEntryId: string
}

export interface HarnessThreadMessageCreateDTO {
  content: string
  clientMessageId: string
}

export interface HarnessThreadCustomMessageCreateDTO {
  role: 'SYSTEM' | 'USER'
  content: string
  clientMessageId: string
}

export interface HarnessThreadYoloSetDTO {
  yoloEnabled: boolean
  clientMessageId: string
}

export interface HarnessThreadAgentSetDTO {
  agentDefinitionId: string
  clientMessageId: string
}

export interface HarnessThreadModelSetDTO {
  modelId: string
  variant: string
  clientMessageId: string
}

export interface HarnessThreadToolsetSetDTO {
  tools: string[]
  clientMessageId: string
}

export type ThreadStatus = 'IDLE' | 'RUNNING' | 'WAITING' | 'FAILED' | 'RETRYING'

export type ThreadInputType =
  | 'USER_MESSAGE'
  | 'CUSTOM_MESSAGE'
  | 'SET_AGENT'
  | 'SET_MODEL'
  | 'SET_TOOLSET'
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
  appliedEntryId: string | null
  resolvedAt: BackendDateTime
  cancelledByStopId: string | null
  createTime: BackendDateTime
}

export interface HarnessThreadStopDTO {
  clientRequestId: string
}

export interface HarnessThreadStopResultDTO {
  stopId: string
  cancelledInputs: HarnessThreadInputDTO[]
  restoredMessages: string[]
}

/** Thread event journal; eventId is the SSE cursor. */
export interface ThreadEventDTO {
  eventId: string
  threadId: string
  subjectEntryId: string | null
  eventType: string
  payloadJson: string
  createTime: BackendDateTime
}

export interface RootActivityDTO {
  rootSessionId: string
  sessionId: string
  threadId: string
  eventId: string
  eventType: string
  payloadJson: string
  createTime: BackendDateTime
}

export interface ToolInvocationDTO {
  id: string
  threadId: string
  assistantEntryId: string
  ordinal: number
  toolCallId: string
  toolName: string
  toolVersion: string
  targetType: string
  environmentId: string | null
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

export interface SubagentTaskReportDTO {
  childSessionId: string
  childThreadId?: string
  status: string
  finalReport: string | null
  artifacts: ToolArtifactRefDTO[]
  turnCount: number | null
  toolCount: number | null
  workingCopyPolicy: string | null
  workingCopyRevision: string | null
}

export interface SubagentTaskDTO {
  parentInvocationId: string
  parentSessionId: string
  parentThreadId: string | null
  childSessionId: string
  childThreadId: string | null
  targetAgent: string
  workingCopyPolicy: string
  workingCopyRevision: string | null
  maxTurns: number
  status: string
  report: SubagentTaskReportDTO | null
  createTime: BackendDateTime
  updateTime: BackendDateTime
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
