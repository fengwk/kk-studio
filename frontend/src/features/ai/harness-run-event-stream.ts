import type { RunEventDTO } from '@/shared/api/contracts'

export function parseRunEvent(data: string): RunEventDTO | null {
  try {
    const event = JSON.parse(data) as Partial<RunEventDTO>
    if (
      !event.eventId
      || !event.runId
      || !event.type
      || !Number.isInteger(event.sequence)
      || (event.sequence ?? -1) <= 0
    ) {
      return null
    }
    return event as RunEventDTO
  } catch {
    return null
  }
}

export function mergeRunEvent(events: RunEventDTO[], event: RunEventDTO): RunEventDTO[] {
  if (events.some((candidate) => candidate.sequence === event.sequence)) {
    return events
  }
  return [...events, event].sort((left, right) => left.sequence - right.sequence)
}

export function mergeRunEventLists(base: RunEventDTO[], incoming: RunEventDTO[]): RunEventDTO[] {
  return incoming.reduce(mergeRunEvent, base)
}
