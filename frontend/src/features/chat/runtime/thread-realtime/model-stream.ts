import {
  hasOnlyFields,
  isNonBlankString,
  isNonNegativeInteger,
  isPositiveInteger,
  isRecord,
  nullableString,
  toEpochMillis,
} from '@/features/chat/runtime/thread-realtime/realtime-values'
import type {
  HarnessActiveAssistantOutputDTO,
  HarnessGenerationFailureDTO,
} from '@/shared/api/generated/contracts'

export interface RealtimeToolCallDraft {
  index: number
  id: string
  name: string
  argumentsJson: string
}

export interface RealtimeToolCallDelta {
  index: number
  id: string | null
  name: string | null
  argumentsJson: string | null
}

export interface RealtimeModelDelta {
  threadId: string
  invocationId: string
  attempt: number
  sequence: number
  kind: 'TEXT_DELTA' | 'THINKING_DELTA' | 'TOOL_CALL_DELTA'
  text: string
  toolCall?: RealtimeToolCallDelta
  createdAt: string
}

export interface RealtimeModelCheckpoint {
  threadId: string
  invocationId: string
  attempt: number
  /** durable checkpoint 覆盖到的 delta sequence（追帧完成信号）。 */
  sequence: number
  createdAt: string
}

export interface RealtimeModelStream {
  threadId: string
  invocationId: string
  attempt: number
  sequence: number
  text: string
  thinking: string
  toolCalls: RealtimeToolCallDraft[]
  createdAt: string
  /**
   * 'streaming' 表示 RUNNING/checkpoint/delta overlay；'done' 表示持久终止态 resultJson
   * 投影；'error' 表示持久终止态 errorJson 投影（checkpoint 已冻结）。timeline
   * 会渲染 status，因此终止态投影永远不会标记为 streaming。
   */
  status: 'streaming' | 'done' | 'error'
  /** 从持久 errorJson 解析出的 ProviderErrorKind（仅在 status='error' 时存在）。 */
  errorCode?: string
  /** 从持久 errorJson 解析出的错误消息（仅在 status='error' 时存在）。 */
  errorText?: string
}

/**
 * 从 lossy realtime overlay 中解析 model 文本/思考 delta 与 tool partial payload。
 * 其他 payload 仍会触发 snapshot 刷新，但不在 transcript 中保留瞬态表示。
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
  if (kind === 'TOOL_CALL_DELTA') {
    const toolCall = parseToolCallDeltaPayload(envelope.payload)
    if (toolCall === undefined) {
      return null
    }
    return {
      threadId: envelope.threadId,
      invocationId: envelope.subjectId,
      attempt: envelope.attempt,
      sequence: envelope.sequence,
      kind,
      text: '',
      toolCall: toolCall ?? undefined,
      createdAt: envelope.createdAt,
    }
  }
  if (kind !== 'TEXT_DELTA' && kind !== 'THINKING_DELTA') {
    return null
  }
  const text = envelope.payload.text
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

/** MODEL_CHECKPOINT 的 canonical envelope 字段集合；payload 与任何额外字段都不合法。 */
const MODEL_CHECKPOINT_FIELDS = [
  'type',
  'subjectKind',
  'threadId',
  'subjectId',
  'attempt',
  'sequence',
  'createdAt',
] as const

/**
 * 解析 canonical MODEL_CHECKPOINT envelope。checkpoint 没有 payload；sequence 是
 * durable checkpoint 已覆盖到的 delta 水位，attempt 必须与当前 active attempt 匹配。
 * 显式携带 payload（包括 null）或任何额外字段都视为格式错误。
 */
export function parseRealtimeModelCheckpoint(data: unknown): RealtimeModelCheckpoint | null {
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
    envelope.type !== 'MODEL_CHECKPOINT'
    || envelope.subjectKind !== 'MODEL_INVOCATION'
    || !isNonBlankString(envelope.threadId)
    || !isNonBlankString(envelope.subjectId)
    || !isPositiveInteger(envelope.attempt)
    || !isPositiveInteger(envelope.sequence)
    || !isNonBlankString(envelope.createdAt)
    || toEpochMillis(envelope.createdAt) == null
    || Object.hasOwn(envelope, 'payload')
    || !hasOnlyFields(envelope, MODEL_CHECKPOINT_FIELDS)
  ) {
    return null
  }
  return {
    threadId: envelope.threadId,
    invocationId: envelope.subjectId,
    attempt: envelope.attempt,
    sequence: envelope.sequence,
    createdAt: envelope.createdAt,
  }
}

function parseToolCallDeltaPayload(
  payload: Record<string, unknown>,
): RealtimeToolCallDelta | null | undefined {
  if (!isNonNegativeInteger(payload.index)) {
    return undefined
  }
  const id = nullableIdentifier(payload.id)
  const name = nullableIdentifier(payload.name)
  const argumentsJson = nullableString(payload.argumentsJson)
  if (id === undefined || name === undefined || argumentsJson === undefined) {
    return undefined
  }
  if (id == null && name == null && argumentsJson == null) {
    // 仅推进 sequence 的空 fragment，不创建 draft。
    return null
  }
  return { index: payload.index, id, name, argumentsJson }
}

function nullableIdentifier(value: unknown): string | null | undefined {
  if (value == null) {
    return null
  }
  if (typeof value !== 'string' || !value.trim()) {
    return undefined
  }
  return value
}

/**
 * 为最新的已知 ModelInvocation attempt 追加一条 delta。重试会替换掉它上一次
 * attempt 中所有的瞬态片段，确保失败 attempt 的输出不会泄露到当前回复中。
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
      toolCalls: applyToolCallDelta([], delta.toolCall),
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
    toolCalls: applyToolCallDelta(current.toolCalls, delta.toolCall),
    status: 'streaming',
  }
}

function mergeSnapshotToolCalls(
  snapshot: RealtimeModelStream,
  current: RealtimeModelStream | null,
): RealtimeModelStream {
  if (
    snapshot.toolCalls.length > 0
    || current == null
    || current.threadId !== snapshot.threadId
    || current.invocationId !== snapshot.invocationId
    || current.attempt !== snapshot.attempt
    || current.toolCalls.length === 0
  ) {
    return snapshot
  }
  return { ...snapshot, toolCalls: current.toolCalls }
}

function applyToolCallDelta(
  current: RealtimeToolCallDraft[],
  delta: RealtimeToolCallDelta | undefined,
): RealtimeToolCallDraft[] {
  if (delta == null) {
    return current
  }
  const next = current.map((item) => ({ ...item }))
  const existing = next.find((item) => item.index === delta.index)
  if (existing == null) {
    next.push({
      index: delta.index,
      id: delta.id ?? '',
      name: delta.name ?? '',
      argumentsJson: delta.argumentsJson ?? '',
    })
    next.sort((left, right) => left.index - right.index)
    return next
  }
  if (delta.id != null) {
    existing.id = mergeToolCallIdentity(existing.id, delta.id)
  }
  if (delta.name != null) {
    existing.name = mergeToolCallIdentity(existing.name, delta.name)
  }
  if (delta.argumentsJson != null) {
    existing.argumentsJson += delta.argumentsJson
  }
  return next
}

/**
 * Provider identity partials 是完整值或逐步延长的前缀，不是文本 delta。
 * 与后端 ModelStreamAccumulator 保持一致，重复的完整 name/id 不能被拼接。
 */
function mergeToolCallIdentity(current: string, incoming: string): string {
  if (!current) {
    return incoming
  }
  if (incoming === current || current.startsWith(incoming)) {
    return current
  }
  if (incoming.startsWith(current)) {
    return incoming
  }
  return current
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
 * 判断指定 generation attempt 是否已被公共 Snapshot 的 failed attempt 审计记录。
 * 仅按 generationId + 正整数 attempt 匹配，遵循 fail-safe 原则。
 */
export function isFailedGeneration(
  generationId: string | null | undefined,
  attempt: number,
  generationFailures: readonly HarnessGenerationFailureDTO[] = [],
): boolean {
  if (!generationId || !Number.isSafeInteger(attempt) || attempt <= 0) {
    return false
  }
  return generationFailures.some(
    (failure) =>
      failure?.generationId === generationId
      && failure.attempt === attempt,
  )
}

/** 公共输出状态是否为冻结终态；终态投影不再接受任何迟到 delta。 */
export function isTerminalAssistantOutputStatus(status: string): boolean {
  return status === 'SUCCEEDED'
    || status === 'FAILED'
    || status === 'CANCELLED'
    || status === 'BLOCKED'
}

/**
 * 从冻结的公共 activeAssistantOutput 投影瞬态 model overlay。
 *
 * <p>contents 只承载已完成的 text/thinking 片段，按服务端顺序拼接；sequence 是
 * canonical 非负十进制字符串，只有可安全表示的整数才接受。resultEntryId 到达后由
 * durable Entry 接管，投影立即消失。前端不再解析任何 Provider 原始 JSON。
 */
export function snapshotModelStream(
  threadId: string,
  activeOutput: HarnessActiveAssistantOutputDTO | null,
): RealtimeModelStream | null {
  if (
    activeOutput == null
    || !activeOutput.generationId.trim()
    || !Number.isSafeInteger(activeOutput.attempt)
    || activeOutput.attempt < 0
  ) {
    return null
  }
  if (activeOutput.resultEntryId != null) {
    // 已应用持久的 result Entry：Entry 才是 transcript 的真实来源。
    return null
  }
  const sequence = parseCanonicalSequence(activeOutput.sequence)
  if (sequence == null) {
    return null
  }
  let text = ''
  let thinking = ''
  for (const content of activeOutput.contents) {
    if (content.type === 'text') {
      text += content.text ?? ''
    } else if (content.type === 'thinking') {
      thinking += content.text ?? ''
    }
  }
  const failed = activeOutput.status === 'FAILED'
    || activeOutput.status === 'CANCELLED'
    || activeOutput.status === 'BLOCKED'
  return {
    threadId,
    invocationId: activeOutput.generationId,
    attempt: activeOutput.attempt,
    sequence,
    text,
    thinking,
    toolCalls: [],
    // HTTP 快照不携带流事件时间；之后的实时事件按各自的 createdAt 排序。
    createdAt: '',
    status: activeOutput.status === 'SUCCEEDED'
      ? 'done'
      : failed
        ? 'error'
        : 'streaming',
    ...(failed
      ? {
          errorCode: activeOutput.error?.code || undefined,
          errorText: activeOutput.error?.message || undefined,
        }
      : {}),
  }
}

/** canonical 非负十进制字符串 -> 安全整数；非规范或溢出值一律拒绝。 */
function parseCanonicalSequence(value: string): number | null {
  if (!/^(?:0|[1-9]\d*)$/.test(value)) {
    return null
  }
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) ? parsed : null
}

/**
 * 用 checkpoint 覆盖后的 snapshot 作为新基线，按 sequence 顺序拼接已缓存 suffix。
 *
 * <p>snapshot 落后于已显示 overlay 时保留 overlay，绝不回退；只有连续 sequence 能
 * 接上时才追加，任何缺口都通过 missingSequence 交给下一轮 checkpoint 恢复。
 */
export function mergeRealtimeModelStreamAfterCheckpoint(
  snapshot: RealtimeModelStream,
  current: RealtimeModelStream | null,
  buffered: readonly RealtimeModelDelta[],
  checkpointSequence: number,
): { stream: RealtimeModelStream; missingSequence: number | null } {
  const currentMatches =
    current != null
    && current.threadId === snapshot.threadId
    && current.invocationId === snapshot.invocationId
    && current.attempt === snapshot.attempt
  const base =
    currentMatches && current.sequence > snapshot.sequence
      ? current
      : mergeSnapshotToolCalls(snapshot, current)
  let stream = base
  let missingSequence: number | null = null
  const ordered = [...buffered]
    .filter(
      (delta) =>
        delta.threadId === snapshot.threadId
        && delta.invocationId === snapshot.invocationId
        && delta.attempt === snapshot.attempt,
    )
    .sort((left, right) => left.sequence - right.sequence)
  for (const delta of ordered) {
    if (delta.sequence <= stream.sequence) {
      continue
    }
    if (delta.sequence !== stream.sequence + 1) {
      missingSequence = stream.sequence + 1
      break
    }
    stream = reduceRealtimeModelStream(stream, delta)
  }
  if (missingSequence == null && stream.sequence < checkpointSequence) {
    missingSequence = stream.sequence + 1
  }
  return { stream, missingSequence }
}
