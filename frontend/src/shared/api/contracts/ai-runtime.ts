import type {
  BackendDateTime,
  DecimalLong,
  InstantTimestamp,
} from '@/shared/api/contracts/base'
import type { EnvironmentBindingDTO } from '@/shared/api/contracts/ai-environment'

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
 * environment 是可空的完整 Environment binding（{name, workspacePath}，null 表示未绑定），
 * 是 durable 快照中的唯一路由身份。
 */
export interface HarnessBranchSettingsDTO {
  environment: EnvironmentBindingDTO | null
  agentName: string
  model: HarnessModelSelectionDTO
  activeTools: string[]
}

export type EntryType =
  | 'ROOT'
  | 'TURN_START'
  | 'MESSAGE'
  | 'CUSTOM'
  | 'CUSTOM_MESSAGE'
  | 'MODEL_ATTEMPT_FAILURE'
  | 'ASSISTANT_ERROR'
  | 'ASSISTANT_ABORTED'
  | 'COMPACTION'
  | 'TURN_END'

/** 按当前 branch 最新状态现算的系统提示词预览。 */
export interface HarnessSystemPromptPreviewDTO {
  text: string
}

/** Session Entry 查询投影；id 均为 canonical UUID string。 */
export interface HarnessSessionEntryDTO {
  entryId: string
  sessionId: string
  parentEntryId: string | null
  entryType: EntryType
  payloadJson: string
  createTime: BackendDateTime
}

/**
 * HarnessThread 查询投影；id 均为 canonical UUID string，revision 是持久快照游标。
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

/**
 * 持久的 Thread mailbox command 投影；身份为 (threadId, sequence)，无代理主键。
 * clientCommandId 是稳定的客户端幂等键；requestHash 是其 raw 命令的 canonical SHA-256。
 */
export interface HarnessThreadCommandDTO {
  threadId: string
  sequence: string
  type: string
  state: 'QUEUED' | 'APPLIED' | 'CANCELLED' | string
  clientCommandId: string
  /** 客户端 raw 命令（含 ordered contents 与 uploadId）的 canonical SHA-256：64 位小写 hex；与 clientCommandId 构成幂等键。 */
  requestHash: string
  payloadJson: string
  consumedTurnStartEntryId: string | null
  cancelledAt: InstantTimestamp
  createTime: BackendDateTime
}

/**
 * 类型化的 Thread mailbox command 请求。
 *
 * USER_MESSAGE 只接受一个非空、有序的 contents 列表（TEXT/ATTACHMENT），不提供
 * 任何文本 shorthand。CUSTOM_MESSAGE 携带 content 与大写 role（SYSTEM/USER）。
 * clientCommandId 是稳定的幂等键。
 */
export type HarnessUserMessageContentDTO =
  | { type: 'TEXT'; text: string }
  /**
   * 共享存储 READY 上传的引用。uploadId 是完成后的持久句柄；后端在入队事务内
   * 将其原子物化为 durable RESOURCE。
   */
  | { type: 'ATTACHMENT'; uploadId: string }

type HarnessUserMessageCommandDTO = {
  type: 'USER_MESSAGE'
  clientCommandId: string
  contents: [HarnessUserMessageContentDTO, ...HarnessUserMessageContentDTO[]]
}

export type HarnessThreadCommandCreateDTO =
  | HarnessUserMessageCommandDTO
  | { type: 'CUSTOM_MESSAGE'; clientCommandId: string; content: string; role: 'SYSTEM' | 'USER' }
  | { type: 'SET_AGENT'; clientCommandId: string; agentName: string }
  | { type: 'SET_MODEL'; clientCommandId: string; model: HarnessModelSelectionDTO }
  | { type: 'SET_ACTIVE_TOOLS'; clientCommandId: string; activeTools: string[] }
  | { type: 'SET_ENVIRONMENT'; clientCommandId: string; environment: EnvironmentBindingDTO | null }

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

/**
 * Thread YOLO policy 直接更新请求；expectedRevision 是精确的 revision CAS 游标
 * （同值请求在任何 CAS 之前即成功 no-op）。
 */
export interface HarnessThreadYoloUpdateDTO {
  expectedRevision: string
  yoloEnabled: boolean
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
 * ModelInvocation 查询投影；id 均为 canonical UUID string。
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
 * ToolInvocation 查询投影；id 均为 canonical UUID string。
 * approvalJson / resultJson / errorJson 是规范的运行时 codec JSON，仅在其对应阶段
 * 非 null；environment 是可空的完整 Environment binding。
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
  environment: EnvironmentBindingDTO | null
  argumentsJson: string
  approvalJson: string | null
  resultJson: string | null
  errorJson: string | null
  resultEntryId: string | null
  createTime: BackendDateTime
  updateTime: BackendDateTime
}

/** 当前 ModelInvocation 尚未物化到 EntryPath 的失败 attempt 审计投影。 */
export interface ModelAttemptFailureDTO {
  modelInvocationId: string
  turnStartEntryId: string
  basisHeadEntryId: string
  attempt: number
  /** 当前 attempt 的非负十进制 sequence；Java Long 在 HTTP wire 上保持字符串。 */
  sequence: DecimalLong
  text: string
  thinking: string
  errorCode: string
  errorMessage: string
  failedAt: InstantTimestamp
  retryAt: InstantTimestamp
}

/**
 * 一致的 Thread 快照投影；所有字段都来自同一个数据库快照。
 * modelInvocation 是当前 Turn 的活动 model invocation（无则为 null）；
 * toolInvocations 是它的 tool 兄弟调用；modelAttemptFailures 只包含当前 Model context
 * 尚未物化的失败 attempt。
 */
export interface HarnessThreadSnapshotDTO {
  revision: string
  thread: HarnessThreadDTO
  entries: HarnessSessionEntryDTO[]
  queuedCommands: HarnessThreadCommandDTO[]
  modelInvocation: ModelInvocationDTO | null
  toolInvocations: ToolInvocationDTO[]
  modelAttemptFailures: ModelAttemptFailureDTO[]
}
