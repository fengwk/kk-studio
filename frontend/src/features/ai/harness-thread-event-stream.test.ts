import { describe, expect, it, vi } from 'vitest'
import {
  lastEventIdCursor,
  loadThreadEventHistory,
  mergeThreadEvent,
  mergeThreadEventLists,
  normalizeThreadEvent,
  parseThreadEvent,
} from '@/features/ai/harness-thread-event-stream'
import { buildThreadTimeline } from '@/features/ai/thread-events'
import type { ThreadEventDTO } from '@/shared/api/contracts'

const SNOWFLAKE_A = '9007199254740993' // Number.MAX_SAFE_INTEGER + 2
const SNOWFLAKE_B = '9007199254740994'
const SNOWFLAKE_C = '9007199254741000'

describe('harness-thread-event-stream', () => {
  it('normalizes, dedupes, and preserves supplied backend order', () => {
    const first = normalizeThreadEvent({
      eventId: '2',
      threadId: '1',
      subjectEntryId: '9',
      eventType: 'assistant_delta_batch',
      payloadJson: '{}',
      createTime: null,
    })
    const second = normalizeThreadEvent({
      eventId: '10',
      threadId: '1',
      subjectEntryId: '9',
      eventType: 'assistant_completed',
      payloadJson: '{}',
      createTime: null,
    })
    expect(first).not.toBeNull()
    expect(second).not.toBeNull()
    const merged = mergeThreadEventLists([second!], [first!])
    expect(merged.map((event) => event.eventId)).toEqual(['10', '2'])
    expect(mergeThreadEvent(merged, first!)).toEqual(merged)
  })

  it('parses SSE payloads and rejects malformed events', () => {
    expect(parseThreadEvent(JSON.stringify({
      eventId: '3',
      threadId: '1',
      eventType: 'thread_idle',
      payloadJson: '{}',
    }))).toMatchObject({ eventId: '3', eventType: 'thread_idle' })
    expect(parseThreadEvent('{')).toBeNull()
    expect(normalizeThreadEvent({ eventId: '0', threadId: '1', eventType: 'x' })).toBeNull()
  })

  it('projects the backend delta batch wire contract from SSE through the timeline', () => {
    const started = parseThreadEvent(JSON.stringify({
      eventId: '1',
      threadId: '7',
      subjectEntryId: '9',
      eventType: 'assistant_started',
      payloadJson: '{"assistantEntryId":"9","schemaVersion":1}',
    }))
    const delta = parseThreadEvent(JSON.stringify({
      eventId: '2',
      threadId: '7',
      subjectEntryId: '9',
      eventType: 'assistant_delta_batch',
      payloadJson: '{"deltas":[{"kind":"thinking","text":"plan "},{"kind":"text","text":"answer"}],"schemaVersion":1}',
    }))

    // This covers both JSON layers used by a real SSE frame before timeline projection.
    const timeline = buildThreadTimeline([], [], [started!, delta!])
    expect(timeline.messages).toMatchObject([
      { role: 'assistant', thinking: 'plan ', text: 'answer', status: 'streaming' },
    ])
  })

  it('requires threadId and non-null subjectEntryId to be positive decimal strings', () => {
    expect(normalizeThreadEvent({
      eventId: '1',
      threadId: '1',
      subjectEntryId: null,
      eventType: 'thread_idle',
      payloadJson: '{}',
    })).toMatchObject({ threadId: '1', subjectEntryId: null })

    expect(normalizeThreadEvent({
      eventId: '1',
      threadId: '1',
      subjectEntryId: '42',
      eventType: 'assistant_started',
      payloadJson: '{}',
    })).toMatchObject({ subjectEntryId: '42' })

    // Reject already-rounded numeric snowflakes.
    expect(normalizeThreadEvent({
      eventId: 1 as never,
      threadId: '1',
      eventType: 'x',
    })).toBeNull()
    expect(normalizeThreadEvent({
      eventId: '1',
      threadId: 1 as never,
      eventType: 'x',
    })).toBeNull()
    expect(normalizeThreadEvent({
      eventId: '1',
      threadId: 't',
      eventType: 'x',
    })).toBeNull()
    expect(normalizeThreadEvent({
      eventId: '1',
      threadId: '1',
      subjectEntryId: 'assistant',
      eventType: 'assistant_started',
    })).toBeNull()
    expect(normalizeThreadEvent({
      eventId: '1',
      threadId: '1',
      subjectEntryId: 9 as never,
      eventType: 'assistant_started',
    })).toBeNull()
    expect(normalizeThreadEvent({
      eventId: '1',
      threadId: '1',
      subjectEntryId: '0',
      eventType: 'assistant_started',
    })).toBeNull()
  })

  it('loads history pages with decimal-string afterEventId cursors', async () => {
    const pages: ThreadEventDTO[][] = [
      [event('1', 'assistant_started'), event('2', 'assistant_delta_batch')],
      [event('3', 'assistant_completed')],
      [],
    ]
    const history = await loadThreadEventHistory(async (afterEventId, limit) => {
      expect(limit).toBe(2)
      expect(typeof afterEventId).toBe('string')
      if (afterEventId === '0') {
        return pages[0]
      }
      if (afterEventId === '2') {
        return pages[1]
      }
      return pages[2]
    }, 2)
    expect(history.map((item) => item.eventId)).toEqual(['1', '2', '3'])
  })

  it('pages with snowflake ids above Number.MAX_SAFE_INTEGER without precision loss', async () => {
    const fetchPage = vi.fn(async (afterEventId: string) => {
      if (afterEventId === '0') {
        return [event(SNOWFLAKE_A, 'assistant_started'), event(SNOWFLAKE_B, 'assistant_delta_batch')]
      }
      if (afterEventId === SNOWFLAKE_B) {
        return [event(SNOWFLAKE_C, 'assistant_completed')]
      }
      return []
    })
    const history = await loadThreadEventHistory(fetchPage, 2)
    expect(fetchPage).toHaveBeenNthCalledWith(1, '0', 2)
    expect(fetchPage).toHaveBeenNthCalledWith(2, SNOWFLAKE_B, 2)
    expect(history.map((item) => item.eventId)).toEqual([SNOWFLAKE_A, SNOWFLAKE_B, SNOWFLAKE_C])
    expect(lastEventIdCursor(history)).toBe(SNOWFLAKE_C)
  })

  it('supports refetching history from the cached last cursor', async () => {
    const cached = [event(SNOWFLAKE_A, 'assistant_started')]
    const page = await loadThreadEventHistory(
      async (afterEventId) => {
        expect(afterEventId).toBe(SNOWFLAKE_A)
        return [event(SNOWFLAKE_B, 'assistant_completed')]
      },
      200,
      lastEventIdCursor(cached),
    )
    expect(mergeThreadEventLists(cached, page).map((item) => item.eventId)).toEqual([
      SNOWFLAKE_A,
      SNOWFLAKE_B,
    ])
  })
})

function event(eventId: string, eventType: string): ThreadEventDTO {
  return {
    eventId,
    threadId: '1',
    subjectEntryId: '9',
    eventType,
    payloadJson: '{}',
    createTime: null,
  }
}
