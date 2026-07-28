import type { HarnessSessionEntryDTO } from '@/shared/api/contracts'

export interface RealtimeModelDelta {
  threadId: string
  invocationId: string
  attempt: number
  kind: 'TEXT_DELTA' | 'THINKING_DELTA'
  text: string
  createdAt: string
}

export interface RealtimeModelStream {
  threadId: string
  invocationId: string
  attempt: number
  text: string
  thinking: string
  createdAt: string
}

/**
 * Parses only model text/thinking deltas. Other realtime payloads still cause snapshot refreshes,
 * but do not have a transient transcript representation.
 */
export function parseRealtimeModelDelta(data: unknown): RealtimeModelDelta | null {
  if (typeof data !== 'string') {
    return null
  }
  let envelope: Record<string, unknown>
  try {
    const parsed: unknown = JSON.parse(data)
    if (!isRecord(parsed)) {
      return null
    }
    envelope = parsed
  } catch {
    return null
  }
  if (
    envelope.type !== 'MODEL_DELTA'
    || envelope.subjectKind !== 'MODEL_INVOCATION'
    || !isNonBlankString(envelope.threadId)
    || !isNonBlankString(envelope.subjectId)
    || !isPositiveInteger(envelope.attempt)
    || !isNonBlankString(envelope.createdAt)
    || toEpochMillis(envelope.createdAt) == null
    || !isRecord(envelope.payload)
  ) {
    return null
  }
  const kind = envelope.payload.kind
  if ((kind !== 'TEXT_DELTA' && kind !== 'THINKING_DELTA') || typeof envelope.payload.text !== 'string') {
    return null
  }
  return {
    threadId: envelope.threadId,
    invocationId: envelope.subjectId,
    attempt: envelope.attempt,
    kind,
    text: envelope.payload.text,
    createdAt: envelope.createdAt,
  }
}

/**
 * Appends a delta for the newest known ModelInvocation attempt. A retry replaces all transient
 * fragments from its prior attempt so failed-attempt output never leaks into the active reply.
 */
export function reduceRealtimeModelStream(
  current: RealtimeModelStream | null,
  delta: RealtimeModelDelta,
): RealtimeModelStream {
  if (
    current == null
    || current.threadId !== delta.threadId
    || current.invocationId !== delta.invocationId
    || delta.attempt > current.attempt
  ) {
    return {
      threadId: delta.threadId,
      invocationId: delta.invocationId,
      attempt: delta.attempt,
      text: delta.kind === 'TEXT_DELTA' ? delta.text : '',
      thinking: delta.kind === 'THINKING_DELTA' ? delta.text : '',
      createdAt: delta.createdAt,
    }
  }
  if (delta.attempt < current.attempt) {
    return current
  }
  return {
    ...current,
    text: delta.kind === 'TEXT_DELTA' ? current.text + delta.text : current.text,
    thinking: delta.kind === 'THINKING_DELTA' ? current.thinking + delta.text : current.thinking,
  }
}

/**
 * The durable path is authoritative. Once it contains an assistant outcome created after the
 * transient stream began, retaining the overlay would duplicate that turn.
 */
export function isRealtimeModelStreamCommitted(
  stream: RealtimeModelStream,
  entries: HarnessSessionEntryDTO[],
): boolean {
  const streamTime = toEpochMillis(stream.createdAt)
  if (streamTime == null) {
    return false
  }
  return entries.some((entry) => {
    if (!isDurableModelOutcome(entry)) {
      return false
    }
    const entryTime = toEpochMillis(entry.createTime)
    return entryTime != null && entryTime >= streamTime
  })
}

function isDurableModelOutcome(entry: HarnessSessionEntryDTO): boolean {
  if (entry.entryType === 'ASSISTANT_ERROR') {
    return true
  }
  if (entry.entryType !== 'MESSAGE') {
    return false
  }
  try {
    const payload: unknown = JSON.parse(entry.payloadJson)
    return isRecord(payload) && isRecord(payload.message) && payload.message.role === 'ASSISTANT'
  } catch {
    return false
  }
}

function toEpochMillis(value: unknown): number | null {
  if (Array.isArray(value)) {
    const [year, month, day, hour = 0, minute = 0, second = 0, nanos = 0] = value
    if (
      ![year, month, day, hour, minute, second, nanos].every(
        (part) => typeof part === 'number' && Number.isFinite(part),
      )
    ) {
      return null
    }
    return Date.UTC(year, month - 1, day, hour, minute, second, Math.floor(nanos / 1_000_000))
  }
  if (typeof value !== 'string' || !value.trim()) {
    return null
  }
  // Spring serializes the PostgreSQL UTC LocalDateTime without an offset. Interpret that wire
  // value as UTC instead of the browser's local zone before comparing it to event Instants.
  const normalized = /(?:Z|[+-]\d{2}:\d{2})$/i.test(value) ? value : `${value}Z`
  const parsed = Date.parse(normalized)
  return Number.isFinite(parsed) ? parsed : null
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null && !Array.isArray(value)
}

function isNonBlankString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function isPositiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}
