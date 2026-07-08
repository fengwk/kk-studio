import type { AgentSessionEventDTO, BackendDateTime } from '@/shared/api/contracts'
import { ThinkTagTextFilter } from '@/features/ai/session-event-think-filter'
import type { DialogueMessage, DialogueStatus, TextDialogueMessage } from '@/features/ai/session-event-types'

export interface AssistantProjectionState {
  eventId: string
  runId: string | null
  createTime: BackendDateTime
  message?: TextDialogueMessage
  textFilter: ThinkTagTextFilter
  // Buffer for thinking deltas that arrive before the first visible text delta.
  // Flushed into `message.thinking` when the text appender promotes the attempt
  // to a visible message. Preserved untouched when the attempt never emits text,
  // so thinking-only attempts still do not produce a bubble.
  pendingThinking: string
}

export function beginAssistantAttempt(
  activeAssistant: AssistantProjectionState | null,
  messages: DialogueMessage[],
  event: AgentSessionEventDTO,
): AssistantProjectionState {
  finalizeAssistant(activeAssistant, messages, event, 'done')
  return newAssistantProjectionState(event)
}

// Append visible assistant text. textDelta may contain inline <think>...</think>
// tags from providers that mix thinking into content; the filter strips them so
// the user-visible text never leaks reasoning. Also flushes any pendingThinking
// that arrived before the first text delta for this attempt.
export function appendAssistantTextDelta(
  activeAssistant: AssistantProjectionState | null,
  messages: DialogueMessage[],
  event: AgentSessionEventDTO,
  textDelta: string,
): AssistantProjectionState {
  const state = activeAssistant ?? newAssistantProjectionState(event)
  const visibleText = state.textFilter.append(textDelta)
  if (visibleText) {
    const message = ensureAssistantMessage(messages, state)
    if (!message.thinking && state.pendingThinking) {
      message.thinking = state.pendingThinking
      state.pendingThinking = ''
    }
    message.text += visibleText
    message.status = 'streaming'
  }
  return state
}

// Buffer assistant thinking. Thinking-only attempts never reach the message
// creation path, so they remain absent from the transcript (matching the
// existing `drops assistant attempts that never produce visible text` contract).
// Thinking that arrives before the first text delta is held in state.pendingThinking
// and flushed when text promotes the attempt to a visible message.
export function appendAssistantThinkingDelta(
  activeAssistant: AssistantProjectionState | null,
  messages: DialogueMessage[],
  event: AgentSessionEventDTO,
  thinkingDelta: string,
): AssistantProjectionState {
  if (!thinkingDelta) {
    return activeAssistant ?? newAssistantProjectionState(event)
  }
  const state = activeAssistant ?? newAssistantProjectionState(event)
  if (state.message) {
    state.message.thinking = (state.message.thinking ?? '') + thinkingDelta
  } else {
    state.pendingThinking += thinkingDelta
  }
  // `messages` is intentionally unused; thinking-only updates do not promote
  // the attempt to a visible message. The parameter is kept to match the
  // sibling text appender signature.
  void messages
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
    if (!message.thinking && state.pendingThinking) {
      message.thinking = state.pendingThinking
      state.pendingThinking = ''
    }
    message.text += visibleTail
  }

  if (!state.message) {
    if (status === 'error') {
      const message = ensureAssistantMessage(messages, state)
      if (state.pendingThinking) {
        message.thinking = state.pendingThinking
        state.pendingThinking = ''
      }
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
    pendingThinking: '',
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
