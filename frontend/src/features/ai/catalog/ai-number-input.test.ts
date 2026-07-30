import { describe, expect, it } from 'vitest'
import { sanitizeDecimalInput, sanitizeIntegerInput } from '@/features/ai/catalog/ai-number-input'

describe('ai-number-input', () => {
  it('keeps only digits for integers', () => {
    expect(sanitizeIntegerInput('12a3.4-5')).toBe('12345')
    expect(sanitizeIntegerInput('')).toBe('')
  })

  it('allows a single decimal point for decimals', () => {
    expect(sanitizeDecimalInput('1.2.3a')).toBe('1.23')
    expect(sanitizeDecimalInput('0.5')).toBe('0.5')
    expect(sanitizeDecimalInput('12.')).toBe('12.')
    expect(sanitizeDecimalInput('')).toBe('')
  })
})
