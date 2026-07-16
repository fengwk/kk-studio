import { describe, expect, it } from 'vitest'
import { queryKeys } from '@/shared/lib/query-keys'

describe('queryKeys', () => {
  it('creates stable usage keys with string ids', () => {
    // Exact tuples prevent scope collisions and prove numeric model ids are normalized before caching.
    expect(queryKeys.usage.run('run-1')).toEqual(['usage', 'runs', 'run-1'])
    expect(queryKeys.usage.session('session-1')).toEqual(['usage', 'sessions', 'session-1'])
    expect(queryKeys.usage.model(42)).toEqual(['usage', 'models', '42'])
  })
})
