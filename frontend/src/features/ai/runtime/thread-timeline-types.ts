type DialogueRole = 'user' | 'assistant' | 'system' | 'tool' | 'meta' | 'entry'
type DialogueStatus = 'streaming' | 'done' | 'error'
type DialogueTimestamp = string | readonly number[] | null
export type ToolAttachmentType = 'image' | 'audio' | 'video' | 'file'
/** 控制面/回合摘要等特殊消息，与 user/assistant/tool 正文区分 */
type MetaMessageKind = 'turn_usage'
/** Durable Entry 的非对话审计事件；未知值也必须留在时间线中。 */
export type EntryEventKind =
  | 'root'
  | 'empty_message'
  | 'unsupported_message'
  | 'unknown_entry'

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

interface BaseDialogueMessage {
  id: string
  role: DialogueRole
  subjectEntryId: string | null
  createdAt: DialogueTimestamp
  status?: DialogueStatus
}

export interface TextDialogueMessage extends BaseDialogueMessage {
  role: 'user' | 'assistant' | 'system'
  text: string
  /**
   * 可选的 durable 附件（user 消息的 RESOURCE parts 投影）；预览/下载 URL
   * 只在渲染时通过 storage service 解析。
   */
  attachments?: ToolAttachment[]
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

export interface ToolDialogueMessage extends BaseDialogueMessage {
  role: 'tool'
  rendererKey: string
  phase: 'call' | 'result'
  text: string
  toolCallId: string
  toolName: string
  arguments: string
  attachments: ToolAttachment[]
  status?: DialogueStatus
  errorMessage?: string
  partialAttachments?: ToolAttachment[]
  partialErrorText?: string
  partial?: string
  /** 持有此次调用持久状态（approval/partial）的 ToolInvocation id。 */
  invocationId?: string
  /** 投影的审批状态（当 tool invocation 不带审批时为 null）。 */
  approval?: ToolApprovalState
}

export interface MetaDialogueMessage extends BaseDialogueMessage {
  role: 'meta'
  kind: MetaMessageKind
  text: string
  /** 可选结构化字段（token/费用等），便于以后扩展 */
  details?: Record<string, unknown>
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
  rawPayloadJson: string
}

export type DialogueMessage =
  | TextDialogueMessage
  | ToolDialogueMessage
  | MetaDialogueMessage
  | EntryEventDialogueMessage

/** 在持久 transcript 之外展示的 QUEUED mailbox 命令；sequence 保持十进制字符串。 */
export interface QueuedThreadMessage {
  /** 稳定客户端幂等键（DTO clientCommandId）；也是 UI 渲染 key。 */
  clientCommandId: string
  role: 'user' | 'system'
  text: string
  sequence: string
}

export interface ThreadTimeline {
  messages: DialogueMessage[]
  /** 在持久 transcript 之外展示的 QUEUED USER_MESSAGE / CUSTOM_MESSAGE 命令。 */
  queuedMessages: QueuedThreadMessage[]
  /** 当 mailbox 中仍有用户可见的排队命令时为 true。 */
  hasPendingInputs: boolean
}
