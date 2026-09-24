import type {
  BackendDateTime,
  DecimalLong,
  InstantTimestamp,
} from '@/shared/api/contracts/base'

/**
 * 冻结进 branch settings 快照的不可变 provider/model/variant 选择。
 */
export interface HarnessModelSelectionDTO {
  providerName: string
  modelName: string
  variant: string
}

/**
 * 用户维护的 branch Goal 快照：不可变 id 与用户原文。
 */
export interface HarnessGoalSettingDTO {
  id: string
  text: string
}

/**
 * 单个 Entry branch 的完整 branch settings 快照。
 */
export interface HarnessBranchSettingsDTO {
  agentName: string
  model: HarnessModelSelectionDTO
  environmentName: string | null
  goal: HarnessGoalSettingDTO | null
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

/** 候选 Tool 的发送状态与最终 definition 事实。 */
export interface HarnessModelRequestDebugToolDTO {
  name: string
  description: string
  inputSchemaJson: string
  environmentSupport: 'NONE' | 'OPTIONAL' | 'REQUIRED'
  requiredEnvironmentId: string | null
  provenance: string
  state: 'SENT' | 'FILTERED'
  filterReason: string | null
}

/** 单个 Skill 的交付事实与实际 Prompt 片段。 */
export interface HarnessModelRequestDebugSkillDTO {
  packageName: string
  name: string
  description: string
  path: string
  delivery: 'LOCAL' | 'PLATFORM'
  currentCommit: string
  observedHeadCommit: string | null
  installedCommit: string | null
  promptXml: string
}

/** subagent allowlist 元素。 */
export interface HarnessModelRequestDebugSubagentDTO {
  name: string
  description: string
}

/** Provider cache control 事实。 */
export interface HarnessModelRequestDebugCacheControlDTO {
  retention: 'NONE' | 'SHORT' | 'LONG'
  affinityKey: string | null
  breakpoints: string[]
}

/** 活动 ModelInvocation 的冻结 canonical ProviderRequest。 */
export interface HarnessModelRequestDebugFrozenInvocationDTO {
  kind: 'FROZEN_INVOCATION'
  requestJson: string
}

/**
 * Thread Debug 的结构化模型请求投影。
 */
export interface HarnessModelRequestDebugDTO {
  kind: 'NEXT_REQUEST_PREVIEW'
  generatedAt: string
  model: HarnessModelSelectionDTO
  environmentName: string | null
  systemInstruction: string
  tools: HarnessModelRequestDebugToolDTO[]
  skills: HarnessModelRequestDebugSkillDTO[]
  subagents: HarnessModelRequestDebugSubagentDTO[]
  cacheControl: HarnessModelRequestDebugCacheControlDTO | null
  planningError: string | null
  frozenInvocation: HarnessModelRequestDebugFrozenInvocationDTO | null
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
 * HarnessThread 查询投影；id 均为 canonical UUID string，version 是持久快照游标。
 *
 * name 是 Thread 的必需非空展示名称（服务端生成默认值，如 root=main、
 * branch=branch-<uuid 前 8 位>；可经 PUT /harness/threads/{id}/name 修改）；
 * status/processing 是派生的展示字段（只有 IDLE 时 processing 才为 false）；
 * branchSettings 是 head Entry branch 的完整 settings 快照。
 */
export interface HarnessThreadDTO {
  threadId: string
  /** Thread 的必需非空名称；主展示文本，绝不回退为 id。 */
  name: string
  /** 当前 Session 主键（由 head Entry 派生）。 */
  sessionId: string
  /** 当前 head Entry。 */
  headEntryId: string
  /** 当前冻结的 YOLO 运行时策略。 */
  yoloEnabled: boolean
  /** 已分配的 command sequence 高水位标记 + 1。 */
  nextCommandSequence: string
  /** PostgreSQL 权威持久投影游标（非负十进制 bigint 字符串）。 */
  version: string
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
 * idempotencyKey 是稳定的客户端幂等键；state 由 durable 终态标记派生。
 */
export interface HarnessThreadCommandDTO {
  threadId: string
  sequence: string
  type: string
  state: 'QUEUED' | 'APPLIED' | 'CANCELLED' | string
  idempotencyKey: string
  payloadJson: string
  cancelledAt: InstantTimestamp
  createTime: BackendDateTime
}

/**
 * 类型化的 Thread mailbox command 请求。
 *
 * USER_MESSAGE 只接受一个非空、有序的 contents 列表（TEXT/ATTACHMENT/RESOURCE），不提供
 * 任何文本 shorthand。持久 Entry/投影仍可包含 CUSTOM_MESSAGE，但它不是创建命令。
 * idempotencyKey 是稳定的幂等键。
 */
export type HarnessUserMessageContentDTO =
  | { type: 'TEXT'; text: string }
  /**
   * 共享存储 READY 上传的引用。uploadId 是完成后的持久句柄；后端在入队事务内
   * 将其原子物化为 durable RESOURCE。
   */
  | { type: 'ATTACHMENT'; uploadId: string }
  /** 当前 Session 已拥有的 durable blob ref；用于 Stop 后 Resource pill 重提。 */
  | { type: 'RESOURCE'; blobId: string; name: string; preview?: string | null }

type HarnessUserMessageCommandDTO = {
  type: 'USER_MESSAGE'
  idempotencyKey: string
  contents: [HarnessUserMessageContentDTO, ...HarnessUserMessageContentDTO[]]
}

export type HarnessCommandCreateDTO =
  | HarnessUserMessageCommandDTO
  | { type: 'GOAL'; idempotencyKey: string; text: string | null }
  | { type: 'SET_AGENT'; idempotencyKey: string; agentName: string }
  | { type: 'SET_MODEL'; idempotencyKey: string; model: HarnessModelSelectionDTO }
  | { type: 'SET_ENVIRONMENT'; idempotencyKey: string; environmentName: string | null }

/**
 * Thread YOLO policy 直接更新请求；expectedVersion 是精确的 version CAS 游标
 * （同值请求在任何 CAS 之前即成功 no-op）。
 */
export interface HarnessThreadYoloUpdateDTO {
  expectedVersion: string
  yoloEnabled: boolean
}

/** Thread 停止请求；stopRequestId 是稳定的幂等键。 */
export interface HarnessThreadStopDTO {
  stopRequestId: string
  expectedVersion: string
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
  cancelledUserMessages: HarnessCancelledUserMessageDTO[]
}

/** Stop 取消的一条 user-like 消息；messageJson 为 canonical AgentMessage JSON。 */
export interface HarnessCancelledUserMessageDTO {
  sequence: string
  idempotencyKey: string
  messageJson: string
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
 * streamCheckpointJson / resultJson / errorJson 是规范的运行时 codec JSON；
 * streamCheckpointJson 可在 Thread version 不变时推进，resultEntryId 是已应用的结果 Entry。
 */
export interface ModelInvocationDTO {
  id: string
  threadId: string
  turnStartEntryId: string
  requestHeadEntryId: string
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
 * 工具身份字段由 durable ToolCall 与 binding 派生：toolCallId / toolName / rendererKey / environmentId。
 * toolCallId / toolName / argumentsJson 恒来自 durable ToolCall，toolName 是唯一模型可见工具身份。
 * rendererKey 恒有值（binding 为 null 时固定回退为 "tool"）。
 * environmentId 为 nullable 环境路由身份。
 * approvalJson / resultJson / errorJson 是规范的运行时 codec JSON，仅在其对应阶段非 null。
 */
export interface ToolInvocationDTO {
  id: string
  modelInvocationId: string
  assistantEntryId: string
  callIndex: number
  status: string
  attempt: number
  toolCallId: string
  toolName: string
  rendererKey: string
  environmentId: string | null
  argumentsJson: string
  approvalJson: string | null
  resultJson: string | null
  errorJson: string | null
  createTime: BackendDateTime
  updateTime: BackendDateTime
}

/** 当前 ModelInvocation 尚未物化到 EntryPath 的失败 attempt 审计投影。 */
export interface ModelAttemptFailureDTO {
  modelInvocationId: string
  turnStartEntryId: string
  requestHeadEntryId: string
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
 * 一致的 Thread 快照投影；durable 字段都来自同一个数据库快照。
 * modelInvocation 是当前 Turn 的活动 model invocation（无则为 null）；
 * toolInvocations 是它的 tool 兄弟调用；modelAttemptFailures 只包含当前 Model context
 * 尚未物化的失败 attempt。
 * manualCompaction 是 advisory sidecar：它只表示当前快照时刻的手动压缩可用性，
 * 不是 durable Thread 状态，提交 compact 时仍必须以最新 version 重新校验。
 */
export interface HarnessThreadSnapshotDTO {
  version: string
  thread: HarnessThreadDTO
  entries: HarnessSessionEntryDTO[]
  queuedCommands: HarnessThreadCommandDTO[]
  modelInvocation: ModelInvocationDTO | null
  toolInvocations: ToolInvocationDTO[]
  modelAttemptFailures: ModelAttemptFailureDTO[]
  manualCompaction: ManualCompactionDTO
}

export interface ManualCompactionDTO {
  available: boolean
  disabledReason: string | null
}

export interface AgentRuntimeOwnerDTO {
  type: 'CHAT' | 'CANVAS' | 'ISSUE_AGENT_SESSION'
  id: string
}

export interface NewSessionCommandTargetDTO {
  type: 'NEW_SESSION'
  sessionId: string
  threadId: string
  rootSettings: HarnessBranchSettingsDTO
  yoloEnabled: boolean
}

export interface NewThreadCommandTargetDTO {
  type: 'NEW_THREAD'
  sessionId: string
  startEntryId: string
  threadId: string
  yoloEnabled: boolean
}

export interface ThreadCommandTargetDTO {
  type: 'THREAD'
  threadId: string
  expectedHeadEntryId: string
  expectedNextCommandSequence: string
}

export type AgentCommandTargetDTO =
  | NewSessionCommandTargetDTO
  | NewThreadCommandTargetDTO
  | ThreadCommandTargetDTO

export interface AgentCommandBatchRequestDTO {
  owner: AgentRuntimeOwnerDTO
  target: AgentCommandTargetDTO
  commands: HarnessCommandCreateDTO[]
}

export interface RuntimeSessionSummaryDTO {
  sessionId: string
  /** Session 的必需非空展示名称（服务端权威值）；主展示文本，绝不回退为 id。 */
  name: string
  createdAt: BackendDateTime
  lastActivityAt: BackendDateTime
  /** 最近一条消息的内容预览；与名称相互独立，可为 null。 */
  firstMessagePreview: string | null
  threadCount: number
}

export interface RuntimeThreadSummaryDTO {
  threadId: string
  /** Thread 的必需非空展示名称（服务端权威值）；主展示文本，绝不回退为 id。 */
  name: string
  createdAt: BackendDateTime
  updatedAt: BackendDateTime
  status: string
  model: HarnessModelSelectionDTO
  headMessagePreview: string | null
}

/** Harness Session 的身份与名称投影（session list / batch 响应共用）。 */
export interface HarnessSessionDTO {
  sessionId: string
  /** Session 的必需非空展示名称（服务端权威值）；主展示文本，绝不回退为 id。 */
  name: string
  createdAt: BackendDateTime
}

export interface AgentCommandBatchResponseDTO {
  session: HarnessSessionDTO
  rootEntry: HarnessSessionEntryDTO
  thread: HarnessThreadDTO
  acceptedCommands: HarnessThreadCommandDTO[]
  replayed: boolean
}

export interface HarnessSessionRenameDTO {
  name: string
}

export interface HarnessThreadRenameDTO {
  name: string
}

export interface ManualCompactionRequestDTO {
  expectedVersion: string
}

export interface ManualCompactionResponseDTO {
  thread: HarnessThreadDTO
  turnStartEntryId: string
  modelInvocationId: string | null
}
