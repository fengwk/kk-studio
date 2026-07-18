import { describe, expect, it } from 'vitest'
import {
  asEventId,
  compareEventIds,
  lastEventIdCursor,
  mergeThreadEvent,
  normalizeThreadEvent,
  parseThreadEvent,
} from '@/features/ai/harness-thread-event-stream'

describe('harness-thread-event-stream branches', () => {
  it('accepts only positive decimal strings for event ids', () => {
    expect(asEventId('34')).toBe('34')
    expect(asEventId('9007199254740993')).toBe('9007199254740993')
    expect(asEventId(12)).toBeNull()
    expect(asEventId('0')).toBeNull()
    expect(asEventId('x')).toBeNull()
    expect(asEventId(1.5)).toBeNull()
    expect(normalizeThreadEvent(null)).toBeNull()
    expect(normalizeThreadEvent({ eventId: '1', threadId: '1', eventType: 'x', payloadJson: 1 as never })).toMatchObject({
      payloadJson: '1',
    })
    expect(normalizeThreadEvent({ eventId: '1', threadId: '1', eventType: 'x', payloadJson: null })).toMatchObject({
      payloadJson: '',
    })
    expect(parseThreadEvent('{"eventId":"1","threadId":"1","eventType":"x"}')).toMatchObject({ eventId: '1' })
  })

  it('compares decimal cursors without changing backend list order', () => {
    expect(compareEventIds('9', '10')).toBeLessThan(0)
    expect(compareEventIds('10', '10')).toBe(0)
    expect(compareEventIds('11', '10')).toBeGreaterThan(0)
    expect(compareEventIds('9007199254740993', '9007199254740994')).toBeLessThan(0)
    expect(lastEventIdCursor([])).toBe('0')
    expect(
      lastEventIdCursor([
        {
          eventId: '5',
          threadId: '1',
          subjectEntryId: null,
          eventType: 'x',
          payloadJson: '',
          createTime: null,
        },
        {
          eventId: '9007199254740993',
          threadId: '1',
          subjectEntryId: null,
          eventType: 'x',
          payloadJson: '',
          createTime: null,
        },
        {
          eventId: '12',
          threadId: '1',
          subjectEntryId: null,
          eventType: 'x',
          payloadJson: '',
          createTime: null,
        },
      ]),
    ).toBe('12')
    expect(mergeThreadEvent([], normalizeThreadEvent({ eventId: 'bad' }) as never)).toEqual([])
  })
})
