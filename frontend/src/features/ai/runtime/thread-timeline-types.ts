import type { InstantTimestamp } from '@/shared/api/contracts/base'
import type { SkillRefDTO } from '@/shared/api/contracts/ai-catalog'

type DialogueRole =
  | 'user'
  | 'assistant'
  | 'tool'
  | 'meta'
  | 'entry'
  | 'model_attempt_failure'
type DialogueStatus = 'streaming' | 'done' | 'error'
export type DialogueTimestamp = string | number | readonly number[] | null
export type ToolAttachmentType = 'image' | 'audio' | 'video' | 'file'
/** 控制面/回合摘要等特殊消息，与 user/assistant/tool 正文区分 */
type MetaMessageKind = 'turn_usage'
/** Durable Entry 的非对话审计事件；未知值也必须留在时间线中。 */
export type EntryEventKind =
  | 'root'
  | 'settings_change'
  | 'invalid_settings'
  | 'notification'
  | 'fork'
  | 'compaction'
  | 'empty_message'
  | 'unsupported_message'
  | 'unknown_entry'

/**
 * 读取投影的估算费用：currency 与后端按当前目录价格计算的精确十进制文本 amount。
 * 它不是历史 payload 事实，前端只做精确求和与格式化，绝不自行定价。
 */
export interface UsageCost {
  currency: string
  amount: string
}

/**
 * 已关闭 Turn 的完整 provider usage；各 token 字段互斥。
 * cost 是读取投影（null 表示未定价/不适用），同一 branch 内跨币种或含缺失项时不得伪造成完整总额。
 */
export interface TurnUsage {
  input: number
  output: number
  cacheRead: number
  cacheWrite: number
  reasoning: number
  providerTotal: number
  cost: UsageCost | null
  /** 解码输出 token 数（outputTokens + reasoningTokens），用于与 decodeDurationMillis 配对计算 tok/s */
  decodeTokens?: number | null
  /** 解码耗时（毫秒），严格有限正值，来自 assistantMetadata.decodeDurationMillis */
  decodeDurationMillis?: number | null
  /** 最新一次成功模型调用的已知上下文输入估计 (input + cacheRead + cacheWrite) */
  contextInputTokens?: number | null
}

export interface ToolAttachment {
  type: ToolAttachmentType
  name: string
  mime: string
  /** 稳定 URI（data:/http:/https:/file:/s3:/...）；自身始终不是 base64 负载。 */
  data: string
  preview?: string
  size?: number | null
  sha256?: string | null
  /** Harness adapter 已解析的显式下载地址；portable panel 不感知 API 路由。 */
  downloadHref?: string
  /** 共享存储的持久资源 id（RESOURCE parts）；URL 仅在渲染时通过 storage service 解析。 */
  blobId?: string
}

/** 由 ToolInvocation approvalJson 投影得到的、未决/已决的 Tool 审批状态。 */
/** 投影得到的审批状态；持久化的枚举是 ALLOWED/DENIED（而非输入的 ALLOW/DENY）。 */
export interface ToolApprovalState {
  required: boolean
  decision: 'ALLOWED' | 'DENIED' | null
  decisionId: string | null
  reason: string | null
}

/**
 * 工具结果的有序内容。
 *
 * 保持模型产出的类型与顺序：Text 忠实展示、JSON 格式化、Resource 按权威 MIME 渲染；
 * 展示层不得把它们压平成「全部文本 + 全部附件」。空文本与非法资源在投影时丢弃。
 */
export type ToolContent =
  | { type: 'text'; text: string }
  | { type: 'json'; value: unknown }
  | { type: 'resource'; attachment: ToolAttachment }

interface BaseDialogueMessage {
  id: string
  role: DialogueRole
  subjectEntryId: string | null
  createdAt: DialogueTimestamp
  status?: DialogueStatus
}

export type DialogueContent =
  | { type: 'text'; text: string }
  | { type: 'resource'; attachment: ToolAttachment }

export interface TextDialogueMessage extends BaseDialogueMessage {
  role: 'user' | 'assistant'
  text: string
  /**
   * 用户消息的有序内容块；存在时作为展示事实源，text 仅用于摘要。
   * 资源 URL 只在渲染时通过 storage service 解析。
   */
  contents?: DialogueContent[]
  // 可选的 assistant 思考文本。仅在本次 attempt 中 assistant 确实产生过思考时存在；
  // 纯文本响应及非 assistant 角色不会出现该字段。
  thinking?: string
  /**
   * 表示该 assistant 回合被用户通过 /stop 显式中断：其 text/thinking 反映的是
   * 停止那一刻的 snapshot，刷新后仍可见，但 UI 应当展示 "已停止" 标识，
   * 并抑制 realtime overlay。
   */
  aborted?: boolean
  metadata?: Record<string, unknown>
}

/** Model attempt 失败审计：partial 与具体错误分离，避免把 partial 当成错误 raw text。 */
export interface ModelAttemptFailureDialogueMessage extends BaseDialogueMessage {
  role: 'model_attempt_failure'
  attempt: number
  /** bigint-safe 的 canonical 非负十进制 sequence。 */
  sequence: string
  text: string
  thinking: string
  errorCode: string
  errorMessage: string
  failedAt: DialogueTimestamp
  retryAt: DialogueTimestamp
  /** 活动态为 attempt + 1；terminal ASSISTANT_ERROR 没有后续重试。 */
  nextAttempt: number | null
  /** 活动 snapshot 的稳定来源身份；durable Entry 不携带这些字段。 */
  modelInvocationId?: string
  turnStartEntryId?: string
  requestHeadEntryId?: string
}

export interface ToolDialogueMessage extends BaseDialogueMessage {
  role: 'tool'
  rendererKey: string
  phase: 'call' | 'result'
  toolCallId: string
  toolName: string
  arguments: string
  /** result 阶段的有序结果内容；call 阶段为空数组。 */
  contents: ToolContent[]
  status?: DialogueStatus
  errorMessage?: string
  /** 持久结果到达之前的瞬态有序内容；只有 text/json（runtime 禁止 partial 携带 Resource）。 */
  partialContents?: ToolContent[]
  partialErrorText?: string
  /** 持有此次调用持久状态（approval/partial）的 ToolInvocation id。 */
  invocationId?: string
  /** 原始 durable 状态；缺失时只是模型生成的调用，并非已经执行。 */
  invocationStatus?: string
  requiredEnvironmentId?: string | null
  waitingForEnvironment?: boolean
  requiredEnvironmentName?: string | null
  environmentWaitFreshnessAt?: InstantTimestamp | null
  /** 该 Tool 归属的 Thread id（用于归属解析）。 */
  threadId?: string
  /** 投影的审批状态（当 tool invocation 不带审批时为 null）。 */
  approval?: ToolApprovalState
  /**
   * 一次工具调用的严格身份：`${assistantEntryId}:${callIndex}`。
   * 相同 toolCallId 可以出现在不同 assistant Entry 上，配对和去重必须使用该身份。
   */
  callIdentity?: string
}

export interface MetaDialogueMessage extends BaseDialogueMessage {
  role: 'meta'
  kind: MetaMessageKind
  text: string
  /** TURN_END 后发射的类型化 usage；Branch Usage 只聚合该字段。无 usage 的纯回合结束 footer 不带该字段。 */
  turnUsage?: TurnUsage
  /**
   * 该回合真正关闭它的 TURN_END Entry id；由 TURN_END 绑定方填写。
   * 存在即表示为一次已关闭回合的 footer：无 usage 也必须展示结束信息与分支入口。
   */
  endEntryId?: string | null
  /** 可选结构化字段（token/费用等），便于以后扩展 */
  details?: Record<string, unknown>
}

/**
 * NOTIFICATION Entry 的权威 kind 与来源。kind 保留后端原文，未知值不得被假定为子代理结果。
 */
export interface EntryNotification {
  kind: string
  sourceThreadId: string | null
}

/**
 * FORK Entry 的来源事实：mode 为 BRANCH 或 SESSION，sourceEntryId 为切点 Entry，
 * sourceThreadId 仅在 SESSION fork 时非 null。
 */
export interface EntryFork {
  mode: string
  sourceEntryId: string | null
  sourceThreadId: string | null
}

/**
 * 持久 Entry 的独立投影，其语义并非对话回合。
 *
 * 让该形态独立于 Harness DTO，可被任何能提供稳定 timeline 契约的调用方复用 transcript。
 */
export interface EntryEventDialogueMessage extends BaseDialogueMessage {
  role: 'entry'
  kind: EntryEventKind
  title: string
  text: string
  /** 仅 NOTIFICATION Entry 携带的 kind 与来源；原始 payload 不再进入会话时间线。 */
  notification?: EntryNotification
  /** 仅 FORK Entry 携带的真实来源事实。 */
  fork?: EntryFork
}

export type DialogueMessage =
  | TextDialogueMessage
  | ModelAttemptFailureDialogueMessage
  | ToolDialogueMessage
  | MetaDialogueMessage
  | EntryEventDialogueMessage

/** 一次工具调用的严格身份。相同 toolCallId 跨 assistant Entry 时不能共用。 */
export function toolCallIdentity(assistantEntryId: string, callIndex: number): string {
  return `${assistantEntryId}:${callIndex}`
}

/**
 * 判断 call/result 是否同一次调用。
 * 任一侧带 callIdentity 时必须精确相同，并同时匹配 toolCallId 与 rendererKey。
 * 便携 panel 双方都没有身份时，才按 toolCallId + rendererKey 配对。
 */
export function sameToolCall(
  left: Pick<ToolDialogueMessage, 'callIdentity' | 'toolCallId' | 'rendererKey'>,
  right: Pick<ToolDialogueMessage, 'callIdentity' | 'toolCallId' | 'rendererKey'>,
): boolean {
  if (left.callIdentity || right.callIdentity) {
    return left.callIdentity === right.callIdentity
      && left.toolCallId === right.toolCallId
      && left.rendererKey === right.rendererKey
  }
  return Boolean(left.toolCallId)
    && left.toolCallId === right.toolCallId
    && left.rendererKey === right.rendererKey
}

/** 在持久 transcript 之外展示的 QUEUED mailbox 命令；sequence 保持十进制字符串。 */
export interface QueuedThreadMessage {
  /** 稳定客户端幂等键（DTO idempotencyKey）；也是 UI 渲染 key。 */
  idempotencyKey: string
  role: 'user'
  text: string
  sequence: string
}

export interface ThreadTimeline {
  messages: DialogueMessage[]
  /** 在持久 transcript 之外展示的可编辑 QUEUED USER_MESSAGE 命令。 */
  queuedMessages: QueuedThreadMessage[]
  /** 当 mailbox 中仍有排队的人类输入（USER_MESSAGE / GOAL）时为 true。 */
  hasPendingInputs: boolean
}

/** Portable Thread Panel 消费的结构化模型请求 Debug 投影；不依赖 API DTO。 */
export interface ThreadModelRequestDebugData {
  kind: 'NEXT_REQUEST_PREVIEW'
  generatedAt: string | number
  model: {
    providerName: string
    modelName: string
    variant: string
  }
  environmentName: string | null
  systemInstruction: string
  tools: ThreadModelRequestDebugTool[]
  skills: ThreadModelRequestDebugSkill[]
  subagents: ThreadModelRequestDebugSubagent[]
  cacheControl: ThreadModelRequestDebugCacheControl | null
  planningError: string | null
  frozenInvocation: ThreadModelRequestDebugFrozenInvocation | null
}

export interface ThreadModelRequestDebugTool {
  name: string
  description: string
  inputSchemaJson: string
  environmentSupport: 'NONE' | 'OPTIONAL' | 'REQUIRED'
  requiredEnvironmentId: string | null
  provenance: string
  state: 'SENT' | 'FILTERED'
  filterReason: string | null
}

export interface ThreadModelRequestDebugSkill {
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

export interface ThreadModelRequestDebugSubagent {
  name: string
  description: string
  tools: string[]
  skills: SkillRefDTO[]
  subagents: string[]
  configurationJson: string
}

export interface ThreadModelRequestDebugCacheControl {
  retention: 'NONE' | 'SHORT' | 'LONG'
  key: string | null
}

export interface ThreadModelRequestDebugFrozenInvocation {
  kind: 'FROZEN_INVOCATION'
  requestJson: string
}

/**
 * Portable Thread Panel 消费的请求预览投影；不依赖 API DTO。
 * kind 区分草稿（绑定或本地分支）与历史条目重放，notice 是服务端的如实说明。
 */
export interface ThreadProviderRequestPreviewData {
  kind: 'DRAFT_REQUEST_PREVIEW' | 'HISTORICAL_REQUEST_PREVIEW'
  providerType: string
  modelName: string
  bodyByteSize: number
  bodyJson: string
  sourceHeadEntryId: string | null
  generatedAt: string | number
  notice?: string | null
}
