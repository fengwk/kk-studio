import type {
  ModelAttemptFailureDTO,
  ModelInvocationDTO,
  ToolInvocationDTO,
} from '@/shared/api/contracts/ai-runtime'
import { taskStatusFingerprint } from '@/features/ai/runtime/task-status'
import type { ToolAttachment } from '@/features/ai/runtime/thread-timeline-types'
import { toResourceAttachment } from '@/features/ai/runtime/thread-timeline/content-utils'

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

export interface RealtimeToolPartial {
  threadId: string
  invocationId: string
  attempt: number
  /** 规范的 partial ToolResult payload：{toolCallId, contents, error, details}。 */
  payload: Record<string, unknown>
  createdAt: string
}

export const PROCESS_OUTPUT_MAX_CHARS = 512 * 1024
export const PROCESS_OUTPUT_MAX_LINES = 2000
export const PROCESS_OUTPUT_OMISSION_MARKER = '... [output omitted] ...'

export interface ProcessOutputDetails {
  kind: 'process.output'
  mode: 'APPEND' | 'SNAPSHOT'
  startOffset: number
  endOffset: number
  observedBytes: number
}

export interface ProcessOutputStreamState {
  mode: 'APPEND' | 'SNAPSHOT'
  startOffset: number
  endOffset: number
  observedBytes: number
  hasOmittedPrefix?: boolean
  gapPending?: boolean
}

/** 一次 invocation attempt 的瞬态 tool-result overlay。 */
export interface RealtimeToolStream {
  threadId: string
  invocationId: string
  attempt: number
  toolCallId: string
  text: string
  error: boolean
  /** 在尚无 result Entry 时，从持久 errorJson 投影出的错误消息。 */
  errorText?: string
  /**
   * 仅从持久终止态 resultJson 投影 Resource 内容。runtime 禁止
   * TOOL_PARTIAL 分片携带 Resource 内容，因此 partial 聚合
   * 永远不会贡献 attachments。
   */
  attachments?: ToolAttachment[]
  /** Environment process.output 结构化流状态（mode、offsets、observedBytes、omission）。 */
  processOutput?: ProcessOutputStreamState
  createdAt: string
}

/** 用于 partial overlay 聚合的规范 ToolResult JSON 字段名。 */
const TOOL_RESULT_CONTENTS_KEY = 'contents'
const TOOL_RESULT_TOOL_CALL_ID_KEY = 'toolCallId'
const TOOL_RESULT_ERROR_KEY = 'error'

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

/** 将 TOOL_PARTIAL realtime 事件解析为它的规范 payload（严格 shape）。 */
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

/**
 * 从活动 ToolInvocation 中派生瞬态 tool-result overlay（invocation 已消失则返回
 * null）。ToolResult Entry 写入与 invocation 删除在同一事务原子提交：invocation
 * 仍存在说明持久结果尚未物化，此时终止态 resultJson/errorJson 会完整投影
 * （text/json contents + resource attachments；error message）；Entry 落地后
 * invocation 随即从 snapshot 消失。
 */
export function snapshotToolStream(
  invocation: ToolInvocationDTO | null,
  threadId: string,
): RealtimeToolStream | null {
  if (invocation == null) {
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

/** 将规范的 ToolResult JSON 解析为 text + error 标记 + resource attachments。 */
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

/** 将规范的错误 JSON 解析为用于展示的 message（不存在时返回 null）。 */
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

export function parseProcessOutputDetails(details: unknown): ProcessOutputDetails | null {
  let record: Record<string, unknown> | null = null
  if (isRecord(details)) {
    record = details
  } else if (typeof details === 'string' && details.trim()) {
    try {
      const parsed: unknown = JSON.parse(details)
      if (isRecord(parsed)) {
        record = parsed
      }
    } catch {
      return null
    }
  }
  if (record == null || record.kind !== 'process.output') {
    return null
  }
  const rawMode = typeof record.mode === 'string' ? record.mode.toUpperCase() : ''
  if (rawMode !== 'APPEND' && rawMode !== 'SNAPSHOT') {
    return null
  }
  const mode = rawMode as 'APPEND' | 'SNAPSHOT'
  const startOffset =
    typeof record.startOffset === 'number'
    && Number.isSafeInteger(record.startOffset)
    && record.startOffset >= 0
      ? record.startOffset
      : null
  const endOffset =
    typeof record.endOffset === 'number'
    && Number.isSafeInteger(record.endOffset)
    && record.endOffset >= 0
      ? record.endOffset
      : null
  const observedBytes =
    typeof record.observedBytes === 'number'
    && Number.isSafeInteger(record.observedBytes)
    && record.observedBytes >= 0
      ? record.observedBytes
      : null

  if (
    startOffset == null
    || endOffset == null
    || observedBytes == null
    || endOffset < startOffset
  ) {
    return null
  }

  return {
    kind: 'process.output',
    mode,
    startOffset,
    endOffset,
    observedBytes,
  }
}

export function isProcessOutputPartial(payload: Record<string, unknown>): boolean {
  return parseProcessOutputDetails(payload.details) != null
}

export function boundProcessOutputText(
  rawText: string,
  forceOmissionMarker = false,
): { text: string; hasOmittedPrefix: boolean } {
  let content = rawText
  let hasOmission = forceOmissionMarker
  if (content.startsWith(PROCESS_OUTPUT_OMISSION_MARKER + '\n')) {
    content = content.slice(PROCESS_OUTPUT_OMISSION_MARKER.length + 1)
    hasOmission = true
  } else if (content === PROCESS_OUTPUT_OMISSION_MARKER) {
    content = ''
    hasOmission = true
  }

  const lines = content.split('\n')
  if (lines.length > PROCESS_OUTPUT_MAX_LINES) {
    content = lines.slice(-PROCESS_OUTPUT_MAX_LINES).join('\n')
    hasOmission = true
  }

  if (content.length > PROCESS_OUTPUT_MAX_CHARS) {
    let sliced = content.slice(-PROCESS_OUTPUT_MAX_CHARS)
    const firstNewline = sliced.indexOf('\n')
    if (firstNewline !== -1 && firstNewline < 1024) {
      sliced = sliced.slice(firstNewline + 1)
    }
    content = sliced
    hasOmission = true
  }

  const resultText = hasOmission
    ? (content ? `${PROCESS_OUTPUT_OMISSION_MARKER}\n${content}` : PROCESS_OUTPUT_OMISSION_MARKER)
    : content

  return {
    text: resultText,
    hasOmittedPrefix: hasOmission,
  }
}

/**
 * 将一条 TOOL_PARTIAL chunk 聚合到 overlay 中：只有 text/json chunk 会追加到 text。
 * 支持 process.output 结构化流（APPEND / SNAPSHOT / gap healing / offset 去重与 UI 有界裁剪）。
 * runtime 禁止 TOOL_PARTIAL 携带 Resource 内容，因此 attachments 不会在这里聚合——
 * 它们仅由 {@link snapshotToolStream} 从持久终止态 resultJson 投影得到。
 * attempt 发生变化（即重试）时会替换掉之前所有的片段。
 */
export function reduceRealtimeToolStream(
  current: RealtimeToolStream | null,
  partial: RealtimeToolPartial,
): RealtimeToolStream {
  const chunkText = partialText(partial.payload)
  const processDetails = parseProcessOutputDetails(partial.payload.details)
  const replaceText = isTaskStatusPartial(partial.payload)
  const isNewAttempt =
    current == null
    || current.threadId !== partial.threadId
    || current.invocationId !== partial.invocationId
    || current.attempt !== partial.attempt

  if (isNewAttempt) {
    if (processDetails != null) {
      if (processDetails.mode === 'SNAPSHOT') {
        const bounded = boundProcessOutputText(chunkText, processDetails.startOffset > 0)
        return {
          threadId: partial.threadId,
          invocationId: partial.invocationId,
          attempt: partial.attempt,
          toolCallId: getString(partial.payload[TOOL_RESULT_TOOL_CALL_ID_KEY]) || '',
          text: bounded.text,
          error: partial.payload[TOOL_RESULT_ERROR_KEY] === true,
          processOutput: {
            mode: 'SNAPSHOT',
            startOffset: processDetails.startOffset,
            endOffset: processDetails.endOffset,
            observedBytes: processDetails.observedBytes,
            hasOmittedPrefix: bounded.hasOmittedPrefix,
            gapPending: false,
          },
          createdAt: partial.createdAt,
        }
      }
      if (processDetails.startOffset === 0) {
        const bounded = boundProcessOutputText(chunkText, false)
        return {
          threadId: partial.threadId,
          invocationId: partial.invocationId,
          attempt: partial.attempt,
          toolCallId: getString(partial.payload[TOOL_RESULT_TOOL_CALL_ID_KEY]) || '',
          text: bounded.text,
          error: partial.payload[TOOL_RESULT_ERROR_KEY] === true,
          processOutput: {
            mode: 'APPEND',
            startOffset: 0,
            endOffset: processDetails.endOffset,
            observedBytes: processDetails.observedBytes,
            hasOmittedPrefix: bounded.hasOmittedPrefix,
            gapPending: false,
          },
          createdAt: partial.createdAt,
        }
      }
      return {
        threadId: partial.threadId,
        invocationId: partial.invocationId,
        attempt: partial.attempt,
        toolCallId: getString(partial.payload[TOOL_RESULT_TOOL_CALL_ID_KEY]) || '',
        text: '',
        error: partial.payload[TOOL_RESULT_ERROR_KEY] === true,
        processOutput: {
          mode: 'APPEND',
          startOffset: 0,
          endOffset: 0,
          observedBytes: processDetails.observedBytes,
          hasOmittedPrefix: false,
          gapPending: true,
        },
        createdAt: partial.createdAt,
      }
    }

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

  const nextError = current.error || partial.payload[TOOL_RESULT_ERROR_KEY] === true

  if (processDetails != null) {
    if (processDetails.mode === 'SNAPSHOT') {
      const snapshot = classifySnapshot(current, partial.createdAt, processDetails)
      if (snapshot === 'STALE') {
        // 陈旧快照（乱序/重放或重复的截断快照）绝不覆盖更新的文本与偏移，只允许单调加宽 error。
        if (nextError !== current.error) {
          return {
            ...current,
            error: nextError,
          }
        }
        return current
      }
      const bounded = boundProcessOutputText(chunkText, processDetails.startOffset > 0)
      return {
        ...current,
        text: bounded.text,
        error: nextError,
        processOutput: {
          mode: 'SNAPSHOT',
          startOffset: processDetails.startOffset,
          endOffset: processDetails.endOffset,
          // 重置快照建立全新的流基线：绝不让旧流的 observedBytes 通过 Math.max 泄漏进新流。
          observedBytes:
            snapshot === 'RESET'
              ? processDetails.observedBytes
              : Math.max(
                  current.processOutput?.observedBytes ?? 0,
                  processDetails.observedBytes,
                ),
          hasOmittedPrefix: bounded.hasOmittedPrefix,
          gapPending: false,
        },
        createdAt: monotonicCreatedAt(current.createdAt, partial.createdAt),
      }
    }

    const currentEndOffset = current.processOutput?.endOffset ?? 0
    const gapPending = current.processOutput?.gapPending === true

    if (
      processDetails.endOffset <= currentEndOffset
      || processDetails.startOffset < currentEndOffset
    ) {
      if (nextError !== current.error) {
        return {
          ...current,
          error: nextError,
        }
      }
      return current
    }

    if (gapPending || processDetails.startOffset > currentEndOffset) {
      const nextObservedBytes = Math.max(
        current.processOutput?.observedBytes ?? 0,
        processDetails.observedBytes,
      )
      if (
        current.processOutput?.gapPending
        && nextError === current.error
        && nextObservedBytes === current.processOutput.observedBytes
      ) {
        return current
      }
      return {
        ...current,
        error: nextError,
        processOutput: {
          mode: 'APPEND',
          startOffset: current.processOutput?.startOffset ?? 0,
          endOffset: currentEndOffset,
          observedBytes: nextObservedBytes,
          hasOmittedPrefix: current.processOutput?.hasOmittedPrefix ?? false,
          gapPending: true,
        },
        createdAt: monotonicCreatedAt(current.createdAt, partial.createdAt),
      }
    }

    const unconstrained = current.text + chunkText
    const bounded = boundProcessOutputText(
      unconstrained,
      current.processOutput?.hasOmittedPrefix ?? false,
    )
    return {
      ...current,
      text: bounded.text,
      error: nextError,
      processOutput: {
        mode: 'APPEND',
        startOffset: current.processOutput?.startOffset ?? 0,
        endOffset: processDetails.endOffset,
        observedBytes: Math.max(
          current.processOutput?.observedBytes ?? 0,
          processDetails.observedBytes,
        ),
        hasOmittedPrefix: bounded.hasOmittedPrefix,
        gapPending: false,
      },
      createdAt: monotonicCreatedAt(current.createdAt, partial.createdAt),
    }
  }

  const currentTaskStatus = replaceText ? taskStatusFingerprint(current.text) : null
  if (
    currentTaskStatus != null
    && nextError === current.error
    && currentTaskStatus === taskStatusFingerprint(chunkText)
  ) {
    return current
  }
  return {
    ...current,
    text: replaceText ? chunkText : current.text + chunkText,
    error: nextError,
    createdAt: monotonicCreatedAt(current.createdAt, partial.createdAt),
  }
}

function isTaskStatusPartial(payload: Record<string, unknown>): boolean {
  const details = payload.details
  return isRecord(details) && details.kind === 'task.status'
}

/**
 * 分类一次 SNAPSHOT 相对当前 overlay 的关系。
 *
 * <p>基于单调递增的 {@code observedBytes} 与字节偏移（{@code startOffset}/{@code endOffset}）
 * 结合事件时间戳（{@code createdAt}）判定快照的新鲜度与重置语义：
 *
 * <ul>
 *   <li>{@code STALE}：陈旧或重复快照。包括：
 *     1) 时间戳严格早于当前状态（乱序/重放到达）；
 *     2) 时间戳不晚于当前状态时声明了更小的结束偏移（流内乱序到达的历史帧）；
 *     3) 与当前已应用的 SNAPSHOT 具有相同区间且未观测到更多字节（重复快照）。
 *     陈旧快照绝不覆盖或回退已应用的文本与偏移，只允许单调加宽 {@code error}。
 *   <li>{@code RESET}：合法的新流重置快照。
 *     时间戳严格晚于当前状态，但声明了更小的结束偏移（例如新进程/命令从 0 开始）。
 *     此时必须干净重置 text、offsets、observedBytes 与 gapPending，建立全新流基线。
 *   <li>{@code FORWARD}：合法向前推进或修复 gap 的快照（偏移单调前进、APPEND 后的截断快照、或修复 pending gap）。
 * </ul>
 */
function classifySnapshot(
  current: RealtimeToolStream,
  createdAt: string,
  details: ProcessOutputDetails,
): 'STALE' | 'RESET' | 'FORWARD' {
  const applied = current.processOutput
  if (applied == null) {
    return 'FORWARD'
  }
  const currentMillis = toEpochMillis(current.createdAt)
  const partialMillis = toEpochMillis(createdAt)

  // 1. 时间戳严格早于当前状态：必为乱序/重放的历史快照。
  if (partialMillis != null && currentMillis != null && partialMillis < currentMillis) {
    return 'STALE'
  }

  // 2. 结束偏移回退：
  if (details.endOffset < applied.endOffset) {
    // 仅当时间戳严格晚于当前状态时，才视为新流合法重置（如新命令从 0 开始）；
    // 否则（时间戳相同、更早或不可比较）属于流内乱序旧快照，必须作为 STALE 丢弃。
    if (partialMillis != null && currentMillis != null && partialMillis > currentMillis) {
      return 'RESET'
    }
    return 'STALE'
  }

  // 3. 结束偏移相同：
  if (details.endOffset === applied.endOffset) {
    // 当前已处于 SNAPSHOT 且起止区间与 observedBytes 均未推进，且无待修复的 gap：视为重复快照。
    if (
      applied.mode === 'SNAPSHOT'
      && !applied.gapPending
      && details.startOffset === applied.startOffset
      && details.observedBytes <= applied.observedBytes
    ) {
      return 'STALE'
    }
    // 其余相同 endOffset 的情况（如 APPEND 后的截断快照、gapPending 修复、或更窄的 tail 预览）均属合法向前更新。
    return 'FORWARD'
  }

  // 4. endOffset > applied.endOffset：正常推进或修复 gap。
  return 'FORWARD'
}

/** 同一 attempt 的 process.output 时间戳只允许单调前进，避免乱序帧污染后续快照分类基线。 */
function monotonicCreatedAt(current: string, candidate: string): string {
  const currentMillis = toEpochMillis(current)
  const candidateMillis = toEpochMillis(candidate)
  if (candidateMillis == null) {
    return current
  }
  if (currentMillis == null || candidateMillis > currentMillis) {
    return candidate
  }
  return current
}

/** 当 tool partial overlay 属于同一 invocation attempt 且没有陈旧时返回 true。 */
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

/** 严格遵循 Java `nullableText` 语义：只接受 string 或 null（undefined 视为格式错误）。 */
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
