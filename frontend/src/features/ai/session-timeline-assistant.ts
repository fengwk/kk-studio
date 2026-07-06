import type { AgentSessionEventDTO, BackendDateTime } from '@/shared/api/contracts'
import { ThinkTagTextFilter } from '@/features/ai/session-event-think-filter'
import type { DialogueMessage, DialogueStatus, TextDialogueMessage } from '@/features/ai/session-event-types'

export interface AssistantProjectionState {
  eventId: string
  runId: string | null
  createTime: BackendDateTime
  message?: TextDialogueMessage
  textFilter: ThinkTagTextFilter
}

export function beginAssistantAttempt(
  activeAssistant: AssistantProjectionState | null,
  messages: DialogueMessage[],
  event: AgentSessionEventDTO,
): AssistantProjectionState {
  finalizeAssistant(activeAssistant, messages, event, 'done')
  return newAssistantProjectionState(event)
}

export function appendAssistantDelta(
  activeAssistant: AssistantProjectionState | null,
  messages: DialogueMessage[],
  event: AgentSessionEventDTO,
  textDelta: string,
): AssistantProjectionState {
  const state = activeAssistant ?? newAssistantProjectionState(event)
  const visibleText = state.textFilter.append(textDelta)
  if (visibleText) {
    const message = ensureAssistantMessage(messages, state)
    message.text += visibleText
    message.status = 'streaming'
  }
  return state
}

export function finalizeAssistant(
  activeAssistant: AssistantProjectionState | null,
  messages: DialogueMessage[],
  event: AgentSessionEventDTO,
  status: DialogueStatus,
  metadata?: Record<string, unknown>,
  closingMessage?: string,
): null {
  const state = activeAssistant ?? newAssistantProjectionState(event)
  const visibleTail = state.textFilter.finish()
  if (visibleTail) {
    const message = ensureAssistantMessage(messages, state)
    message.text += visibleTail
  }

  if (!state.message) {
    if (status === 'error') {
      const message = ensureAssistantMessage(messages, state)
      message.text = closingMessage || 'Assistant failed'
      message.status = 'error'
      message.metadata = metadata
    }
    return null
  }

  if (closingMessage) {
    state.message.text = state.message.text ? `${state.message.text}\n${closingMessage}` : closingMessage
  }
  state.message.status = status
  state.message.metadata = metadata
  return null
}

function newAssistantProjectionState(event: AgentSessionEventDTO): AssistantProjectionState {
  return {
    eventId: event.eventId,
    runId: event.runId,
    createTime: event.createTime,
    textFilter: new ThinkTagTextFilter(),
  }
}

function ensureAssistantMessage(messages: DialogueMessage[], state: AssistantProjectionState): TextDialogueMessage {
  if (state.message) {
    return state.message
  }
  const created: TextDialogueMessage = {
    id: state.eventId,
    role: 'assistant',
    runId: state.runId,
    text: '',
    createdAt: state.createTime,
    status: 'streaming',
  }
  state.message = created
  messages.push(created)
  return created
}
