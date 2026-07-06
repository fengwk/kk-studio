import type { AgentSessionEventDTO } from '@/shared/api/contracts'
import type { ToolAttachment, ToolAttachmentType, ToolDialogueMessage } from '@/features/ai/session-event-types'

export interface ToolProjectionState {
  toolCallId: string
  message: ToolDialogueMessage
  outputSlots: Map<number, ToolOutputSlot>
}

export interface ToolOutputSlot {
  type?: 'text' | ToolAttachmentType
  text: string
  attachment?: ToolAttachment
}

export function createToolProjectionState(
  event: AgentSessionEventDTO,
  toolCallId: string,
  toolName: string,
  argumentsText: string,
): ToolProjectionState {
  return {
    toolCallId,
    message: {
      id: event.eventId,
      role: 'tool',
      runId: event.runId,
      createdAt: event.createTime,
      status: 'streaming',
      text: '',
      toolCallId,
      toolName,
      arguments: argumentsText,
      attachments: [],
    },
    outputSlots: new Map(),
  }
}

export function createSyntheticToolProjection(event: AgentSessionEventDTO, toolCallId: string): ToolProjectionState {
  return createToolProjectionState(event, toolCallId, 'Tool', '')
}
