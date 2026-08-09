import type { BackendDateTime, InstantTimestamp } from '@/shared/api/contracts/base'

/**
 * 冻结进 branch settings 快照的不可变 provider/model/variant 选择。
 */
export interface HarnessModelSelectionDTO {
  providerName: string
  modelName: string
  variant: string
}

/**
 * 单个 Entry branch 的完整 branch settings 快照。
 *
 * environmentName 是可空的规范小写路由名称；展示名称永远不会进入此持久快照。
 */
export interface HarnessBranchSettingsDTO {
  environmentName: string | null
  agentName: string
  model: HarnessModelSelectionDTO
  thinkingLevel: string
  activeTools: string[]
}

export type EntryType =
  | 'ROOT'
  | 'TURN_START'
  | 'MESSAGE'
  | 'CUSTOM'
  | 'CUSTOM_MESSAGE'
  | 'ASSISTANT_ERROR'
  | 'ASSISTANT_ABORTED'
  | 'COMPACTION'
  | 'TURN_END'

/** Session Entry 查询投影；id 均为严格的正十进制数字字符串。 */
export interface HarnessSessionEntryDTO {
  entryId: string
  sessionId: string
  parentEntryId: string | null
  entryType: EntryType
  payloadJson: string
  createTime: BackendDateTime
}

/**
 * HarnessThread 查询投影；id 均为严格的正十进制数字字符串，revision 是持久快照游标。
 *
 * status/processing 是派生的展示字段（只有 IDLE 时 processing 才为 false）；
 * branchSettings 是 head Entry branch 的完整 settings 快照。
 */
export interface HarnessThreadDTO {
  threadId: string
  /** 当前 Session 主键（由 head Entry 派生）。 */
  sessionId: string
  /** 当前 head Entry。 */
  headEntryId: string
  /** 当前冻结的 YOLO 运行时策略。 */
  yoloEnabled: boolean
  /** 已分配的 command sequence 高水位标记 + 1。 */
  nextCommandSequence: string
  /** PostgreSQL 权威持久投影游标（非负十进制 bigint 字符串）。 */
  revision: string
  /** 展示状态（派生）：IDLE / CONTINUATION_DUE / MODEL_* / TOOL_* / APPLYING。 */
  status: string
  /** 运行时当前是否正在处理此 Thread（派生字段）。 */
  processing: boolean
  /** head Entry branch 的完整 settings 快照。 */
  branchSettings: HarnessBranchSettingsDTO
  createTime: BackendDateTime
  updateTime: BackendDateTime
}

/** 持久的 Thread mailbox command 投影。 */
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
 * 类型化的 Thread mailbox command 请求。
 *
 * 该可辨识联合镜像了 Java mapper 严格的按类型字段规则：USER_MESSAGE 不得携带 role
 * （mapper 禁止该字段——role 始终是 USER）；CUSTOM_MESSAGE 携带大写 role
 * （SYSTEM/USER）。clientCommandId 是稳定的幂等键。
 */
export type HarnessThreadCommandCreateDTO =
  | { type: 'USER_MESSAGE'; clientCommandId: string; content: string }
  | { type: 'CUSTOM_MESSAGE'; clientCommandId: string; content: string; role: 'SYSTEM' | 'USER' }
  | { type: 'SET_AGENT'; clientCommandId: string; agentName: string }
  | { type: 'SET_MODEL'; clientCommandId: string; model: HarnessModelSelectionDTO }
  | { type: 'SET_THINKING_LEVEL'; clientCommandId: string; thinkingLevel: string }
  | { type: 'SET_ACTIVE_TOOLS'; clientCommandId: string; activeTools: string[] }
  | { type: 'SET_YOLO'; clientCommandId: string; yoloEnabled: boolean }
  | { type: 'SET_ENVIRONMENT'; clientCommandId: string; environmentName: string | null }

/** 原子化的 Thread mailbox 入队请求；期望游标来自最新的 thread DTO。 */
export interface HarnessThreadCommandBatchDTO {
  expectedHeadEntryId: string
  expectedNextCommandSequence: string
  commands: HarnessThreadCommandCreateDTO[]
}

/** 以完整的 branch settings 快照原子化创建 Thread；title 可为 null。 */
export interface HarnessThreadCreateDTO {
  title: string | null
  branchSettings: HarnessBranchSettingsDTO
  yoloEnabled: boolean
}

/** Thread head 重定位请求；expectedRevision 是精确的 revision CAS 游标。 */
export interface HarnessThreadHeadUpdateDTO {
  targetEntryId: string
  expectedRevision: string
}

/** Thread 停止请求；stopRequestId 是稳定的幂等键。 */
export interface HarnessThreadStopDTO {
  stopRequestId: string
  expectedRevision: string
}

/**
 * 停止结果；status 为 STOPPED / IDLE / REPLAYED。IDLE 表示没有 Turn 被停止，但排队中的
 * commands 可能已被取消；仅 STOPPED/REPLAYED 时 stoppedTurnEndEntryId 才非 null。
 */
export interface HarnessThreadStopResultDTO {
  status: string
  thread: HarnessThreadDTO
  stoppedTurnEndEntryId: string | null
  cancelledCommandCount: number
}

/** Tool 审批决定请求；decisionId 是稳定的客户端幂等键。 */
export interface HarnessToolApprovalDTO {
  decision: 'ALLOW' | 'DENY'
  decisionId: string
  actor: string
  reason: string | null
}

/**
 * ModelInvocation 查询投影；id 均为严格的正十进制数字字符串。
 * streamCheckpointJson / resultJson / errorJson 是规范的运行时 codec JSON，仅在其对应阶段
 * 非 null；resultEntryId 是应用 TURN_END 后的结果 Entry。
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
 * ToolInvocation 查询投影；id 均为严格的正十进制数字字符串。
 * approvalJson / resultJson / errorJson 是规范的运行时 codec JSON，仅在其对应阶段
 * 非 null；environmentName 是可空的规范小写路由名称。
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
  rendererKey: string
  toolType: string
  environmentName: string | null
  argumentsJson: string
  approvalJson: string | null
  resultJson: string | null
  errorJson: string | null
  resultEntryId: string | null
  createTime: BackendDateTime
  updateTime: BackendDateTime
}

/**
 * 一致的 Thread 快照投影；所有字段都来自同一个数据库快照。
 * modelInvocation 是当前 Turn 的活动 model invocation（无则为 null）；
 * toolInvocations 是它的 tool 兄弟调用。
 */
export interface HarnessThreadSnapshotDTO {
  revision: string
  thread: HarnessThreadDTO
  entries: HarnessSessionEntryDTO[]
  queuedCommands: HarnessThreadCommandDTO[]
  modelInvocation: ModelInvocationDTO | null
  toolInvocations: ToolInvocationDTO[]
}
