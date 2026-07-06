import type { BackendDateTime } from '@/shared/api/contracts'

export type DialogueRole = 'user' | 'assistant' | 'system' | 'tool'
export type DialogueStatus = 'streaming' | 'done' | 'error'
export type ToolAttachmentType = 'image' | 'audio' | 'video'

export interface ToolAttachment {
  type: ToolAttachmentType
  name: string
  mime: string
  data: string
}

interface BaseDialogueMessage {
  id: string
  role: DialogueRole
  runId: string | null
  createdAt: BackendDateTime
  status?: DialogueStatus
}

export interface TextDialogueMessage extends BaseDialogueMessage {
  role: 'user' | 'assistant' | 'system'
  text: string
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

export interface RuntimeContext {
  provider?: string
  model?: string
  variant?: string
  agentName?: string
}

export interface SessionTimeline {
  messages: DialogueMessage[]
  runtimeContext: RuntimeContext
}
