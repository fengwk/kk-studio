import { describe, expect, it } from 'vitest'
import { formatModelLabel } from '@/features/catalog/model-labels'

describe('formatModelLabel', () => {
  it('formats logical model names with an optional variant without inventing defaults', () => {
    expect(formatModelLabel('MiniMax-M2.7', 'high')).toBe('MiniMax-M2.7 (high)')
    expect(formatModelLabel('grok-4.7', 'xhigh')).toBe('grok-4.7 (xhigh)')
    expect(formatModelLabel('MiniMax-M2.7')).toBe('MiniMax-M2.7')
    expect(formatModelLabel('group/deep-model', 'deep')).toBe('group/deep-model (deep)')
    expect(formatModelLabel('MiniMax-M2.7', null)).toBe('MiniMax-M2.7')
    expect(formatModelLabel('MiniMax-M2.7', undefined)).toBe('MiniMax-M2.7')
    expect(formatModelLabel('MiniMax-M2.7', '')).toBe('MiniMax-M2.7')
    expect(formatModelLabel('MiniMax-M2.7', '   ')).toBe('MiniMax-M2.7')
    expect(formatModelLabel('', 'v1')).toBe('v1')
    expect(formatModelLabel('', '')).toBe('unknown-model')
  })
})
