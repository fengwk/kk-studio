import type { ThreadEventDTO } from '@/shared/api/contracts'

/** Backend eventId contract: positive decimal string only (snowflake-safe). */
export function asEventId(value: unknown): string | null {
  if (typeof value === 'string' && /^[1-9]\d*$/.test(value.trim())) {
    return value.trim()
  }
  return null
}

export function normalizeThreadEvent(raw: Partial<ThreadEventDTO> | null | undefined): ThreadEventDTO | null {
  if (!raw) {
    return null
  }
  const eventId = asEventId(raw.eventId)
  // threadId is also a snowflake-safe positive decimal string; reject numbers.
  const threadId = asEventId(raw.threadId)
  if (!eventId || !threadId || !raw.eventType) {
    return null
  }
  let subjectEntryId: string | null = null
  if (raw.subjectEntryId != null && raw.subjectEntryId !== '') {
    // Non-null subjectEntryId must also be a positive decimal string.
    subjectEntryId = asEventId(raw.subjectEntryId)
    if (!subjectEntryId) {
      return null
    }
  }
  return {
    eventId,
    threadId,
    subjectEntryId,
    eventType: String(raw.eventType),
    payloadJson:
      typeof raw.payloadJson === 'string'
        ? raw.payloadJson
        : raw.payloadJson == null
          ? ''
          : String(raw.payloadJson),
    createTime: raw.createTime as ThreadEventDTO['createTime'],
  }
}

export function parseThreadEvent(data: string): ThreadEventDTO | null {
  try {
    return normalizeThreadEvent(JSON.parse(data) as Partial<ThreadEventDTO>)
  } catch {
    return null
  }
}

/** Decimal-string comparison for snowflake cursors (length first, then lexicographic). */
export function compareEventIds(left: string, right: string): number {
  if (left.length !== right.length) {
    return left.length - right.length
  }
  return left === right ? 0 : left < right ? -1 : 1
}

export function mergeThreadEvent(events: ThreadEventDTO[], event: ThreadEventDTO): ThreadEventDTO[] {
  const normalized = normalizeThreadEvent(event)
  if (!normalized) {
    return events
  }
  if (events.some((candidate) => candidate.eventId === normalized.eventId)) {
    return events
  }
  // REST pages and SSE frames are already ordered by the backend journal. Append only: repairing
  // order from IDs here would make the frontend a second, divergent ordering authority.
  return [...events, normalized]
}

export function mergeThreadEventLists(base: ThreadEventDTO[], incoming: ThreadEventDTO[]): ThreadEventDTO[] {
  return incoming.reduce(
    mergeThreadEvent,
    base
      .map((event) => normalizeThreadEvent(event))
      .filter((event): event is ThreadEventDTO => Boolean(event)),
  )
}

export async function loadThreadEventHistory(
  fetchPage: (afterEventId: string, limit: number) => Promise<ThreadEventDTO[]>,
  pageSize = 200,
  afterEventId = '0',
): Promise<ThreadEventDTO[]> {
  let events: ThreadEventDTO[] = []
  let cursor = afterEventId
  while (true) {
    const page = (await fetchPage(cursor, pageSize))
      .map((event) => normalizeThreadEvent(event))
      .filter((event): event is ThreadEventDTO => Boolean(event))
    events = mergeThreadEventLists(events, page)
    const nextCursor = page.at(-1)?.eventId ?? cursor
    if (page.length < pageSize || compareEventIds(nextCursor, cursor) <= 0) {
      return events
    }
    cursor = nextCursor
  }
}

/** Cursor of the last backend-ordered event; baseline `"0"` when empty. */
export function lastEventIdCursor(events: ThreadEventDTO[]): string {
  for (let index = events.length - 1; index >= 0; index -= 1) {
    const id = asEventId(events[index]?.eventId)
    if (id) {
      return id
    }
  }
  return '0'
}
