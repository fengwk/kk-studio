import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  CANONICAL_UUID_PATTERN,
  createUuid,
  isCanonicalUuid,
} from '@/shared/lib/uuid'

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

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

describe('isCanonicalUuid', () => {
  it('accepts lowercase canonical UUIDs', () => {
    expect(isCanonicalUuid('8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f')).toBe(true)
    expect(isCanonicalUuid('123e4567-e89b-12d3-a456-426614174000')).toBe(true)
  })

  it('rejects malformed or non-canonical strings', () => {
    expect(isCanonicalUuid('8D3B8A2E-4B9F-4C5D-9E6F-1A2B3C4D5E6F')).toBe(false)
    expect(isCanonicalUuid('')).toBe(false)
    expect(isCanonicalUuid('not-a-uuid')).toBe(false)
    expect(isCanonicalUuid('8d3b8a2e4b9f4c5d9e6f1a2b3c4d5e6f')).toBe(false)
    expect(isCanonicalUuid('8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6')).toBe(false)
    expect(isCanonicalUuid('8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f0')).toBe(false)
    expect(isCanonicalUuid('zg3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f')).toBe(false)
  })

  // 测试意图：存储/传输的未知值直接喂给校验时必须是 false，而不需要调用方先做类型收窄。
  it('rejects unknown non-string inputs', () => {
    expect(isCanonicalUuid(null)).toBe(false)
    expect(isCanonicalUuid(undefined)).toBe(false)
    expect(isCanonicalUuid(42)).toBe(false)
    expect(isCanonicalUuid({ id: '8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f' })).toBe(false)
    expect(isCanonicalUuid(['8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f'])).toBe(false)
  })

  it('matches pattern directly', () => {
    expect(CANONICAL_UUID_PATTERN.test('8d3b8a2e-4b9f-4c5d-9e6f-1a2b3c4d5e6f')).toBe(true)
    expect(CANONICAL_UUID_PATTERN.test('invalid')).toBe(false)
  })
})
