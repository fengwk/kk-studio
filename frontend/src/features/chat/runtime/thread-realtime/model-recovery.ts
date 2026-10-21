import {
  isFailedAttempt,
  type RealtimeModelDelta,
  type RealtimeModelStream,
} from '@/features/chat/runtime/thread-realtime/model-stream'
import type { ModelAttemptFailureDTO, ModelInvocationDTO } from '@/shared/api/contracts/ai-runtime'

/** 每个 thread:invocation:attempt 保留的已见 eventId 上限；仅 best-effort 防重投递。 */
export const TOOL_PARTIAL_SEEN_EVENT_ID_LIMIT = 256
/** gap / checkpoint 在途期间缓存的 delta suffix 硬上限；超限后清空并继续等 checkpoint。 */
const MODEL_GAP_BUFFER_MAX_DELTAS = 256
/** suffix 预算按 UTF-8 字节计，避免非 ASCII/emoji 按 UTF-16 code unit 低估真实体积。 */
const MODEL_GAP_BUFFER_MAX_BYTES = 512 * 1024
const UTF8_ENCODER = new TextEncoder()

export interface ModelRecovery {
  threadId: string
  invocationId: string
  attempt: number
  /** 触发恢复的首个跳号 delta；null 表示只等待订阅 catchup checkpoint。 */
  firstGapSequence: number
  /** 已按 sequence 索引的待拼接 suffix。 */
  buffered: Map<number, RealtimeModelDelta>
  bufferedBytes: number
  highestObservedSequence: number
  /** 缓冲超限后不再信任已丢弃的 suffix，只能等待覆盖它的下一 checkpoint。 */
  overflowed: boolean
  /** 本恢复 episode 已发过 catchup（或已被订阅 catchup 覆盖）。 */
  requested: boolean
  /** 已接受、等待 snapshot 覆盖的 checkpoint 水位（拼接目标）。 */
  pendingCheckpointSequence: number | null
  /** 真正在途的 snapshot 回读；与 pending 水位分离，settled 后即释放。 */
  refreshInFlight: boolean
}

export function createModelRecovery(
  threadId: string,
  invocationId: string,
  attempt: number,
  firstGapSequence: number,
): ModelRecovery {
  return {
    threadId,
    invocationId,
    attempt,
    firstGapSequence,
    buffered: new Map(),
    bufferedBytes: 0,
    highestObservedSequence: firstGapSequence - 1,
    overflowed: false,
    requested: false,
    pendingCheckpointSequence: null,
    refreshInFlight: false,
  }
}

export function bufferModelDelta(recovery: ModelRecovery, delta: RealtimeModelDelta): void {
  if (recovery.buffered.has(delta.sequence)) {
    return
  }
  const size = deltaByteSize(delta)
  if (
    recovery.buffered.size >= MODEL_GAP_BUFFER_MAX_DELTAS
    || recovery.bufferedBytes + size > MODEL_GAP_BUFFER_MAX_BYTES
  ) {
    // 有界：丢弃已缓存 suffix 并保持 gap；后续只接受能覆盖全部已观察
    // sequence 的 checkpoint，绝不无界积累。
    recovery.buffered.clear()
    recovery.bufferedBytes = 0
    recovery.overflowed = true
    recovery.highestObservedSequence = Math.max(
      recovery.highestObservedSequence,
      delta.sequence,
    )
    return
  }
  recovery.buffered.set(delta.sequence, delta)
  recovery.bufferedBytes += size
  recovery.highestObservedSequence = Math.max(
    recovery.highestObservedSequence,
    delta.sequence,
  )
}

export function dropBufferedThrough(recovery: ModelRecovery, sequence: number): void {
  for (const [bufferedSequence, delta] of [...recovery.buffered]) {
    if (bufferedSequence <= sequence) {
      recovery.buffered.delete(bufferedSequence)
      recovery.bufferedBytes = Math.max(0, recovery.bufferedBytes - deltaByteSize(delta))
    }
  }
}

function deltaByteSize(delta: RealtimeModelDelta): number {
  return UTF8_ENCODER.encode(delta.text).length
    + UTF8_ENCODER.encode(delta.toolCall?.id ?? '').length
    + UTF8_ENCODER.encode(delta.toolCall?.name ?? '').length
    + UTF8_ENCODER.encode(delta.toolCall?.argumentsJson ?? '').length
}

export function hasVisibleModelOverlay(stream: RealtimeModelStream): boolean {
  return stream.sequence > 0
    || Boolean(stream.text)
    || Boolean(stream.thinking)
    || stream.toolCalls.length > 0
}

export function isActiveModelAttempt(
  invocation: ModelInvocationDTO | null,
  threadId: string,
  invocationId: string,
  attempt: number,
  failures: readonly ModelAttemptFailureDTO[],
): invocation is ModelInvocationDTO {
  return invocation != null
    && invocation.threadId === threadId
    && invocation.id === invocationId
    && invocation.attempt === attempt
    && invocation.resultJson == null
    && invocation.errorJson == null
    && invocation.resultEntryId == null
    && !isFailedAttempt(attempt, invocation, failures)
}
