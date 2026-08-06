import type { ModelInvocationDTO, ToolInvocationDTO } from '@/shared/api/contracts/ai-runtime'
import type { ToolAttachment } from '@/features/ai/runtime/thread-timeline-types'
import { toResourceAttachment } from '@/features/ai/runtime/thread-timeline/content-utils'

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
  /**
   * 'streaming' = RUNNING/checkpoint/delta overlay; 'done' = durable terminal resultJson
   * projection; 'error' = durable terminal errorJson projection (checkpoint frozen). The
   * timeline renders status, so a terminal projection is never marked streaming.
   */
  status: 'streaming' | 'done' | 'error'
  /** Error message parsed from the durable errorJson (present only for status='error'). */
  errorText?: string
}

export interface RealtimeToolPartial {
  threadId: string
  invocationId: string
  attempt: number
  /** Canonical partial ToolResult payload: {toolCallId, contents, error, details}. */
  payload: Record<string, unknown>
  createdAt: string
}

/** Transient tool-result overlay for one invocation attempt. */
export interface RealtimeToolStream {
  threadId: string
  invocationId: string
  attempt: number
  toolCallId: string
  text: string
  error: boolean
  /** Error message projected from the durable errorJson while no result Entry exists yet. */
  errorText?: string
  /**
   * Resource contents projected ONLY from the durable terminal resultJson. The runtime
   * forbids TOOL_PARTIAL chunks from carrying Resource contents, so partial aggregation
   * never contributes attachments.
   */
  attachments?: ToolAttachment[]
  createdAt: string
}

/** Canonical ToolResult JSON keys used for the partial overlay aggregation. */
const TOOL_RESULT_CONTENTS_KEY = 'contents'
const TOOL_RESULT_TOOL_CALL_ID_KEY = 'toolCallId'
const TOOL_RESULT_ERROR_KEY = 'error'

/**
 * Parses model text/thinking deltas and tool partial payloads from the Redis realtime overlay.
 * Other payloads still cause snapshot refreshes, but have no transient transcript representation.
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

/** Parses a TOOL_PARTIAL realtime event into its canonical payload (strict shape). */
export function parseRealtimeToolPartial(data: unknown): RealtimeToolPartial | null {
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
    envelope.type !== 'TOOL_PARTIAL'
    || envelope.subjectKind !== 'TOOL_INVOCATION'
    || !isNonBlankString(envelope.threadId)
    || !isNonBlankString(envelope.subjectId)
    || !isPositiveInteger(envelope.attempt)
    || !isNonBlankString(envelope.createdAt)
    || !isRecord(envelope.payload)
  ) {
    return null
  }
  return {
    threadId: envelope.threadId,
    invocationId: envelope.subjectId,
    attempt: envelope.attempt,
    payload: envelope.payload,
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
      status: 'streaming',
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
    status: 'streaming',
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

/**
 * Extracts the canonical durable stream checkpoint of one ModelInvocation (if any).
 * Mirrors the Java codec + record constructor: text/thinking accept ONLY string or null
 * (numbers/objects are malformed), and at least one of them must be non-blank.
 * Valid nulls normalize to empty strings here.
 */
export function parseStreamCheckpoint(
  json: string | null,
): { attempt: number; sequence: number; text: string; thinking: string } | null {
  if (json == null) {
    return null
  }
  try {
    const value: unknown = JSON.parse(json)
    if (!isRecord(value)) {
      return null
    }
    if (!isPositiveInteger(value.attempt) || !isNonNegativeInteger(value.sequence)) {
      return null
    }
    const text = nullableString(value.text)
    const thinking = nullableString(value.thinking)
    if (text === undefined || thinking === undefined) {
      // Malformed type: the Java codec's nullableText rejects anything but string/null.
      return null
    }
    const normalizedText = text ?? ''
    const normalizedThinking = thinking ?? ''
    if (!normalizedText.trim() && !normalizedThinking.trim()) {
      // The Java record constructor forbids both-blank checkpoints.
      return null
    }
    return {
      attempt: value.attempt,
      sequence: value.sequence,
      text: normalizedText,
      thinking: normalizedThinking,
    }
  } catch {
    return null
  }
}

/**
 * Derives the transient model overlay from the active ModelInvocation's durable checkpoint.
 *
 * - A RUNNING invocation whose checkpoint has not flushed yet (checkpoint flush does not bump
 *   the revision) yields an EMPTY base with sequence 0 so the first realtime delta is not
 *   permanently dropped; its attempt always matches invocation.attempt.
 * - A stale checkpoint whose attempt differs from invocation.attempt is ignored the same way.
 * - A terminal result/error whose resultEntryId is still null must NOT be mistaken for an
 *   applied durable Entry: the safe checkpoint overlay stays visible until resultEntryId is
 *   set (or the invocation disappears).
 */
export function snapshotModelStream(
  threadId: string,
  invocation: ModelInvocationDTO | null,
): RealtimeModelStream | null {
  if (invocation == null || invocation.threadId !== threadId) {
    return null
  }
  if (invocation.resultEntryId != null) {
    // Durable result Entry applied: the Entry is the transcript truth.
    return null
  }
  const checkpoint = parseStreamCheckpoint(invocation.streamCheckpointJson)
  const base = {
    threadId,
    invocationId: invocation.id,
    attempt: invocation.attempt,
    sequence: checkpoint?.attempt === invocation.attempt ? checkpoint.sequence : 0,
    text: '',
    thinking: '',
    createdAt: timestampString(invocation.updateTime) || timestampString(invocation.createTime),
  }
  const resultJson = invocation.resultJson
  if (resultJson != null && resultJson.trim()) {
    // Durable terminal boundary: the canonical ProviderResponse projection unconditionally
    // supersedes any higher-sequence Redis overlay. Text/thinking fall back to the frozen
    // checkpoint when the result payload is malformed (never a Redis-only fragment).
    const result = parseModelResultPayload(resultJson)
    return {
      ...base,
      text: result?.text ?? checkpoint?.text ?? '',
      thinking: result?.thinking ?? checkpoint?.thinking ?? '',
      status: 'done',
    }
  }
  const errorJson = invocation.errorJson
  if (errorJson != null && errorJson.trim()) {
    // Durable terminal error: freeze the checkpoint as the visible text and surface the
    // parsed message; the stream is 'error' so the UI never renders it as streaming.
    const message = parseToolErrorText(errorJson)
    return {
      ...base,
      text: checkpoint?.text ?? (message ?? ''),
      thinking: checkpoint?.thinking ?? '',
      status: 'error',
      errorText: message ?? undefined,
    }
  }
  if (checkpoint == null || checkpoint.attempt !== invocation.attempt) {
    // No flush yet or stale attempt: empty streaming base keeps the first delta of the
    // active attempt (checkpoint flush does not bump the revision).
    return { ...base, status: 'streaming' }
  }
  return {
    ...base,
    sequence: checkpoint.sequence,
    text: checkpoint.text,
    thinking: checkpoint.thinking,
    status: 'streaming',
  }
}

/**
 * Parses the canonical durable ProviderResponse JSON ({text, thinking, toolCalls, stopReason,
 * usage, cost}) into the complete terminal projection. Returns null only for malformed JSON;
 * empty text/thinking stay valid (the Java codec writes both keys unconditionally).
 */
function parseModelResultPayload(json: string): { text: string; thinking: string } | null {
  try {
    const value: unknown = JSON.parse(json)
    if (!isRecord(value)) {
      return null
    }
    const text = getString(value.text)
    const thinking = getString(value.thinking)
    return { text, thinking }
  } catch {
    return null
  }
}

/**
 * Derives the transient tool-result overlay from the active ToolInvocation (or null when the
 * durable result Entry applied or the invocation is gone).
 *
 * A terminal resultJson/errorJson with resultEntryId == null is projected in full (text/json
 * contents + resource attachments; error message) until the durable Tool result Entry appears.
 */
export function snapshotToolStream(
  invocation: ToolInvocationDTO | null,
  threadId: string,
): RealtimeToolStream | null {
  if (invocation == null) {
    return null
  }
  if (invocation.resultEntryId != null) {
    // Durable result Entry applied: the Entry is the transcript truth.
    return null
  }
  const base = {
    threadId,
    invocationId: invocation.id,
    attempt: invocation.attempt,
    toolCallId: invocation.toolCallId,
    text: '',
    error: false,
    createdAt: timestampString(invocation.updateTime) || timestampString(invocation.createTime),
  }
  const result = parseToolResultPayload(invocation.resultJson)
  if (result != null) {
    return {
      ...base,
      text: result.text,
      error: result.error,
      attachments: result.attachments,
    }
  }
  const errorText = parseToolErrorText(invocation.errorJson)
  if (errorText != null) {
    return { ...base, error: true, errorText }
  }
  return base
}

/** Parses the canonical ToolResult JSON into text + error flag + resource attachments. */
function parseToolResultPayload(json: string | null): {
  text: string
  error: boolean
  attachments: ToolAttachment[]
} | null {
  if (json == null || !json.trim()) {
    return null
  }
  try {
    const value: unknown = JSON.parse(json)
    if (!isRecord(value)) {
      return null
    }
    const contents = Array.isArray(value[TOOL_RESULT_CONTENTS_KEY])
      ? (value[TOOL_RESULT_CONTENTS_KEY] as unknown[])
      : []
    const text = partialText(value)
    const attachments: ToolAttachment[] = []
    for (const item of contents) {
      if (!isRecord(item)) {
        continue
      }
      attachments.push(...toResourceAttachment(item))
    }
    return { text, error: value[TOOL_RESULT_ERROR_KEY] === true, attachments }
  } catch {
    return null
  }
}

/** Parses the canonical error JSON into a display message (null when absent). */
export function parseToolErrorText(json: string | null): string | null {
  if (json == null || !json.trim()) {
    return null
  }
  try {
    const value: unknown = JSON.parse(json)
    if (isRecord(value)) {
      const message = value.message
      if (typeof message === 'string' && message.trim()) {
        return message
      }
    }
    return json
  } catch {
    return json
  }
}

/**
 * Aggregates one TOOL_PARTIAL chunk into the overlay: only text/json chunks append to text.
 * The runtime FORBIDS TOOL_PARTIAL from carrying Resource contents, so attachments never
 * aggregate here — they are projected exclusively from the durable terminal resultJson via
 * {@link snapshotToolStream}. A retry (attempt change) replaces all prior fragments.
 */
export function reduceRealtimeToolStream(
  current: RealtimeToolStream | null,
  partial: RealtimeToolPartial,
): RealtimeToolStream {
  const chunkText = partialText(partial.payload)
  if (
    current == null
    || current.threadId !== partial.threadId
    || current.invocationId !== partial.invocationId
    || current.attempt !== partial.attempt
  ) {
    return {
      threadId: partial.threadId,
      invocationId: partial.invocationId,
      attempt: partial.attempt,
      toolCallId: getString(partial.payload[TOOL_RESULT_TOOL_CALL_ID_KEY]) || '',
      text: chunkText,
      error: partial.payload[TOOL_RESULT_ERROR_KEY] === true,
      createdAt: partial.createdAt,
    }
  }
  return {
    ...current,
    text: current.text + chunkText,
    error: current.error || partial.payload[TOOL_RESULT_ERROR_KEY] === true,
    createdAt: partial.createdAt,
  }
}

/** True while the tool partial overlay belongs to the same invocation attempt and is not stale. */
export function isRealtimeToolStreamActive(
  current: RealtimeToolStream | null,
  invocation: ToolInvocationDTO | null,
): boolean {
  return current != null
    && invocation != null
    && current.invocationId === invocation.id
    && current.attempt === invocation.attempt
}

function partialText(payload: Record<string, unknown>): string {
  const contents = Array.isArray(payload[TOOL_RESULT_CONTENTS_KEY])
    ? (payload[TOOL_RESULT_CONTENTS_KEY] as unknown[])
    : []
  const parts: string[] = []
  for (const item of contents) {
    if (!isRecord(item)) {
      continue
    }
    const type = item.type
    if (type === 'text') {
      const text = getString(item.text)
      if (text) {
        parts.push(text)
      }
    } else if (type === 'json') {
      const json = stringifyJson(item.json)
      if (json) {
        parts.push(json)
      }
    }
  }
  return parts.join('\n')
}

function stringifyJson(value: unknown): string {
  if (typeof value === 'string') {
    return value
  }
  if (value == null) {
    return ''
  }
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value)
    } catch {
      return ''
    }
  }
  return String(value)
}

/** Strict Java `nullableText` semantics: only string or null are acceptable (undefined = malformed). */
function nullableString(value: unknown): string | null | undefined {
  if (typeof value === 'string' || value == null) {
    return value as string | null
  }
  return undefined
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

function timestampString(value: string | readonly number[] | null): string {
  if (typeof value === 'string' && value.trim()) {
    return value
  }
  return ''
}

function getString(value: unknown): string {
  return typeof value === 'string' ? value : ''
}
