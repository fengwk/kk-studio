import { describe, expect, it } from 'vitest'
import type { AgentSessionEventDTO } from '@/shared/api/contracts'
import { mergeSessionEvent, mergeSessionEventLists, parseSessionEvent } from '@/features/ai/session-event-stream'

describe('session-event-stream', () => {
  it('parses valid session events and rejects malformed payloads', () => {
    expect(parseSessionEvent(JSON.stringify(event('event-1')))).toEqual(event('event-1'))
    expect(parseSessionEvent('{broken')).toBeNull()
    expect(parseSessionEvent(JSON.stringify({ sessionId: 'session-1', eventType: 'assistant_delta' }))).toBeNull()
  })

  it('deduplicates streamed events by eventId', () => {
    const base = [event('event-1')]
    expect(mergeSessionEvent(base, event('event-1'))).toBe(base)
    expect(mergeSessionEvent(base, event('event-2'))).toEqual([event('event-1'), event('event-2')])
  })

  it('merges cached and snapshot event lists while preserving order of first appearance', () => {
    expect(mergeSessionEventLists([event('event-1')], [event('event-1'), event('event-2'), event('event-3')])).toEqual([
      event('event-1'),
      event('event-2'),
      event('event-3'),
    ])
  })
})

function event(eventId: string): AgentSessionEventDTO {
  return {
    eventId,
    sessionId: 'session-1',
    parentEventId: 'root',
    runId: 'run-1',
    eventType: 'assistant_delta',
    payloadType: 'json',
    payloadJson: '{"textDelta":"hello"}',
    createTime: '2026-06-20T02:00:00',
  }
}
