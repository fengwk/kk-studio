import type {
  BackendBigDecimal,
  BackendDateTime,
  BackendLong,
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

/** Flat Session query projection; Sessions are only created as a side effect of Thread bootstrap. */
export interface HarnessSessionDTO {
  sessionId: string
  title: string | null
  createTime: BackendDateTime
  /** Observable last-entry time; not a stored column. */
  updateTime: BackendDateTime
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
  /** PostgreSQL durable snapshot cursor, always a decimal bigint string. */
  revision: string
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

export interface ModelInvocationDTO {
  id: string
  threadId: string
  sourceHeadEntryId: string
  executionEpoch: BackendLong
  requestJson: string
  status: string
  attempt: number
  nextAttemptAt: InstantTimestamp
  workerUntil: InstantTimestamp
  deadlineAt: InstantTimestamp
  lastActivityAt: InstantTimestamp
  resultJson: string | null
  errorJson: string | null
  appliedAt: InstantTimestamp
  createdAt: InstantTimestamp
  startedAt: InstantTimestamp
  finishedAt: InstantTimestamp
  safeStreamSnapshotJson: string | null
}

export interface InteractionDTO {
  id: string
  toolInvocationId: string
  projectionJson: string
  status: string
  responseJson: string | null
  version: string
  createdAt: InstantTimestamp
  resolvedAt: InstantTimestamp
}

/** Coherent PostgreSQL Thread projection used as the sole chat-runtime query. */
export interface HarnessThreadSnapshotDTO {
  revision: string
  thread: HarnessThreadDTO
  entries: HarnessSessionEntryDTO[]
  inputs: HarnessThreadInputDTO[]
  modelInvocations: ModelInvocationDTO[]
  toolInvocations: ToolInvocationDTO[]
  openInteractions: InteractionDTO[]
  usage: ModelUsageSummaryDTO
}
