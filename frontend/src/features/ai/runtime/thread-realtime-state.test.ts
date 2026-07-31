import { describe, expect, it } from 'vitest'
import {
  isRealtimeModelDeltaGap,
  parseRealtimeModelDelta,
  reduceRealtimeModelStream,
  snapshotModelStream,
} from '@/features/ai/runtime/thread-realtime-state'

describe('thread realtime state', () => {
  it('parses only sequenced model deltas', () => {
    expect(parseRealtimeModelDelta(event(1, 'TEXT_DELTA', 'hello'))).toMatchObject({
      threadId: '7',
      invocationId: '9',
      attempt: 1,
      sequence: 1,
      text: 'hello',
    })
    expect(parseRealtimeModelDelta(event(0, 'TEXT_DELTA', 'hello'))).toBeNull()
    expect(parseRealtimeModelDelta('not-json')).toBeNull()
  })

  it('applies only the next sequence and detects a missing delta', () => {
    const first = parseRealtimeModelDelta(event(1, 'TEXT_DELTA', 'one'))!
    const second = parseRealtimeModelDelta(event(2, 'THINKING_DELTA', 'plan'))!
    const gap = parseRealtimeModelDelta(event(4, 'TEXT_DELTA', 'four'))!
    const stream = reduceRealtimeModelStream(null, first)
    const advanced = reduceRealtimeModelStream(stream, second)

    expect(advanced).toMatchObject({ text: 'one', thinking: 'plan', sequence: 2 })
    expect(isRealtimeModelDeltaGap(advanced, gap)).toBe(true)
    expect(reduceRealtimeModelStream(advanced, gap)).toBe(advanced)
    expect(reduceRealtimeModelStream(advanced, first)).toBe(advanced)
  })

  it('advances sequence across tool-call deltas without creating transcript text', () => {
    const text = parseRealtimeModelDelta(event(1, 'TEXT_DELTA', 'one'))!
    const toolCall = parseRealtimeModelDelta(event(2, 'TOOL_CALL_DELTA', ''))!
    const thinking = parseRealtimeModelDelta(event(3, 'THINKING_DELTA', 'plan'))!

    const afterText = reduceRealtimeModelStream(null, text)
    const afterToolCall = reduceRealtimeModelStream(afterText, toolCall)
    const complete = reduceRealtimeModelStream(afterToolCall, thinking)

    expect(afterToolCall).toMatchObject({ sequence: 2, text: 'one', thinking: '' })
    expect(complete).toMatchObject({ sequence: 3, text: 'one', thinking: 'plan' })
  })

  it('selects the newest decimal invocation id without Number precision loss', () => {
    const base = {
      threadId: '7',
      sourceHeadEntryId: '1',
      executionEpoch: '1',
      requestJson: '{}',
      status: 'RUNNING',
      attempt: 1,
      nextAttemptAt: null,
      workerUntil: null,
      deadlineAt: null,
      lastActivityAt: null,
      resultJson: null,
      errorJson: null,
      appliedAt: null,
      createdAt: '2026-07-28T10:00:00Z',
      startedAt: '2026-07-28T10:00:00Z',
      finishedAt: null,
    }
    const stream = snapshotModelStream('7', [
      {
        ...base,
        id: '9007199254740993',
        safeStreamSnapshotJson: '{"text":"old","thinking":"","sequence":1}',
      },
      {
        ...base,
        id: '9007199254740995',
        safeStreamSnapshotJson: '{"text":"new","thinking":"","sequence":2}',
      },
    ])

    expect(stream).toMatchObject({
      invocationId: '9007199254740995',
      text: 'new',
      sequence: 2,
    })
    expect(snapshotModelStream('7', [
      {
        ...base,
        id: '9007199254740997',
        safeStreamSnapshotJson: '{"text":"invalid","thinking":""}',
      },
    ])).toBeNull()
  })
})

function event(
  sequence: number,
  kind: 'TEXT_DELTA' | 'THINKING_DELTA' | 'TOOL_CALL_DELTA',
  text: string,
): string {
  return JSON.stringify({
    threadId: '7',
    subjectKind: 'MODEL_INVOCATION',
    subjectId: '9',
    attempt: 1,
    sequence,
    type: 'MODEL_DELTA',
    payload: kind === 'TOOL_CALL_DELTA' ? { kind, index: 0 } : { kind, text },
    createdAt: '2026-07-28T10:00:00Z',
  })
}
