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

export interface HarnessSessionDTO {
  sessionId: string
  agentDefinitionId: string
  title: string | null
  rootSessionId: string
  parentSessionId: string | null
  depth: number
  leafEntryId: string | null
  activeRunId: string | null
  yoloEnabled: boolean
  createTime: BackendDateTime
  updateTime: BackendDateTime
}

export interface HarnessSessionCreateDTO {
  agentDefinitionId: string
  title?: string
}

export interface HarnessSessionMessageCreateDTO {
  content: string
  expectedLeafEntryId: string
}

export interface HarnessSessionEntryDTO {
  sessionEntryId: string
  sessionId: string
  parentEntryId: string | null
  runId: string | null
  entryType: string
  payloadJson: string
  createTime: BackendDateTime
}

export interface HarnessRunDTO {
  runId: string
  sessionId: string
  triggerEntryId: string
  status: string
  turnIndex: number
  attempt: number
  eventSequence: number
  nextAttemptAt: BackendDateTime
  cancelRequestedAt: BackendDateTime
  startedAt: BackendDateTime
  finishedAt: BackendDateTime
  createTime: BackendDateTime
  updateTime: BackendDateTime
}

export interface RunEventDTO {
  eventId: string
  runId: string
  sequence: number
  type: string
  payloadJson: string
  createTime: BackendDateTime
}

export interface RootActivityDTO {
  rootSessionId: string
  sessionId: string
  runId: string
  eventId: string
  sequence: number
  type: string
  payloadJson: string
  createTime: BackendDateTime
}

export interface ToolInvocationDTO {
  id: string
  runId: string
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

export interface SessionYoloDTO {
  sessionId: string
  rootSessionId: string
  enabled: boolean
}

export interface ToolArtifactRefDTO {
  artifactId: string
  mediaType: string
  sizeBytes: BackendLong
}

export interface SubagentTaskReportDTO {
  childSessionId: string
  childRunId: string
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
  childSessionId: string
  childRunId: string
  targetAgent: string
  workingCopyPolicy: string
  workingCopyRevision: string | null
  maxTurns: number
  idleTimeoutMillis: BackendLong | null
  status: string
  report: SubagentTaskReportDTO | null
  createTime: BackendDateTime
  updateTime: BackendDateTime
}

export interface RunControlDTO {
  id: string
  sessionId: string
  runId: string | null
  consumedRunId: string | null
  consumedEntryId: string | null
  kind: string
  consumptionMode: string
  status: string
  content: string
  createdAt: BackendDateTime
  consumedAt: BackendDateTime
}

export interface RunAbortDTO {
  sessionId: string
  runId: string | null
  newlyRequested: boolean
  status: string | null
  requestedAt: BackendDateTime
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
