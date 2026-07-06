import type { AgentSessionEventDTO } from '@/shared/api/contracts'
import { asRecord, getString, parsePayload } from '@/features/ai/session-event-payload'
import { appendAssistantDelta, beginAssistantAttempt, finalizeAssistant, type AssistantProjectionState } from '@/features/ai/session-timeline-assistant'
import { appendToolDelta, finalizeTool, startToolProjection, type ToolProjectionState } from '@/features/ai/session-timeline-tools'
import type { DialogueMessage, RuntimeContext, SessionTimeline } from '@/features/ai/session-event-types'

export function buildSessionTimeline(events: AgentSessionEventDTO[]): SessionTimeline {
  const messages: DialogueMessage[] = []
  const runtimeContext: RuntimeContext = {}
  const activeTools = new Map<string, ToolProjectionState>()
  let activeAssistant: AssistantProjectionState | null = null

  for (const event of events) {
    const payload = parsePayload(event.payloadJson)

    switch (event.eventType) {
      case 'user_message':
        messages.push({
          id: event.eventId,
          role: 'user',
          runId: event.runId,
          text: getString(payload.content),
          createdAt: event.createTime,
          status: 'done',
        })
        break
      case 'set_agent_info':
        runtimeContext.agentName = getString(payload.agentName) || runtimeContext.agentName
        break
      case 'set_model_info':
        runtimeContext.provider = getString(payload.provider) || runtimeContext.provider
        runtimeContext.model = getString(payload.model) || runtimeContext.model
        runtimeContext.variant = getString(payload.variant) || runtimeContext.variant
        break
      case 'assistant_start':
        activeAssistant = beginAssistantAttempt(activeAssistant, messages, event)
        break
      case 'assistant_delta':
        activeAssistant = appendAssistantDelta(activeAssistant, messages, event, getString(payload.textDelta))
        break
      case 'assistant_end':
        activeAssistant = finalizeAssistant(activeAssistant, messages, event, 'done', asRecord(payload.metadata))
        break
      case 'assistant_error':
        activeAssistant = finalizeAssistant(
          activeAssistant,
          messages,
          event,
          'error',
          undefined,
          getString(payload.message) || 'Assistant failed',
        )
        break
      case 'tool_start':
        startToolProjection(activeTools, messages, event, payload)
        break
      case 'tool_delta':
        appendToolDelta(activeTools, payload)
        break
      case 'tool_end':
        finalizeTool(activeTools, messages, event, payload, 'done')
        break
      case 'tool_error':
        finalizeTool(activeTools, messages, event, payload, 'error')
        break
    }
  }

  return { messages, runtimeContext }
}
