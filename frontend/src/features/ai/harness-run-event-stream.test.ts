import { describe, expect, it } from 'vitest'
import { mergeRunEvent, mergeRunEventLists, parseRunEvent } from '@/features/ai/harness-run-event-stream'
import type { RunEventDTO } from '@/shared/api/contracts'

describe('harness run event stream', () => {
  it('accepts only complete positive-sequence run events', () => {
    expect(parseRunEvent(JSON.stringify(event(3)))).toMatchObject({ eventId: 'event-3', sequence: 3 })
    expect(parseRunEvent(JSON.stringify({ eventId: 'event', runId: 'run', type: 'run_started', sequence: 0 }))).toBeNull()
    expect(parseRunEvent('{bad')).toBeNull()
  })

  it('deduplicates by sequence and returns ordered event history', () => {
    expect(mergeRunEventLists([event(2)], [event(3), event(1), event(2)])).toEqual([event(1), event(2), event(3)])
    expect(mergeRunEvent([event(1)], event(1))).toEqual([event(1)])
  })
})

function event(sequence: number): RunEventDTO {
  return {
    eventId: `event-${sequence}`,
    runId: 'run-1',
    sequence,
    type: 'assistant_delta_batch',
    payloadJson: '{}',
    createTime: '2026-06-20T02:00:00Z',
  }
}
