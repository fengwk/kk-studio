import type { ModelInvocationDTO } from '@/shared/api/contracts'

export interface RealtimeModelDelta {
  threadId: string
  invocationId: string
  attempt: number
  sequence: number
  kind: 'TEXT_DELTA' | 'THINKING_DELTA' | 'TOOL_CALL_DELTA'
  text: string
  createdAt: string
}

export interface RealtimeModelStream {
  threadId: string
  invocationId: string
  attempt: number
  sequence: number
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
    || !isPositiveInteger(envelope.sequence)
    || !isNonBlankString(envelope.createdAt)
    || toEpochMillis(envelope.createdAt) == null
    || !isRecord(envelope.payload)
  ) {
    return null
  }
  const kind = envelope.payload.kind
  if (
    kind !== 'TEXT_DELTA'
    && kind !== 'THINKING_DELTA'
    && kind !== 'TOOL_CALL_DELTA'
  ) {
    return null
  }
  const text = kind === 'TOOL_CALL_DELTA' ? '' : envelope.payload.text
  if (typeof text !== 'string') {
    return null
  }
  return {
    threadId: envelope.threadId,
    invocationId: envelope.subjectId,
    attempt: envelope.attempt,
    sequence: envelope.sequence,
    kind,
    text,
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
    || current.attempt !== delta.attempt
  ) {
    return {
      threadId: delta.threadId,
      invocationId: delta.invocationId,
      attempt: delta.attempt,
      sequence: delta.sequence,
      text: delta.kind === 'TEXT_DELTA' ? delta.text : '',
      thinking: delta.kind === 'THINKING_DELTA' ? delta.text : '',
      createdAt: delta.createdAt,
    }
  }
  if (delta.sequence <= current.sequence || delta.sequence !== current.sequence + 1) {
    return current
  }
  return {
    ...current,
    sequence: delta.sequence,
    text: delta.kind === 'TEXT_DELTA' ? current.text + delta.text : current.text,
    thinking: delta.kind === 'THINKING_DELTA' ? current.thinking + delta.text : current.thinking,
  }
}

export function isRealtimeModelDeltaGap(
  current: RealtimeModelStream | null,
  delta: RealtimeModelDelta,
): boolean {
  return current != null
    && current.threadId === delta.threadId
    && current.invocationId === delta.invocationId
    && current.attempt === delta.attempt
    && delta.sequence > current.sequence + 1
}

export function snapshotModelStream(
  threadId: string,
  invocations: ModelInvocationDTO[],
): RealtimeModelStream | null {
  const invocation = invocations
    .filter(
      (item) =>
        item.threadId === threadId
        && item.appliedAt == null
        && (item.status === 'RUNNING' || item.status === 'SUCCEEDED'),
    )
    .sort((left, right) => compareDecimalIdsDescending(left.id, right.id))[0]
  if (invocation == null) {
    return null
  }
  const snapshot = parseSafeStreamSnapshot(invocation.safeStreamSnapshotJson)
  if (
    snapshot == null
    && (invocation.safeStreamSnapshotJson != null || invocation.status !== 'RUNNING')
  ) {
    return null
  }
  return {
    threadId,
    invocationId: invocation.id,
    attempt: invocation.attempt,
    sequence: snapshot?.sequence ?? 0,
    text: snapshot?.text ?? '',
    thinking: snapshot?.thinking ?? '',
    createdAt: timestampString(invocation.startedAt) || timestampString(invocation.createdAt),
  }
}

export function parseSafeStreamSnapshot(
  json: string | null,
): { text: string; thinking: string; sequence: number } | null {
  if (json == null) {
    return null
  }
  try {
    const value: unknown = JSON.parse(json)
    if (!isRecord(value) || !hasExactKeys(value, ['text', 'thinking', 'sequence'])) {
      return null
    }
    if (
      typeof value.text !== 'string'
      || typeof value.thinking !== 'string'
      || !isNonNegativeInteger(value.sequence)
    ) {
      return null
    }
    return { text: value.text, thinking: value.thinking, sequence: value.sequence }
  } catch {
    return null
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null && !Array.isArray(value)
}

function isNonBlankString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function toEpochMillis(value: unknown): number | null {
  if (typeof value !== 'string' || !value.trim()) {
    return null
  }
  const parsed = Date.parse(value)
  return Number.isFinite(parsed) ? parsed : null
}

function isPositiveInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
}

function isNonNegativeInteger(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
}

function hasExactKeys(value: Record<string, unknown>, expected: string[]): boolean {
  const keys = Object.keys(value)
  return keys.length === expected.length && expected.every((key) => key in value)
}

function compareDecimalIdsDescending(left: string, right: string): number {
  const leftId = BigInt(left)
  const rightId = BigInt(right)
  return leftId === rightId ? 0 : leftId > rightId ? -1 : 1
}

function timestampString(value: unknown): string {
  return typeof value === 'string' ? value : ''
}
