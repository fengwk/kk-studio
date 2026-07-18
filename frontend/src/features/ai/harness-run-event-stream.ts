import type { RunEventDTO } from '@/shared/api/contracts'

/** Jackson may emit sequence as a JSON string; always normalize to a finite integer. */
export function asRunEventSequence(value: unknown): number {
  if (typeof value === 'number' && Number.isInteger(value)) {
    return value
  }
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value)
    if (Number.isInteger(parsed)) {
      return parsed
    }
  }
  return Number.NaN
}

export function normalizeRunEvent(raw: Partial<RunEventDTO> | null | undefined): RunEventDTO | null {
  if (!raw) {
    return null
  }
  const sequence = asRunEventSequence(raw.sequence)
  if (!raw.eventId || !raw.runId || !raw.type || !Number.isInteger(sequence) || sequence <= 0) {
    return null
  }
  return {
    eventId: String(raw.eventId),
    runId: String(raw.runId),
    sequence,
    type: String(raw.type),
    payloadJson: typeof raw.payloadJson === 'string' ? raw.payloadJson : raw.payloadJson == null ? '' : String(raw.payloadJson),
    createTime: raw.createTime as RunEventDTO['createTime'],
  }
}

export function parseRunEvent(data: string): RunEventDTO | null {
  try {
    return normalizeRunEvent(JSON.parse(data) as Partial<RunEventDTO>)
  } catch {
    return null
  }
}

export function mergeRunEvent(events: RunEventDTO[], event: RunEventDTO): RunEventDTO[] {
  const normalized = normalizeRunEvent(event)
  if (!normalized) {
    return events
  }
  if (events.some((candidate) => candidate.sequence === normalized.sequence)) {
    return events
  }
  return [...events, normalized].sort((left, right) => left.sequence - right.sequence)
}

export function mergeRunEventLists(base: RunEventDTO[], incoming: RunEventDTO[]): RunEventDTO[] {
  return incoming.reduce(mergeRunEvent, base.map((event) => normalizeRunEvent(event)).filter((event): event is RunEventDTO => Boolean(event)))
}

export async function loadRunEventHistory(
  fetchPage: (afterSequence: number, limit: number) => Promise<RunEventDTO[]>,
  pageSize = 200,
): Promise<RunEventDTO[]> {
  let events: RunEventDTO[] = []
  let cursor = 0
  while (true) {
    const page = (await fetchPage(cursor, pageSize))
      .map((event) => normalizeRunEvent(event))
      .filter((event): event is RunEventDTO => Boolean(event))
    events = mergeRunEventLists(events, page)
    const nextCursor = page.reduce((maximum, event) => Math.max(maximum, event.sequence), cursor)
    if (page.length < pageSize || nextCursor <= cursor) {
      return events
    }
    cursor = nextCursor
  }
}
