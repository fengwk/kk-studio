import type { BackendDateTime, InstantTimestamp } from '@/shared/api/contracts/base'

/**
 * Immutable provider/model/variant selection frozen into a branch settings snapshot.
 */
export interface HarnessModelSelectionDTO {
  providerName: string
  modelName: string
  variant: string
}

/**
 * Complete branch settings snapshot of one Entry branch.
 *
 * environmentId is a nullable canonical lowercase UUID route identity; display names never
 * enter this durable snapshot.
 */
export interface HarnessBranchSettingsDTO {
  environmentId: string | null
  agentName: string
  model: HarnessModelSelectionDTO
  thinkingLevel: string
  activeTools: string[]
}

export type EntryType =
  | 'ROOT'
  | 'TURN_START'
  | 'MESSAGE'
  | 'CUSTOM_MESSAGE'
  | 'ASSISTANT_ERROR'
  | 'ASSISTANT_ABORTED'
  | 'TURN_END'

/** Session Entry query projection; ids are strict positive decimal strings. */
export interface HarnessSessionEntryDTO {
  entryId: string
  sessionId: string
  parentEntryId: string | null
  entryType: EntryType
  payloadJson: string
  createTime: BackendDateTime
}

/**
 * HarnessThread query projection; ids are strict positive decimal strings, revision is the
 * durable snapshot cursor.
 *
 * status/processing are derived display fields (processing is false only for IDLE);
 * branchSettings is the complete settings snapshot of the head Entry branch.
 */
export interface HarnessThreadDTO {
  threadId: string
  /** Current Session primary key (derived from the head Entry). */
  sessionId: string
  /** Current head Entry. */
  headEntryId: string
  /** Current frozen YOLO runtime policy. */
  yoloEnabled: boolean
  /** Allocated command sequence high-water mark + 1. */
  nextCommandSequence: string
  /** PostgreSQL authoritative durable projection cursor (non-negative decimal bigint string). */
  revision: string
  /** Display status (derived): IDLE / CONTINUATION_DUE / MODEL_* / TOOL_* / APPLYING. */
  status: string
  /** Whether the runtime is currently processing this Thread (derived). */
  processing: boolean
  /** Complete settings snapshot of the head Entry branch. */
  branchSettings: HarnessBranchSettingsDTO
  createTime: BackendDateTime
  updateTime: BackendDateTime
}

/** Durable Thread mailbox command projection. */
export interface HarnessThreadCommandDTO {
  commandId: string
  threadId: string
  sequence: string
  type: string
  state: 'QUEUED' | 'APPLIED' | 'CANCELLED' | string
  clientCommandId: string
  payloadJson: string
  consumedTurnStartEntryId: string | null
  cancelledAt: InstantTimestamp
  createTime: BackendDateTime
}

/**
 * Typed Thread mailbox command request.
 *
 * The discriminated union mirrors the Java mapper's strict per-type field rules: USER_MESSAGE
 * must NOT carry role (the mapper forbids it — the role is always USER); CUSTOM_MESSAGE carries
 * an uppercase role (SYSTEM/USER). clientCommandId is the stable idempotency key.
 */
export type HarnessThreadCommandCreateDTO =
  | { type: 'USER_MESSAGE'; clientCommandId: string; content: string }
  | { type: 'CUSTOM_MESSAGE'; clientCommandId: string; content: string; role: 'SYSTEM' | 'USER' }
  | { type: 'SET_AGENT'; clientCommandId: string; agentName: string }
  | { type: 'SET_MODEL'; clientCommandId: string; model: HarnessModelSelectionDTO }
  | { type: 'SET_THINKING_LEVEL'; clientCommandId: string; thinkingLevel: string }
  | { type: 'SET_ACTIVE_TOOLS'; clientCommandId: string; activeTools: string[] }
  | { type: 'SET_YOLO'; clientCommandId: string; yoloEnabled: boolean }
  | { type: 'SET_ENVIRONMENT'; clientCommandId: string; environmentId: string | null }

/** Atomic Thread mailbox enqueue request; expected cursors come from the latest thread DTO. */
export interface HarnessThreadCommandBatchDTO {
  expectedHeadEntryId: string
  expectedNextCommandSequence: string
  commands: HarnessThreadCommandCreateDTO[]
}

/** Create a Thread atomically with a complete branch settings snapshot; title is nullable. */
export interface HarnessThreadCreateDTO {
  title: string | null
  branchSettings: HarnessBranchSettingsDTO
  yoloEnabled: boolean
}

/** Thread head relocation request; expectedRevision is the exact revision CAS cursor. */
export interface HarnessThreadHeadUpdateDTO {
  targetEntryId: string
  expectedRevision: string
}

/** Thread stop request; stopRequestId is the stable idempotency key. */
export interface HarnessThreadStopDTO {
  stopRequestId: string
  expectedRevision: string
}

/**
 * Stop result; status is STOPPED / IDLE / REPLAYED. IDLE means no Turn was stopped but queued
 * commands may have been cancelled; stoppedTurnEndEntryId is non-null only for STOPPED/REPLAYED.
 */
export interface HarnessThreadStopResultDTO {
  status: string
  thread: HarnessThreadDTO
  stoppedTurnEndEntryId: string | null
  cancelledCommandCount: number
}

/** Tool approval decision request; decisionId is the stable client idempotency key. */
export interface HarnessToolApprovalDTO {
  decision: 'ALLOW' | 'DENY'
  decisionId: string
  actor: string
  reason: string | null
}

/**
 * ModelInvocation query projection; ids are strict positive decimal strings.
 * streamCheckpointJson / resultJson / errorJson are canonical runtime codec JSON, non-null only
 * in their corresponding phase; resultEntryId is the result Entry after TURN_END application.
 */
export interface ModelInvocationDTO {
  id: string
  threadId: string
  turnStartEntryId: string
  basisHeadEntryId: string
  status: string
  attempt: number
  streamCheckpointJson: string | null
  resultJson: string | null
  errorJson: string | null
  resultEntryId: string | null
  createTime: BackendDateTime
  updateTime: BackendDateTime
}

/**
 * ToolInvocation query projection; ids are strict positive decimal strings.
 * approvalJson / resultJson / errorJson are canonical runtime codec JSON, non-null only in
 * their corresponding phase; environmentId is a nullable canonical lowercase UUID route identity.
 */
export interface ToolInvocationDTO {
  id: string
  modelInvocationId: string
  assistantEntryId: string
  ordinal: number
  status: string
  attempt: number
  toolCallId: string
  toolName: string
  toolVersion: string
  toolType: string
  environmentId: string | null
  argumentsJson: string
  approvalJson: string | null
  resultJson: string | null
  errorJson: string | null
  resultEntryId: string | null
  createTime: BackendDateTime
  updateTime: BackendDateTime
}

/**
 * Coherent Thread snapshot projection; all fields come from the same database snapshot.
 * modelInvocation is the active model invocation of the current Turn (null when none);
 * toolInvocations are its tool siblings.
 */
export interface HarnessThreadSnapshotDTO {
  revision: string
  thread: HarnessThreadDTO
  entries: HarnessSessionEntryDTO[]
  queuedCommands: HarnessThreadCommandDTO[]
  modelInvocation: ModelInvocationDTO | null
  toolInvocations: ToolInvocationDTO[]
}
