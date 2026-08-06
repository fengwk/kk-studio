import { describe, expect, it } from 'vitest'
import {
  isRealtimeModelDeltaGap,
  parseRealtimeModelDelta,
  parseStreamCheckpoint,
  reduceRealtimeModelStream,
  reduceRealtimeToolStream,
  snapshotModelStream,
  snapshotToolStream,
} from '@/features/ai/runtime/thread-realtime-state'
import type { ToolInvocationDTO } from '@/shared/api/contracts/ai-runtime'

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

  it('mirrors the Java checkpoint codec: nullable string-only text/thinking with one non-blank', () => {
    expect(
      parseStreamCheckpoint('{"attempt":1,"sequence":0,"text":null,"thinking":"x"}'),
    ).toEqual({ attempt: 1, sequence: 0, text: '', thinking: 'x' })
    expect(
      parseStreamCheckpoint('{"attempt":1,"sequence":0,"text":"y","thinking":null}'),
    ).toEqual({ attempt: 1, sequence: 0, text: 'y', thinking: '' })
    // Both blank (null or whitespace) is rejected exactly like the Java record constructor.
    expect(parseStreamCheckpoint('{"attempt":1,"sequence":0,"text":null,"thinking":null}')).toBeNull()
    expect(parseStreamCheckpoint('{"attempt":1,"sequence":0,"text":"  ","thinking":""}')).toBeNull()
    // Non-string types are malformed (nullableText rejects numbers/objects), not defaulted.
    expect(parseStreamCheckpoint('{"attempt":1,"sequence":0,"text":5,"thinking":"x"}')).toBeNull()
    expect(parseStreamCheckpoint('{"attempt":1,"sequence":0,"text":null,"thinking":{"a":1}}')).toBeNull()
    expect(parseStreamCheckpoint('{"attempt":1,"sequence":0,"text":[],"thinking":"x"}')).toBeNull()
    // attempt/sequence validation stays strict.
    expect(parseStreamCheckpoint('{"attempt":0,"sequence":0,"text":"x","thinking":null}')).toBeNull()
    expect(parseStreamCheckpoint('{"attempt":1,"sequence":-1,"text":"x","thinking":null}')).toBeNull()
  })

  it('uses the invocation attempt for the empty base and tolerates stale checkpoint attempts', () => {
    const invocation = {
      id: 'inv-1',
      threadId: '7',
      turnStartEntryId: '1',
      basisHeadEntryId: '1',
      status: 'RUNNING',
      attempt: 3,
      streamCheckpointJson: '{"attempt":2,"sequence":5,"text":"stale","thinking":null}',
      resultJson: null,
      errorJson: null,
      resultEntryId: null,
      createTime: '2026-07-28T10:00:00Z',
      updateTime: '2026-07-28T10:00:00Z',
    }
    expect(snapshotModelStream('7', invocation)).toMatchObject({
      attempt: 3,
      sequence: 0,
      text: '',
      thinking: '',
    })
  })

  it('projects terminal tool resultJson/errorJson while the durable result Entry is pending', () => {
    const base: ToolInvocationDTO = {
      id: 'inv-t',
      modelInvocationId: 'inv-1',
      assistantEntryId: 'e-1',
      ordinal: 0,
      status: 'RUNNING',
      attempt: 1,
      toolCallId: 'call-1',
      toolName: 'web-search',
      toolVersion: null,
      toolType: 'PLATFORM',
      environmentId: null,
      argumentsJson: '{}',
      approvalJson: null,
      resultJson: null,
      errorJson: null,
      resultEntryId: null,
      createTime: '2026-07-28T10:00:00Z',
      updateTime: '2026-07-28T10:00:00Z',
    }
    // toolCallId comes straight from the invocation, never guessed from argumentsJson.
    const active = snapshotToolStream(base, '7')
    expect(active).toMatchObject({ toolCallId: 'call-1', text: '', error: false })

    const result = snapshotToolStream(
      {
        ...base,
        resultJson: JSON.stringify({
          toolCallId: 'call-1',
          contents: [
            { type: 'text', text: 'answer' },
            { type: 'resource', uri: 's3://bucket/a.png', mediaType: 'image/png', name: 'a.png' },
          ],
          error: null,
          details: null,
        }),
      },
      '7',
    )
    expect(result).toMatchObject({ text: 'answer', error: false })
    expect(result?.attachments).toEqual([
      expect.objectContaining({ data: 's3://bucket/a.png', mime: 'image/png', name: 'a.png' }),
    ])

    // ToolResult.error=true must project (a terminal error result is not "done").
    const failedResult = snapshotToolStream(
      {
        ...base,
        resultJson: JSON.stringify({
          toolCallId: 'call-1',
          contents: [{ type: 'text', text: 'partial output' }],
          error: true,
          details: null,
        }),
      },
      '7',
    )
    expect(failedResult).toMatchObject({ error: true, text: 'partial output' })

    const failed = snapshotToolStream(
      { ...base, errorJson: '{"message":"boom"}' },
      '7',
    )
    expect(failed).toMatchObject({ error: true, errorText: 'boom' })

    // resultEntryId set: the durable Tool result Entry is the transcript truth.
    expect(snapshotToolStream({ ...base, resultJson: '{}', resultEntryId: 'e-9' }, '7')).toBeNull()
  })

  it('ignores Resource contents in TOOL_PARTIAL chunks (the runtime forbids them)', () => {
    const partial = {
      threadId: '7',
      invocationId: 'inv-t',
      attempt: 1,
      payload: {
        toolCallId: 'call-1',
        contents: [
          { type: 'text', text: 'one' },
          { type: 'resource', uri: 'file:///tmp/r.bin', mediaType: 'application/octet-stream', name: 'r.bin' },
        ],
        error: null,
        details: null,
      },
      createdAt: '2026-07-28T10:00:00Z',
    }
    const stream = reduceRealtimeToolStream(null, partial)
    // Text/json chunks aggregate; Resource chunks are never projected from partials.
    expect(stream.text).toBe('one')
    expect(stream.attachments).toBeUndefined()
  })

  it('restores the stream from the single snapshot ModelInvocation checkpoint', () => {
    const invocation = {
      id: '9007199254740995',
      threadId: '7',
      turnStartEntryId: '1',
      basisHeadEntryId: '2',
      status: 'RUNNING',
      attempt: 1,
      streamCheckpointJson: '{"attempt":1,"text":"new","thinking":"","sequence":2}',
      resultJson: null,
      errorJson: null,
      resultEntryId: null,
      createTime: '2026-07-28T10:00:00Z',
      updateTime: '2026-07-28T10:00:00Z',
    }
    const stream = snapshotModelStream('7', invocation)

    expect(stream).toMatchObject({
      invocationId: '9007199254740995',
      text: 'new',
      sequence: 2,
    })
    // Invalid checkpoint JSON is treated as absent -> RUNNING keeps an empty base so the
    // first realtime delta is not dropped (checkpoint flush does not bump the revision).
    expect(snapshotModelStream('7', { ...invocation, streamCheckpointJson: 'not-json' })).toMatchObject({
      attempt: 1,
      sequence: 0,
      text: '',
      thinking: '',
    })
    // Terminal result WITHOUT resultEntryId: the complete durable result projection
    // replaces the overlay (status 'done') and stays until resultEntryId is set.
    expect(
      snapshotModelStream('7', {
        ...invocation,
        resultJson:
          '{"text":"new","thinking":"","toolCalls":[],"stopReason":"stop","usage":{},"cost":{}}',
      }),
    ).toMatchObject({
      attempt: 1,
      sequence: 2,
      text: 'new',
      status: 'done',
    })
    // resultEntryId set: the durable Entry is the transcript truth.
    expect(
      snapshotModelStream('7', {
        ...invocation,
        resultJson: '{"text":"new","thinking":""}',
        resultEntryId: '9',
      }),
    ).toBeNull()
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
