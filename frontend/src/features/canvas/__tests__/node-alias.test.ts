import { describe, expect, it } from 'vitest'
import {
  normalizeNodeAlias,
  uniqueNodeAlias,
} from '@/features/canvas/node-alias'

describe('Canvas node aliases', () => {
  it('matches backend NFKC, trim, and case-insensitive uniqueness', () => {
    // Full-width and case variants collide exactly as backend name_normalized does.
    expect(uniqueNodeAlias('  Ｔｅｘｔ  ', ['text', 'Text 2'])).toBe('Text 3')
    expect(normalizeNodeAlias('  ＩＭＡＧＥ  ')).toBe('image')
  })

  it('keeps the requested alias when no normalized collision exists', () => {
    // Non-colliding user/file names remain stable instead of receiving speculative suffixes.
    expect(uniqueNodeAlias('photo.png', ['photo.jpg'])).toBe('photo.png')
  })
})
