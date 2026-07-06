import type { AgentSessionEventDTO } from '@/shared/api/contracts'

export function parseSessionEvent(data: string): AgentSessionEventDTO | null {
  try {
    const parsed = JSON.parse(data) as Partial<AgentSessionEventDTO>
    if (!parsed.eventId || !parsed.sessionId || !parsed.eventType) {
      return null
    }
    return parsed as AgentSessionEventDTO
  } catch {
    return null
  }
}

export function mergeSessionEvent(events: AgentSessionEventDTO[], event: AgentSessionEventDTO): AgentSessionEventDTO[] {
  if (events.some((existing) => existing.eventId === event.eventId)) {
    return events
  }
  return [...events, event]
}

export function mergeSessionEventLists(baseEvents: AgentSessionEventDTO[], incomingEvents: AgentSessionEventDTO[]): AgentSessionEventDTO[] {
  let merged = baseEvents
  for (const event of incomingEvents) {
    merged = mergeSessionEvent(merged, event)
  }
  return merged
}
