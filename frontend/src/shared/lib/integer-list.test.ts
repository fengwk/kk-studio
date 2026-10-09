import { describe, expect, it } from 'vitest'
import {
  assembleHttpStatusCodeList,
  assembleIntegerList,
  IntegerListValidationError,
  parseIntegerListTokens,
  validateIntegerListTokens,
} from './integer-list'

describe('integer-list helpers', () => {
  it('parses comma-separated and whitespace-separated tokens', () => {
    expect(parseIntegerListTokens('408, 429   500,502')).toEqual(['408', '429', '500', '502'])
    expect(parseIntegerListTokens('408, , 500')).toEqual(['408', '', '500'])
  })

  it('validates tokens with bounds and duplicate checks', () => {
    const { valid, error } = validateIntegerListTokens(['408', '429'], [500], { min: 400, max: 599 })
    expect(error).toBeNull()
    expect(valid).toEqual([408, 429])

    // Duplicate against existing
    const dupResult = validateIntegerListTokens(['500'], [500], { min: 400, max: 599 })
    expect(dupResult.error?.code).toBe('duplicate')

    // Out of range
    const rangeResult = validateIntegerListTokens(['399'], [], { min: 400, max: 599 })
    expect(rangeResult.error?.code).toBe('outOfRange')

    // Not integer
    const notIntResult = validateIntegerListTokens(['abc'], [], { min: 400, max: 599 })
    expect(notIntResult.error?.code).toBe('notInteger')
  })

  it('assembles HTTP status code list rejecting non-integers, out-of-range and duplicates', () => {
    expect(assembleHttpStatusCodeList([408, '429', 500])).toEqual([408, 429, 500])

    expect(() => assembleHttpStatusCodeList([200])).toThrow(
      expect.objectContaining<Partial<IntegerListValidationError>>({ code: 'outOfRange' }),
    )
    expect(() => assembleHttpStatusCodeList([600])).toThrow(
      expect.objectContaining<Partial<IntegerListValidationError>>({ code: 'outOfRange' }),
    )
    expect(() => assembleHttpStatusCodeList([429, '429'])).toThrow(
      expect.objectContaining<Partial<IntegerListValidationError>>({ code: 'duplicate' }),
    )
    expect(() => assembleHttpStatusCodeList(['not-a-number'])).toThrow(
      expect.objectContaining<Partial<IntegerListValidationError>>({ code: 'notInteger' }),
    )
  })
})
