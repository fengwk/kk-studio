import { describe, expect, it } from 'vitest'
import { mergeRootActivity, mergeRootActivityLists, parseRootActivity } from '@/features/ai/harness-root-activity-stream'
import type { RootActivityDTO } from '@/shared/api/contracts'

describe('harness root activity stream', () => {
  it('accepts complete root activity events with large decimal identifiers', () => {
    expect(parseRootActivity(JSON.stringify(activity('344139441037639680')))).toMatchObject({ eventId: '344139441037639680' })
    expect(parseRootActivity(JSON.stringify({ ...activity('1'), eventId: '0' }))).toBeNull()
    expect(parseRootActivity(JSON.stringify({ ...activity('1'), sequence: -1 }))).toBeNull()
    expect(parseRootActivity('{bad')).toBeNull()
  })

  it('deduplicates by event id and orders decimal Snowflake cursors without number coercion', () => {
    const small = activity('9')
    const large = activity('344139441037639680')
    const later = activity('344139441037639681')

    expect(mergeRootActivityLists([later], [large, small, later])).toEqual([small, large, later])
    expect(mergeRootActivity([small], small)).toEqual([small])
  })
})

function activity(eventId: string): RootActivityDTO {
  return {
    rootSessionId: '1',
    sessionId: '2',
    runId: '3',
    eventId,
    sequence: 1,
    type: 'subagent_started',
    payloadJson: '{}',
    createTime: '2026-06-20T02:00:00Z',
  }
}
