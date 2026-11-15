import { isCanonicalUuid } from '@/shared/lib/uuid'
import {
  isPositiveInteger,
  stringifyJson,
} from '@/features/thread/runtime/thread-realtime/realtime-values'
import { getString, isRecord, nonBlank as isNonBlankString } from '@/shared/lib/value'
import { parseCodecInstantMillis } from '@/shared/lib/date-format'
import type { ToolInvocationDTO } from '@/shared/api/generated/contracts'
import {
  boundProcessOutputText,
  classifySnapshot,
  monotonicCreatedAt,
  parseProcessOutputDetails,
  type ProcessOutputDetails,
} from '@/features/thread/runtime/thread-realtime/process-output-stream'

export {
  PROCESS_OUTPUT_MAX_CHARS,
  PROCESS_OUTPUT_MAX_LINES,
  PROCESS_OUTPUT_OMISSION_MARKER,
  boundProcessOutputText,
  isProcessOutputPartial,
  parseProcessOutputDetails,
} from '@/features/thread/runtime/thread-realtime/process-output-stream'

interface RealtimeToolPartial {
  threadId: string
  invocationId: string
  attempt: number
  /**
   * 生产端为这一条投影事件生成的 canonical UUID。重投递复用同一值，是普通 partial 的
   * 唯一去重键；不同 eventId 的同 ms、同 payload 事件必须各自追加。
   */
  eventId: string
  /** 规范的 partial ToolResult payload：{toolCallId, contents, error, details}。 */
  payload: Record<string, unknown>
  createdAt: number
}

interface ProcessOutputStreamState {
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
  /** 在尚无 result Entry 时，从持久 error 投影出的错误消息。 */
  errorText?: string
  /** Environment process.output 结构化流状态（mode、offsets、observedBytes、omission）。 */
  processOutput?: ProcessOutputStreamState
  createdAt: number
}

/** 用于 partial overlay 聚合的规范 ToolResult JSON 字段名。 */
const TOOL_RESULT_CONTENTS_KEY = 'contents'

const TOOL_RESULT_TOOL_CALL_ID_KEY = 'toolCallId'

const TOOL_RESULT_ERROR_KEY = 'error'

/** 将 TOOL_PARTIAL realtime 事件解析为它的规范 payload（严格 shape，含 canonical eventId identity）。 */
export function parseRealtimeToolPartial(data: unknown): RealtimeToolPartial | null {
  if (!isRecord(data)) {
    return null
  }
  const envelope = data
  const createdAt = parseCodecInstantMillis(envelope.createdAt)
  if (
    envelope.type !== 'TOOL_PARTIAL'
    || envelope.subjectKind !== 'TOOL_INVOCATION'
    || !isNonBlankString(envelope.threadId)
    || !isNonBlankString(envelope.subjectId)
    || !isPositiveInteger(envelope.attempt)
    || typeof envelope.eventId !== 'string'
    || !isCanonicalUuid(envelope.eventId)
    || envelope.eventId !== envelope.eventId.toLowerCase()
    || createdAt == null
    || !isRecord(envelope.payload)
  ) {
    return null
  }
  return {
    threadId: envelope.threadId,
    invocationId: envelope.subjectId,
    attempt: envelope.attempt,
    eventId: envelope.eventId,
    payload: envelope.payload,
    createdAt,
  }
}

/**
 * 从活动 ToolInvocation 中派生瞬态 tool-result overlay（invocation 已消失则返回
 * null）。ToolResult Entry 写入与 invocation 删除在同一事务原子提交：invocation
 * 仍存在说明持久结果尚未物化，此时先给出已持久化的 text 与 error 摘要；Entry 落地后
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
    // HTTP 快照没有流事件时间，采用该 invocation 的创建时间；实时 partial 携带各自的 epoch 毫秒。
    createdAt: invocation.createdAt,
  }
  const result = parseToolResultPayload(invocation.result)
  if (result != null) {
    return {
      ...base,
      text: result.text,
      error: result.error,
    }
  }
  const errorText = parseToolErrorText(invocation.error)
  if (errorText != null) {
    return { ...base, error: true, errorText }
  }
  return base
}

/** 将规范的 ToolResult JSON 节点解析为 text + error 标记。 */
function parseToolResultPayload(value: unknown): {
  text: string
  error: boolean
} | null {
  if (value == null) {
    return null
  }
  if (!isRecord(value)) {
    return null
  }
  return { text: partialText(value), error: value[TOOL_RESULT_ERROR_KEY] === true }
}

/** 将规范的错误 JSON 节点解析为用于展示的 message（不存在时返回 null）。 */
export function parseToolErrorText(value: unknown): string | null {
  if (value == null) {
    return null
  }
  if (isRecord(value)) {
    const message = value.message
    if (typeof message === 'string' && message.trim()) {
      return message
    }
  }
  return null
}

/**
 * 将一条 TOOL_PARTIAL chunk 聚合到 overlay 中：只有 text/json chunk 会追加到 text。
 * 支持 process.output 结构化流（APPEND / SNAPSHOT / gap healing / offset 去重与 UI 有界裁剪）。
 * runtime 禁止 TOOL_PARTIAL 携带 Resource 内容，因此 attachments 不会在这里聚合——
 * 它们仅由 {@link snapshotToolStream} 从持久终止态 result 投影得到。
 * attempt 发生变化（即重试）时会替换掉之前所有的片段。
 */
export function reduceRealtimeToolStream(
  current: RealtimeToolStream | null,
  partial: RealtimeToolPartial,
): RealtimeToolStream {
  const chunkText = partialText(partial.payload)
  const processDetails = parseProcessOutputDetails(partial.payload.details)
  const isNewAttempt =
    current == null
    || current.threadId !== partial.threadId
    || current.invocationId !== partial.invocationId
    || current.attempt !== partial.attempt

  if (isNewAttempt) {
    return startAttemptStream(partial, chunkText, processDetails)
  }
  return advanceAttemptStream(current, partial, chunkText, processDetails)
}

/** 新 attempt：换流即替换状态，首个 chunk 自带 process.output 时同时建立流基线。 */
function startAttemptStream(
  partial: RealtimeToolPartial,
  chunkText: string,
  processDetails: ProcessOutputDetails | null,
): RealtimeToolStream {
  const base = {
    threadId: partial.threadId,
    invocationId: partial.invocationId,
    attempt: partial.attempt,
    toolCallId: getString(partial.payload[TOOL_RESULT_TOOL_CALL_ID_KEY]) || '',
    error: partial.payload[TOOL_RESULT_ERROR_KEY] === true,
    createdAt: partial.createdAt,
  }
  if (processDetails == null) {
    return { ...base, text: chunkText }
  }
  if (processDetails.mode === 'SNAPSHOT') {
    const bounded = boundProcessOutputText(chunkText, processDetails.startOffset > 0)
    return {
      ...base,
      text: bounded.text,
      processOutput: {
        mode: 'SNAPSHOT',
        startOffset: processDetails.startOffset,
        endOffset: processDetails.endOffset,
        observedBytes: processDetails.observedBytes,
        hasOmittedPrefix: bounded.hasOmittedPrefix,
        gapPending: false,
      },
    }
  }
  if (processDetails.startOffset === 0) {
    const bounded = boundProcessOutputText(chunkText, false)
    return {
      ...base,
      text: bounded.text,
      processOutput: {
        mode: 'APPEND',
        startOffset: 0,
        endOffset: processDetails.endOffset,
        observedBytes: processDetails.observedBytes,
        hasOmittedPrefix: bounded.hasOmittedPrefix,
        gapPending: false,
      },
    }
  }
  // 首帧即从非 0 偏移开始：无前缀可重建，标记 pending gap 等待快照修复。
  return {
    ...base,
    text: '',
    processOutput: {
      mode: 'APPEND',
      startOffset: 0,
      endOffset: 0,
      observedBytes: processDetails.observedBytes,
      hasOmittedPrefix: false,
      gapPending: true,
    },
  }
}

/** 同一 attempt 的后续 chunk：错误位单调加宽，process.output 交给各自的推进规则。 */
function advanceAttemptStream(
  current: RealtimeToolStream,
  partial: RealtimeToolPartial,
  chunkText: string,
  processDetails: ProcessOutputDetails | null,
): RealtimeToolStream {
  const nextError = current.error || partial.payload[TOOL_RESULT_ERROR_KEY] === true
  if (processDetails != null) {
    return processDetails.mode === 'SNAPSHOT'
      ? applySnapshotChunk(current, partial, chunkText, processDetails, nextError)
      : applyAppendChunk(current, partial, chunkText, processDetails, nextError)
  }
  if (!chunkText && nextError === current.error) {
    return current
  }
  return {
    ...current,
    text: current.text + chunkText,
    error: nextError,
    createdAt: monotonicCreatedAt(current.createdAt, partial.createdAt),
  }
}

/** 快照 chunk：陈旧快照不覆盖事实，RESET 干净重建基线，FORWARD 单调加宽 observedBytes。 */
function applySnapshotChunk(
  current: RealtimeToolStream,
  partial: RealtimeToolPartial,
  chunkText: string,
  processDetails: ProcessOutputDetails,
  nextError: boolean,
): RealtimeToolStream {
  const snapshot = classifySnapshot(current, partial.createdAt, processDetails)
  if (snapshot === 'STALE') {
    // 陈旧快照（乱序/重放或重复的截断快照）绝不覆盖更新的文本与偏移，只允许单调加宽 error。
    return nextError === current.error ? current : { ...current, error: nextError }
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

/** APPEND chunk：重叠/回退帧不追加，gap 期间只加宽 observedBytes 等待修复。 */
function applyAppendChunk(
  current: RealtimeToolStream,
  partial: RealtimeToolPartial,
  chunkText: string,
  processDetails: ProcessOutputDetails,
  nextError: boolean,
): RealtimeToolStream {
  const currentEndOffset = current.processOutput?.endOffset ?? 0
  const gapPending = current.processOutput?.gapPending === true

  if (
    processDetails.endOffset <= currentEndOffset
    || processDetails.startOffset < currentEndOffset
  ) {
    return nextError === current.error ? current : { ...current, error: nextError }
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
