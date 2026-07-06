import type { AgentSessionEventDTO } from '@/shared/api/contracts'
import { getString } from '@/features/ai/session-event-payload'
import type { DialogueMessage, DialogueStatus } from '@/features/ai/session-event-types'
import {
  applyToolContentDeltas,
  syncToolMessage,
} from '@/features/ai/session-timeline-tool-output'
import {
  createSyntheticToolProjection,
  createToolProjectionState,
  type ToolProjectionState,
} from '@/features/ai/session-timeline-tool-state'

export type { ToolProjectionState } from '@/features/ai/session-timeline-tool-state'

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

  const state = createToolProjectionState(event, toolCallId, toolName, getString(payload.arguments))
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
  applyToolContentDeltas(state, payload)
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
