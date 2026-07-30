import { describe, expect, it } from 'vitest'
import { queryKeys } from '@/shared/lib/query-keys'

describe('queryKeys', () => {
  it('keeps global Harness policies in distinct cache scopes', () => {
    expect(queryKeys.harness.retryPolicy).toEqual(['harness', 'retry-policy'])
    expect(queryKeys.harness.realtimeStreamPolicy).toEqual(['harness', 'realtime-stream-policy'])
  })

  it('keeps one authoritative snapshot key per Thread and separate aggregate usage keys', () => {
    expect(queryKeys.threads.snapshot('thread-1')).toEqual(['threads', 'snapshot', 'thread-1'])
    expect(queryKeys.usage.session('session-1')).toEqual(['usage', 'sessions', 'session-1'])
    expect(queryKeys.usage.model(42)).toEqual(['usage', 'models', '42'])
  })
})
