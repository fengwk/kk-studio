import { describe, expect, it } from 'vitest'
import { ThinkTagTextFilter } from '@/features/ai/session-event-think-filter'

describe('ThinkTagTextFilter', () => {
  it('preserves visible text around complete think blocks and trims their separator whitespace', () => {
    const filter = new ThinkTagTextFilter()

    expect(filter.append('before<think>private</think>   after')).toBe('beforeafter')
    expect(filter.finish()).toBe('')
  })

  it('recognizes tags split across chunks and removes incomplete hidden tails on finish', () => {
    const filter = new ThinkTagTextFilter()

    expect(filter.append('visible <thi')).toBe('visible ')
    expect(filter.append('nk>private')).toBe('')
    expect(filter.append('</th')).toBe('')
    expect(filter.append('ink>\nanswer')).toBe('answer')
    expect(filter.append('<think>unclosed')).toBe('')
    expect(filter.finish()).toBe('')
  })

  it('handles empty chunks and unmatched visible tag prefixes', () => {
    const filter = new ThinkTagTextFilter()

    expect(filter.append('')).toBe('')
    expect(filter.append('plain <thin')).toBe('plain ')
    expect(filter.finish()).toBe('<thin')
  })
})
