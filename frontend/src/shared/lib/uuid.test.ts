import { afterEach, describe, expect, it, vi } from 'vitest'
import { createUuid, UUID_PATTERN } from '@/shared/lib/uuid'

describe('createUuid', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('produces canonical RFC4122 v4 UUIDs via randomUUID when available', () => {
    for (let index = 0; index < 20; index += 1) {
      expect(createUuid()).toMatch(UUID_PATTERN)
    }
  })

  it('falls back to getRandomValues with the same canonical form when randomUUID is unavailable', () => {
    // Node/jsdom 均提供 randomUUID；显式移除以强制走 v4 fallback。
    vi.stubGlobal('crypto', {
      ...crypto,
      randomUUID: undefined,
      getRandomValues: crypto.getRandomValues.bind(crypto),
    })
    for (let index = 0; index < 20; index += 1) {
      expect(createUuid()).toMatch(UUID_PATTERN)
    }
  })
})
