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

export async function loadRunEventHistory(
  fetchPage: (afterSequence: number, limit: number) => Promise<RunEventDTO[]>,
  pageSize = 200,
): Promise<RunEventDTO[]> {
  let events: RunEventDTO[] = []
  let cursor = 0
  while (true) {
    const page = await fetchPage(cursor, pageSize)
    events = mergeRunEventLists(events, page)
    const nextCursor = page.reduce((maximum, event) => Math.max(maximum, event.sequence), cursor)
    if (page.length < pageSize || nextCursor <= cursor) {
      return events
    }
    cursor = nextCursor
  }
}
