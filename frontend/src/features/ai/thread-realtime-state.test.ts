import { describe, expect, it } from 'vitest'
import type { HarnessSessionEntryDTO } from '@/shared/api/contracts'
import {
  isRealtimeModelStreamCommitted,
  parseRealtimeModelDelta,
  reduceRealtimeModelStream,
} from '@/features/ai/thread-realtime-state'

describe('thread realtime state', () => {
  it('parses model text and thinking deltas but rejects unrelated envelopes', () => {
    expect(
      parseRealtimeModelDelta(
        '{"threadId":"7","subjectKind":"MODEL_INVOCATION","subjectId":"9","attempt":1,'
          + '"type":"MODEL_DELTA","payload":{"kind":"TEXT_DELTA","text":"hello"},'
          + '"createdAt":"2026-07-28T10:00:00Z"}',
      ),
    ).toMatchObject({ threadId: '7', invocationId: '9', kind: 'TEXT_DELTA', text: 'hello' })
    expect(
      parseRealtimeModelDelta(
        '{"threadId":"7","subjectKind":"TOOL_INVOCATION","subjectId":"9","attempt":1,'
          + '"type":"TOOL_PARTIAL","payload":{},"createdAt":"2026-07-28T10:00:00Z"}',
      ),
    ).toBeNull()
    expect(parseRealtimeModelDelta('not-json')).toBeNull()
    expect(
      parseRealtimeModelDelta(
        '{"threadId":"7","subjectKind":"MODEL_INVOCATION","subjectId":"9","attempt":1,'
          + '"type":"MODEL_DELTA","payload":{"kind":"TEXT_DELTA","text":"hello"},'
          + '"createdAt":"not-a-date"}',
      ),
    ).toBeNull()
    expect(parseRealtimeModelDelta({ type: 'MODEL_DELTA' })).toBeNull()
    expect(
      parseRealtimeModelDelta(
        '{"threadId":"7","subjectKind":"MODEL_INVOCATION","subjectId":"9","attempt":1,'
          + '"type":"MODEL_DELTA","payload":{"kind":"IMAGE_DELTA","text":"hello"},'
          + '"createdAt":"2026-07-28T10:00:00Z"}',
      ),
    ).toBeNull()
    expect(
      parseRealtimeModelDelta(
        '{"threadId":"7","subjectKind":"MODEL_INVOCATION","subjectId":"9","attempt":1,'
          + '"type":"MODEL_DELTA","payload":{"kind":"TEXT_DELTA","text":42},'
          + '"createdAt":"2026-07-28T10:00:00Z"}',
      ),
    ).toBeNull()
  })

  it('accumulates one attempt and resets the transient response for a retry', () => {
    const first = parseRealtimeModelDelta(event('1', 'TEXT_DELTA', 'old text'))!
    const thinking = parseRealtimeModelDelta(event('1', 'THINKING_DELTA', 'old thought'))!
    const retry = parseRealtimeModelDelta(event('2', 'TEXT_DELTA', 'new text'))!

    const initial = reduceRealtimeModelStream(null, first)
    const withThinking = reduceRealtimeModelStream(initial, thinking)
    const retried = reduceRealtimeModelStream(withThinking, retry)

    expect(withThinking).toMatchObject({ text: 'old text', thinking: 'old thought', attempt: 1 })
    expect(retried).toMatchObject({ text: 'new text', thinking: '', attempt: 2 })
    expect(reduceRealtimeModelStream(retried, first)).toBe(retried)

    const anotherThread = parseRealtimeModelDelta(
      event('1', 'TEXT_DELTA', 'other thread').replace('"threadId":"7"', '"threadId":"8"'),
    )!
    expect(reduceRealtimeModelStream(retried, anotherThread)).toMatchObject({
      threadId: '8',
      text: 'other thread',
      thinking: '',
      attempt: 1,
    })
  })

  it('removes a transient response only after its durable assistant outcome arrives', () => {
    const stream = reduceRealtimeModelStream(null, parseRealtimeModelDelta(event('1', 'TEXT_DELTA', 'hi'))!)
    const earlierAssistant = entry('2026-07-28T09:59:59', 'ASSISTANT')
    const durableAssistant = entry('2026-07-28T10:00:01', 'ASSISTANT')

    expect(isRealtimeModelStreamCommitted(stream, [earlierAssistant])).toBe(false)
    expect(isRealtimeModelStreamCommitted(stream, [durableAssistant])).toBe(true)
  })

  it('commits the realtime stream as soon as an ASSISTANT_ABORTED barrier lands', () => {
    // /stop emits ASSISTANT_ABORTED when the partial has safe content; from the realtime overlay
    // perspective this is a durable terminal outcome and the transient deltas must be cleared
    // so the aborted affordance becomes the visible state.
    const stream = reduceRealtimeModelStream(
      null,
      parseRealtimeModelDelta(event('1', 'TEXT_DELTA', 'partial'))!,
    )
    const aborted = abortedEntry('2026-07-28T10:00:01')
    const earlierAssistant = entry('2026-07-28T09:59:59', 'ASSISTANT')
    expect(isRealtimeModelStreamCommitted(stream, [earlierAssistant])).toBe(false)
    expect(isRealtimeModelStreamCommitted(stream, [aborted])).toBe(true)
  })

  it('keeps the transient response for malformed timestamps and malformed durable messages', () => {
    const stream = reduceRealtimeModelStream(
      null,
      parseRealtimeModelDelta(event('1', 'TEXT_DELTA', 'partial'))!,
    )
    expect(isRealtimeModelStreamCommitted({ ...stream, createdAt: 'not-a-date' }, [])).toBe(false)
    expect(
      isRealtimeModelStreamCommitted(stream, [
        { ...entry('2026-07-28T10:00:01', 'ASSISTANT'), payloadJson: '{' },
      ]),
    ).toBe(false)
  })
})

function event(attempt: string, kind: 'TEXT_DELTA' | 'THINKING_DELTA', text: string): string {
  return JSON.stringify({
    threadId: '7',
    subjectKind: 'MODEL_INVOCATION',
    subjectId: '9',
    attempt: Number(attempt),
    type: 'MODEL_DELTA',
    payload: { kind, text },
    createdAt: '2026-07-28T10:00:00Z',
  })
}

function entry(createTime: string, role: string): HarnessSessionEntryDTO {
  return {
    entryId: 'e1',
    parentEntryId: null,
    entryType: 'MESSAGE',
    payloadJson: JSON.stringify({ message: { role, contents: [] }, assistantMetadata: null }),
    createTime,
  }
}

function abortedEntry(createTime: string): HarnessSessionEntryDTO {
  return {
    entryId: 'e-aborted',
    parentEntryId: null,
    entryType: 'ASSISTANT_ABORTED',
    payloadJson: JSON.stringify({
      message: {
        role: 'ASSISTANT',
        contents: [{ type: 'text', text: 'partial' }],
      },
    }),
    createTime,
  }
}
