import type { AgentSessionEventDTO } from '@/shared/api/contracts'
import { asRecord, getInteger, getRecordList, getString, getToolContentType } from '@/features/ai/session-event-payload'
import type {
  DialogueMessage,
  DialogueStatus,
  ToolAttachment,
  ToolAttachmentType,
  ToolDialogueMessage,
} from '@/features/ai/session-event-types'

export interface ToolProjectionState {
  toolCallId: string
  message: ToolDialogueMessage
  outputSlots: Map<number, ToolOutputSlot>
}

interface ToolOutputSlot {
  type?: 'text' | ToolAttachmentType
  text: string
  attachment?: ToolAttachment
}

export function startToolProjection(
  activeTools: Map<string, ToolProjectionState>,
  messages: DialogueMessage[],
  event: AgentSessionEventDTO,
  payload: Record<string, unknown>,
) {
  const toolCallId = getString(payload.toolCallId)
  const toolName = getString(payload.toolName)
  if (!toolCallId || !toolName) {
    return
  }

  const existing = activeTools.get(toolCallId)
  if (existing) {
    existing.message.status = 'done'
    activeTools.delete(toolCallId)
  }

  const state: ToolProjectionState = {
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
      arguments: getString(payload.arguments),
      attachments: [],
    },
    outputSlots: new Map(),
  }

  messages.push(state.message)
  activeTools.set(toolCallId, state)
}

export function appendToolDelta(
  activeTools: Map<string, ToolProjectionState>,
  payload: Record<string, unknown>,
) {
  const toolCallId = getString(payload.toolCallId)
  if (!toolCallId) {
    return
  }
  const state = activeTools.get(toolCallId)
  if (!state) {
    return
  }

  for (const indexedDelta of getRecordList(payload.contentDeltas)) {
    const slotIndex = getInteger(indexedDelta.index)
    if (slotIndex === null) {
      continue
    }
    const contentDelta = asRecord(indexedDelta.contentDelta)
    const contentType = getToolContentType(contentDelta.type)
    if (!contentType) {
      continue
    }

    const slot = state.outputSlots.get(slotIndex) ?? { text: '' }
    slot.type = contentType
    if (contentType === 'text') {
      slot.text += getString(contentDelta.text)
      slot.attachment = undefined
    } else {
      slot.attachment = {
        type: contentType,
        name: getString(contentDelta.name),
        mime: getString(contentDelta.mime),
        data: getString(contentDelta.data),
      }
    }
    state.outputSlots.set(slotIndex, slot)
  }

  syncToolMessage(state)
}

export function finalizeTool(
  activeTools: Map<string, ToolProjectionState>,
  messages: DialogueMessage[],
  event: AgentSessionEventDTO,
  payload: Record<string, unknown>,
  status: Extract<DialogueStatus, 'done' | 'error'>,
) {
  const toolCallId = getString(payload.toolCallId)
  if (!toolCallId) {
    return
  }
  const state = activeTools.get(toolCallId) ?? createSyntheticToolProjection(event, toolCallId)
  if (!activeTools.has(toolCallId)) {
    messages.push(state.message)
    activeTools.set(toolCallId, state)
  }

  if (status === 'error') {
    state.message.errorMessage = getString(payload.message) || 'Tool failed'
  }

  syncToolMessage(state)
  if (status === 'error' && !state.message.text.trim()) {
    state.message.text = state.message.errorMessage || 'Tool failed'
  }
  state.message.status = status
  activeTools.delete(toolCallId)
}

function createSyntheticToolProjection(event: AgentSessionEventDTO, toolCallId: string): ToolProjectionState {
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
      toolName: 'Tool',
      arguments: '',
      attachments: [],
    },
    outputSlots: new Map(),
  }
}

function syncToolMessage(state: ToolProjectionState) {
  const orderedSlots = Array.from(state.outputSlots.entries())
    .sort(([left], [right]) => left - right)
    .map(([, slot]) => slot)

  const textBlocks: string[] = []
  const attachments: ToolAttachment[] = []
  for (const slot of orderedSlots) {
    if (slot.type === 'text') {
      if (slot.text) {
        textBlocks.push(slot.text)
      }
      continue
    }
    if (slot.attachment?.data) {
      attachments.push(slot.attachment)
    }
  }

  state.message.text = textBlocks.join('\n\n')
  state.message.attachments = attachments
}
