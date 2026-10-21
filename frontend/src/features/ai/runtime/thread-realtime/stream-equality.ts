import type {
  RealtimeModelStream,
} from '@/features/ai/runtime/thread-realtime/model-stream'
import type {
  RealtimeToolStream,
} from '@/features/ai/runtime/thread-realtime/tool-stream'

export function sameToolStreamMap(
  left: ReadonlyMap<string, RealtimeToolStream>,
  right: ReadonlyMap<string, RealtimeToolStream>,
): boolean {
  if (left.size !== right.size) {
    return false
  }
  for (const [key, stream] of left) {
    const other = right.get(key)
    if (other == null || !sameToolStream(stream, other)) {
      return false
    }
  }
  return true
}

function sameToolStream(left: RealtimeToolStream, right: RealtimeToolStream): boolean {
  return left.threadId === right.threadId
    && left.invocationId === right.invocationId
    && left.attempt === right.attempt
    && left.toolCallId === right.toolCallId
    && left.text === right.text
    && left.error === right.error
    && left.errorText === right.errorText
    && sameProcessOutput(left.processOutput, right.processOutput)
}

function sameProcessOutput(
  left: RealtimeToolStream['processOutput'],
  right: RealtimeToolStream['processOutput'],
): boolean {
  if (left == null && right == null) {
    return true
  }
  if (left == null || right == null) {
    return false
  }
  return left.mode === right.mode
    && left.startOffset === right.startOffset
    && left.endOffset === right.endOffset
    && left.observedBytes === right.observedBytes
    && left.hasOmittedPrefix === right.hasOmittedPrefix
    && left.gapPending === right.gapPending
}

export function mergeSnapshotToolCalls(
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

function sameToolCallDrafts(
  left: RealtimeModelStream['toolCalls'],
  right: RealtimeModelStream['toolCalls'],
): boolean {
  if (left.length !== right.length) {
    return false
  }
  return left.every((item, index) => {
    const other = right[index]
    return other != null
      && item.index === other.index
      && item.id === other.id
      && item.name === other.name
      && item.argumentsJson === other.argumentsJson
  })
}

export function sameModelStreamNullable(
  left: RealtimeModelStream | null,
  right: RealtimeModelStream | null,
): boolean {
  if (left === right) {
    return true
  }
  if (left == null || right == null) {
    return false
  }
  return sameModelStream(left, right)
}

function sameModelStream(left: RealtimeModelStream, right: RealtimeModelStream): boolean {
  return left.threadId === right.threadId
    && left.invocationId === right.invocationId
    && left.attempt === right.attempt
    && left.text === right.text
    && left.thinking === right.thinking
    && sameToolCallDrafts(left.toolCalls, right.toolCalls)
    && left.sequence === right.sequence
    && left.status === right.status
    && left.errorCode === right.errorCode
    && left.errorText === right.errorText
}
