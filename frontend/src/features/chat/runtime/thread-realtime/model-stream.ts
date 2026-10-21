import type {
  ModelAttemptFailureDTO,
  ModelInvocationDTO,
} from '@/shared/api/contracts/ai-runtime'
import {
  getString,
  hasOnlyFields,
  isNonBlankString,
  isNonNegativeInteger,
  isPositiveInteger,
  isRecord,
  nullableString,
  timestampString,
  toEpochMillis,
} from '@/features/chat/runtime/thread-realtime/realtime-values'
import {
  parseToolErrorText,
} from '@/features/chat/runtime/thread-realtime/tool-stream'

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
 * 判断指定 attempt 是否属于当前 invocation 已在 Snapshot 中由 durable failedAttempts 记录的旧 attempt。
 * 仅按 modelInvocationId + 正整数 attempt 匹配，遵循 fail-safe 原则。
 */
export function isFailedAttempt(
  attempt: number,
  invocation: ModelInvocationDTO | null,
  modelAttemptFailures: readonly ModelAttemptFailureDTO[] = [],
): boolean {
  if (invocation == null || !Number.isSafeInteger(attempt) || attempt <= 0) {
    return false
  }
  return modelAttemptFailures.some(
    (failure) =>
      failure?.modelInvocationId === invocation.id
      && failure.attempt === attempt,
  )
}

/**
 * 提取一次 ModelInvocation 规范的持久流式 checkpoint（如果有）。
 * 与 Java codec + record 构造函数保持一致：text/thinking 仅接受 string 或 null
 * （数字/对象视为格式错误），且二者至少有一个必须非空；纯空白是合法内容。
 * 合法的 null 在此处统一规范化为空字符串。
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
      // 类型不合法：Java codec 的 nullableText 仅接受 string 或 null。
      return null
    }
    const normalizedText = text ?? ''
    const normalizedThinking = thinking ?? ''
    if (!normalizedText && !normalizedThinking) {
      // Java record 构造函数只禁止两侧均为空；不会裁剪合法空白。
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
 * 从活动 ModelInvocation 的持久 checkpoint 中派生瞬态 model overlay。
 *
 * - RUNNING 状态的 invocation 若 checkpoint 尚未 flush（checkpoint flush 不会
 *   提升 version），会得到一个 sequence 为 0 的空 base，确保第一条
 *   realtime delta 不会被永久丢弃；其 attempt 始终等于 invocation.attempt。
 * - 若 checkpoint 的 attempt 与 invocation.attempt 不一致，同样视为陈旧并忽略。
 * - 终止态的 result/error 若 resultEntryId 仍为 null，绝不能误以为是已落地的
 *   持久 Entry：安全起见，checkpoint overlay 会一直保留，直到 resultEntryId 被
 *   设置（或 invocation 消失）。
 */
export function snapshotModelStream(
  threadId: string,
  invocation: ModelInvocationDTO | null,
): RealtimeModelStream | null {
  if (invocation == null || invocation.threadId !== threadId) {
    return null
  }
  if (invocation.resultEntryId != null) {
    // 已应用持久的 result Entry：Entry 才是 transcript 的真实来源。
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
    toolCalls: [],
    createdAt: timestampString(invocation.updateTime) || timestampString(invocation.createTime),
  }
  const resultJson = invocation.resultJson
  if (resultJson != null && resultJson.trim()) {
    // 持久终止态边界：规范的 ProviderResponse 投影无条件覆盖
    // 任何更高 sequence 的 lossy realtime overlay。当 result payload 格式不合法时，
    // text/thinking 回退到冻结的 checkpoint（绝不是仅有 lossy realtime delta 的片段）。
    const result = parseModelResultPayload(resultJson)
    return {
      ...base,
      text: result?.text ?? checkpoint?.text ?? '',
      thinking: result?.thinking ?? checkpoint?.thinking ?? '',
      toolCalls: result?.toolCalls ?? [],
      status: 'done',
    }
  }
  const errorJson = invocation.errorJson
  if (errorJson != null && errorJson.trim()) {
    // 持久终止态错误：checkpoint 仍是用户已见 partial，错误 kind/message
    // 单独携带；timeline 决定 FAILED/UNKNOWN/CANCELLED 的最终可见形态。
    const error = parseModelErrorPayload(errorJson)
    return {
      ...base,
      text: checkpoint?.text ?? '',
      thinking: checkpoint?.thinking ?? '',
      status: 'error',
      errorCode: error?.code || undefined,
      errorText: error?.message ?? parseToolErrorText(errorJson) ?? undefined,
    }
  }
  if (checkpoint == null || checkpoint.attempt !== invocation.attempt) {
    // 尚未 flush 或 attempt 已陈旧：保持空的 streaming base，以确保活动
    // attempt 的第一条 delta 不丢失（checkpoint flush 不会提升 version）。
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

/**
 * 将规范的持久 ProviderResponse JSON（{text, thinking, toolCalls, stopReason,
 * usage, cost}）解析为完整的终止态投影。仅在 JSON 格式不合法时返回 null；
 * 空 text/thinking 视为有效（Java codec 会无条件写入这两个键）。
 */
function parseModelResultPayload(json: string): {
  text: string
  thinking: string
  toolCalls: RealtimeToolCallDraft[]
} | null {
  try {
    const value: unknown = JSON.parse(json)
    if (!isRecord(value)) {
      return null
    }
    return {
      text: getString(value.text),
      thinking: getString(value.thinking),
      toolCalls: parseCompletedToolCalls(value.toolCalls),
    }
  } catch {
    return null
  }
}

function parseCompletedToolCalls(value: unknown): RealtimeToolCallDraft[] {
  if (!Array.isArray(value)) {
    return []
  }
  const calls: RealtimeToolCallDraft[] = []
  for (const [index, item] of value.entries()) {
    if (!isRecord(item)) {
      continue
    }
    const id = getString(item.id)
    const name = getString(item.name)
    const argumentsJson = getString(item.argumentsJson)
    if (!id && !name && !argumentsJson) {
      continue
    }
    calls.push({ index, id, name, argumentsJson })
  }
  return calls
}

function parseModelErrorPayload(json: string): { code: string; message: string } | null {
  try {
    const value: unknown = JSON.parse(json)
    if (!isRecord(value)) {
      return null
    }
    const message = getString(value.message)
    if (!message.trim()) {
      return null
    }
    return { code: getString(value.kind), message }
  } catch {
    return null
  }
}
