import type { BackendDateTime } from '@/shared/api/contracts'

export type DialogueRole = 'user' | 'assistant' | 'system' | 'tool'
export type DialogueStatus = 'streaming' | 'done' | 'error'
export type ToolAttachmentType = 'image' | 'audio' | 'video' | 'file'

export interface ToolAttachment {
  type: ToolAttachmentType
  name: string
  mime: string
  data: string
}

interface BaseDialogueMessage {
  id: string
  role: DialogueRole
  subjectEntryId: string | null
  createdAt: BackendDateTime
  status?: DialogueStatus
}

export interface TextDialogueMessage extends BaseDialogueMessage {
  role: 'user' | 'assistant' | 'system'
  text: string
  // Optional assistant thinking text. Only present when the assistant actually
  // emitted thinking during this attempt; absent for plain text responses and
  // for non-assistant roles.
  thinking?: string
  metadata?: Record<string, unknown>
}

export interface ToolDialogueMessage extends BaseDialogueMessage {
  role: 'tool'
  text: string
  toolCallId: string
  toolName: string
  arguments: string
  attachments: ToolAttachment[]
  errorMessage?: string
}

export type DialogueMessage = TextDialogueMessage | ToolDialogueMessage

export interface QueuedThreadMessage {
  inputId: string
  role: 'user' | 'system'
  text: string
  sequence: number
}

export interface ThreadTimeline {
  messages: DialogueMessage[]
  /** QUEUED mailbox messages shown outside the durable transcript. */
  queuedMessages: QueuedThreadMessage[]
  /** True when the mailbox contains a queued user-visible input. */
  hasPendingInputs: boolean
  /** True only for active open stream work (open assistant or streaming tool). */
  hasLiveProjection: boolean
}
