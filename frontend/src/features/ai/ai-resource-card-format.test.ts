import { describe, expect, it } from 'vitest'
import { formatCompactList } from '@/features/ai/ai-resource-card-format'

describe('ai-resource-card-format', () => {
  it('compacts long lists with +N', () => {
    expect(formatCompactList(['a', 'b', 'c'], 3)).toBe('a, b, c')
    expect(formatCompactList(['a', 'b', 'c', 'd', 'e'], 2)).toBe('a, b +3')
    expect(formatCompactList([], 2)).toBe('—')
  })
})
