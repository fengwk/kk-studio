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
  /** Stable URI (data:/http:/https:/file:/s3:/...); never a base64 payload on its own. */
  data: string
  /** Optional canonical preview text embedded in the resource content. */
  preview?: string
  size?: number | null
  sha256?: string | null
}

/** Undecided/decided Tool approval state projected from the ToolInvocation approvalJson. */
/** Projected approval state; the persisted enum is ALLOWED/DENIED (not the input ALLOW/DENY). */
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
  // Optional assistant thinking text. Only present when the assistant actually
  // emitted thinking during this attempt; absent for plain text responses and
  // for non-assistant roles.
  thinking?: string
  /**
   * True for assistant turns that the user explicitly stopped via /stop; their
   * text/thinking reflect the snapshot at stop time and remain visible after a
   * refresh, but the UI should render an "已停止" affordance and suppress the
   * realtime overlay.
   */
  aborted?: boolean
  metadata?: Record<string, unknown>
}

export interface ToolDialogueMessage extends BaseDialogueMessage {
  role: 'tool'
  phase: 'call' | 'result'
  text: string
  toolCallId: string
  toolName: string
  arguments: string
  attachments: ToolAttachment[]
  errorMessage?: string
  /** ToolInvocation id carrying this call's durable state (approval/partial). */
  invocationId?: string
  /** Transient TOOL_PARTIAL / terminal-result overlay attachments for the active call. */
  partialAttachments?: ToolAttachment[]
  /** Transient error message projected from errorJson while no durable result Entry exists. */
  partialErrorText?: string
  /** Transient TOOL_PARTIAL overlay aggregated for the active invocation attempt. */
  partial?: string
  /** Projected approval state (null when the tool invocation carries no approval). */
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
 * A standalone projection of a durable Entry whose semantics are not a dialogue turn.
 *
 * Keeping this shape independent of Harness DTOs makes the transcript reusable by any caller that
 * can provide the stable timeline contract.
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

/** QUEUED mailbox command shown outside the durable transcript; sequence stays a decimal string. */
export interface QueuedThreadMessage {
  commandId: string
  role: 'user' | 'system'
  text: string
  sequence: string
}

export interface ThreadTimeline {
  messages: DialogueMessage[]
  /** QUEUED USER_MESSAGE / CUSTOM_MESSAGE commands shown outside the durable transcript. */
  queuedMessages: QueuedThreadMessage[]
  /** True when the mailbox contains a queued user-visible command. */
  hasPendingInputs: boolean
}
