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
  | 'NOTIFICATION'
  | 'FORK'
  | 'MODEL_ATTEMPT_FAILURE'
  | 'ASSISTANT_ERROR'
  | 'ASSISTANT_ABORTED'
  | 'COMPACTION'
  | 'TURN_END'
  | 'SETTINGS'

/** 一次 fork 的模式：只陈述历史来源形态（同 Session 分支或复制到新 Session）。 */
export type ForkMode = 'BRANCH' | 'SESSION'

/**
 * FORK Entry 的持久 payload：记录 fork 模式、作为切点的来源 Entry，以及会话 fork 时的来源 Thread
 * （分支 fork 与来源同处一个 Session，因此 sourceThreadId 为 null）。
 * 模型可见的固定英文通知正文不持久化在 payload JSON 中，只陈述事实，不建立执行父子关系。
 */
export interface HarnessForkPayloadDTO {
  mode: ForkMode
  sourceEntryId: string
  sourceThreadId: string | null
}

/** 系统结果通知的分类；对模型只是上下文，不是更高权限指令。 */
export type NotificationKind = 'SUBAGENT_RESULT' | 'TASK_BUDGET'

/**
 * NOTIFICATION Entry / command 的稳定 payload：历史 JSON 与 command JSON 同形。
 * message 是标准 USER AgentMessage（durable 形态），只作为上下文来源，不代表人类输入。
 */
export interface HarnessNotificationPayloadDTO {
  notificationId: string
  kind: NotificationKind
  sourceThreadId: string
  message: { role: string; contents: unknown[] }
}

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
  key: string | null
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
  generatedAt: Exclude<InstantTimestamp, null>
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

/**
 * 草稿请求预览 (POST /api/harness/threads/{threadId}/provider-request-preview)。
 */
export interface ProviderRequestPreviewDTO {
  kind: 'DRAFT_REQUEST_PREVIEW' | 'HISTORICAL_REQUEST_PREVIEW'
  providerType: string
  modelName: string
  bodyByteSize: number
  bodyJson: string
  sourceHeadEntryId: string | null
  generatedAt: Exclude<InstantTimestamp, null>
  notice?: string | null
}

/** 以当前目录价格计算的读取投影，不属于历史 payload。 */
export interface UsageCostDTO {
  currency: string
  amount: string
}

/** Session Entry 查询投影；id 均为 canonical UUID string。 */
export interface HarnessSessionEntryDTO {
  entryId: string
  sessionId: string
  parentEntryId: string | null
  entryType: EntryType
  payloadJson: string
  createTime: BackendDateTime
  usageCost?: UsageCostDTO | null
}

/**
 * 父子 Agent 关系树的一个节点。
 * 从任意 Thread 查询时返回其真实 root 的整棵树，包含运行中、空闲和已结束节点。
 * outcome 仅在 IDLE 且 head 回合已结束时有值，否则为 null。
 * turnCount / toolCallCount 是当前 branch root-to-head 累计，不含 COMPACTION / STOP。
 */
export interface HarnessThreadTreeNodeDTO {
  threadId: string
  parentThreadId: string | null
  /** Java Instant：HTTP 数字为 Unix 秒（可带小数），也接受 ISO 字符串。 */
  updateTime: Exclude<InstantTimestamp, null>
  name: string
  agentName: string
  model: HarnessModelSelectionDTO
  status: string
  processing: boolean
  turnCount: number
  toolCallCount: number
  outcome: string | null
}

/**
 * Thread 的 YOLO 运行时策略投影。根只可能是 ENABLE/DISABLE（rootThreadId 为 null），
 * 子代理只可能是 FOLLOW 且指向真实执行根。该投影取代旧 boolean，展示读取根事实，
 * 不表达实际生效的布尔值。
 */
export interface HarnessThreadYoloPolicyDTO {
  mode: 'ENABLE' | 'DISABLE' | 'FOLLOW'
  rootThreadId: string | null
}

/**
 * HarnessThread 查询投影；id 均为 canonical UUID string，version 是持久快照游标。
 *
 * name 是 Thread 的必需非空展示名称（服务端生成默认值，如 root=main、
 * branch=branch-<uuid 前 8 位>；可经 PUT /harness/threads/{id}/name 修改）；
 * status/processing 是派生的展示字段：status 描述该 Thread 自身执行阶段
 * （IDLE / STOPPED / QUEUED / CONTINUATION_DUE / MODEL_* / TOOL_* / APPLYING），
 * 不递归投影子树忙碌；processing 对 IDLE/STOPPED 为 false，其余为 true。
 * executionControl 是持久执行控制（RUNNABLE / STOPPED），与本地运行阶段无关：
 * 本地已 IDLE 也可能仍有活跃后代需要停止。
 * branchSettings 是 head Entry branch 的完整 settings 快照。
 * yoloPolicy 是该 Thread 的 YOLO 运行时策略投影。
 */
export interface HarnessThreadDTO {
  threadId: string
  /** Thread 的必需非空名称；主展示文本，绝不回退为 id。 */
  name: string
  /** 当前 Session 主键（由 head Entry 派生）。 */
  sessionId: string
  /** 当前 head Entry。 */
  headEntryId: string
  /** 父 Thread 主键（根 Thread 为 null）。 */
  parentThreadId: string | null
  /** 当前冻结的 YOLO 运行时策略投影；根为 ENABLE/DISABLE，子代理为 FOLLOW。 */
  yoloPolicy: HarnessThreadYoloPolicyDTO
  /** 已分配的 command sequence 高水位标记 + 1。 */
  nextCommandSequence: string
  /** PostgreSQL 权威持久投影游标（非负十进制 bigint 字符串）。 */
  version: string
  /** 展示状态（派生）：IDLE / STOPPED / QUEUED / CONTINUATION_DUE / MODEL_* / TOOL_* / APPLYING。 */
  status: string
  /** 是否仍在处理（派生字段），IDLE 与 STOPPED 为 false。 */
  processing: boolean
  /** 持久执行控制：RUNNABLE / STOPPED。 */
  executionControl: string
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
/** 图片输入档位；Wire 与 durable 均采用 720P / 1080P / ORIGINAL。 */
export type ImageInputTier = '720P' | '1080P' | 'ORIGINAL'

export type HarnessUserMessageContentDTO =
  | { type: 'TEXT'; text: string }
  /**
   * 共享存储 READY 上传的引用。uploadId 是完成后的持久句柄；后端在入队事务内
   * 将其原子物化为 durable RESOURCE。
   */
  | { type: 'ATTACHMENT'; uploadId: string; imageTier?: ImageInputTier }
  /** 当前 Session 已拥有的 durable blob ref；用于 Stop 后 Resource pill 重提。 */
  | { type: 'RESOURCE'; blobId: string; name: string; preview?: string | null; imageTier?: ImageInputTier }

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
 * Thread YOLO policy 直接更新请求。它只提交目标策略，不携带 Thread version CAS。
 */
export interface HarnessThreadYoloUpdateDTO {
  yoloEnabled: boolean
}

/** Thread 停止请求；stopRequestId 是稳定的幂等键。 */
export interface HarnessThreadStopDTO {
  stopRequestId: string
  expectedVersion: string
}

/**
 * 停止结果；status 为 STOPPED / REPLAYED。thread 是请求目标的权威当前投影；
 * stoppedThreads 是本次完整受影响集合（含目标自身与全部后代）的持久回执，
 * 精确重放时返回原回执集合。
 */
export interface HarnessThreadStopResultDTO {
  status: string
  thread: HarnessThreadDTO
  stoppedThreads: HarnessStoppedThreadReceiptDTO[]
}

/** Stop 取消的一条人工输入；type 只可能是 USER_MESSAGE / GOAL。 */
export interface HarnessCancelledInputDTO {
  sequence: string
  idempotencyKey: string
  type: 'USER_MESSAGE' | 'GOAL'
  /** canonical ThreadCommandPayload JSON（USER_MESSAGE: {message}；GOAL: {text}）。 */
  payloadJson: string
}

/**
 * 一次 Stop 在某个受影响 Thread 上留下的持久回执；身份是 (threadId, stopRequestId)。
 * cancelledInputs 只含可恢复的人工输入，按 sequence 升序。
 */
export interface HarnessStoppedThreadReceiptDTO {
  threadId: string
  stopRequestId: string
  stoppedTurnEndEntryId?: string | null
  cancelledCommandCount: number
  cancelledInputs: HarnessCancelledInputDTO[]
}

/**
 * Tool 审批决定请求；decisionId 是稳定的客户端幂等键。
 *
 * <p>请求体绝不携带操作者身份：actor 由服务端从认证主体解析，客户端无法伪造。
 */
export interface HarnessToolApprovalDTO {
  decision: 'ALLOW' | 'DENY'
  decisionId: string
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
  /** Work 冻结的路由与等待投影，不来自当前 composer 设置。 */
  requiredEnvironmentId: string | null
  waitingForEnvironment: boolean
  requiredEnvironmentName: string | null
  environmentWaitFreshnessAt: InstantTimestamp | null
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
  /**
   * 该 Thread 自己的、包含可恢复人工输入的持久 Stop 回执。它与 Stop 响应走同一
   * 应用渠道，供刷新或未打开子页时恢复草稿；按 (threadId, stopRequestId) 幂等。
   */
  stopReceipts: HarnessStoppedThreadReceiptDTO[]
}

export interface ManualCompactionDTO {
  available: boolean
  disabledReason: string | null
}

export type AgentRuntimeOwnerDTO =
  | { type: 'CHAT'; chatId: string }
  | { type: 'ISSUE_AGENT'; issueId: string; agentName: string }

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
  threadName: string
  yoloEnabled: boolean
}

/**
 * 会话 fork 创建 target：从 sourceThread 的 startEntryId 切点复制有效上下文到新 Session
 * 与新 Thread。settings 从源切点 branch 推导，因此不携带 rootSettings/threadName；
 * commands 与产品 owner 语义同 NEW_SESSION / NEW_THREAD。
 */
export interface NewForkedSessionCommandTargetDTO {
  type: 'NEW_FORKED_SESSION'
  sourceThreadId: string
  startEntryId: string
  sessionId: string
  threadId: string
  yoloEnabled: boolean
}

/** 创建型命令批次 target：NEW_SESSION / NEW_THREAD / NEW_FORKED_SESSION，都需要产品 owner。 */
export type AgentCommandTargetDTO =
  | NewSessionCommandTargetDTO
  | NewThreadCommandTargetDTO
  | NewForkedSessionCommandTargetDTO

/** 创建型命令批次请求（POST /harness/command-batches）。 */
export interface AgentCommandBatchRequestDTO {
  owner: AgentRuntimeOwnerDTO
  target: AgentCommandTargetDTO
  commands: HarnessCommandCreateDTO[]
}

/**
 * 既有 Thread 的通用命令写请求：只携带精确 CAS 游标与有序命令。
 *
 * POST /harness/threads/{threadId}/command-batches
 * POST /harness/threads/{threadId}/provider-request-preview
 *
 * 不携带 owner 或 target：服务端从 path 解析 Session，owner 只用于 NEW_SESSION /
 * NEW_THREAD 创建与容器列表。
 */
export interface ThreadCommandBatchRequestDTO {
  expectedHeadEntryId: string
  expectedNextCommandSequence: string
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
  /** 执行父 Thread 的 canonical UUID string；根 Thread 显式为 null。 */
  parentThreadId: string | null
  /** Thread 的必需非空展示名称（服务端权威值）；主展示文本，绝不回退为 id。 */
  name: string
  createdAt: BackendDateTime
  updatedAt: BackendDateTime
  /** 该 Thread 自身执行状态的派生展示值（不递归子树活性）。 */
  status: string
  /** 是否仍在处理（派生字段），IDLE 与 STOPPED 为 false。 */
  processing: boolean
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
  childThreadId: string | null
}
