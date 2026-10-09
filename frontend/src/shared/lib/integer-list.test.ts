import { describe, expect, it } from 'vitest'
import {
  assembleHttpStatusCodeList,
  assembleIntegerList,
  IntegerListValidationError,
  parseIntegerListTokens,
  splitTagInputDraft,
  validateIntegerListTokens,
} from './integer-list'

describe('integer-list helpers', () => {
  it('splits tag input draft into committed numbers and at most one pending string', () => {
    expect(splitTagInputDraft([408, 429, '500'])).toEqual({
      committed: [408, 429],
      pending: '500',
    })
    expect(splitTagInputDraft([408, 429])).toEqual({
      committed: [408, 429],
      pending: null,
    })
    expect(splitTagInputDraft(['abc'])).toEqual({
      committed: [],
      pending: 'abc',
    })
    expect(splitTagInputDraft([])).toEqual({
      committed: [],
      pending: null,
    })
  })

  it('parses comma-separated and whitespace-separated tokens', () => {
    expect(parseIntegerListTokens('408, 429   500,502')).toEqual(['408', '429', '500', '502'])
    expect(parseIntegerListTokens('408, , 500')).toEqual(['408', '', '500'])
  })

  it('validates tokens with bounds, safe integer check, and duplicate checks', () => {
    const { valid, error } = validateIntegerListTokens(['408', '429'], [500], { min: 400, max: 599 })
    expect(error).toBeNull()
    expect(valid).toEqual([408, 429])

    // Duplicate against existing
    const dupResult = validateIntegerListTokens(['500'], [500], { min: 400, max: 599 })
    expect(dupResult.error?.code).toBe('duplicate')

    // Out of range
    const rangeResult = validateIntegerListTokens(['399'], [], { min: 400, max: 599 })
    expect(rangeResult.error?.code).toBe('outOfRange')

    // Non-integer string / floating point
    const notIntResult = validateIntegerListTokens(['abc'], [], { min: 400, max: 599 })
    expect(notIntResult.error?.code).toBe('notInteger')

    const floatResult = validateIntegerListTokens(['400.5'], [], { min: 400, max: 599 })
    expect(floatResult.error?.code).toBe('notInteger')

    // Unsafe integer exceeding MAX_SAFE_INTEGER
    const unsafeResult = validateIntegerListTokens(['99999999999999999999'], ['-10'])
    expect(unsafeResult.error?.code).toBe('notInteger')

    // Empty token
    const emptyResult = validateIntegerListTokens([''], [])
    expect(emptyResult.error?.code).toBe('emptyToken')

    // Unset min/max allows any safe integer
    const noBounds = validateIntegerListTokens(['-10', '0', '1000'], [])
    expect(noBounds.error).toBeNull()
    expect(noBounds.valid).toEqual([-10, 0, 1000])
  })

  it('assembles integer list rejecting unsafe numbers, floats, out-of-range, and duplicates', () => {
    expect(assembleHttpStatusCodeList([408, '429', 500])).toEqual([408, 429, 500])

    expect(() => assembleIntegerList(null)).toThrow(
      expect.objectContaining<Partial<IntegerListValidationError>>({ code: 'notInteger' }),
    )
    expect(() => assembleIntegerList([true])).toThrow(
      expect.objectContaining<Partial<IntegerListValidationError>>({ code: 'notInteger' }),
    )
    expect(() => assembleIntegerList([''])).toThrow(
      expect.objectContaining<Partial<IntegerListValidationError>>({ code: 'notInteger' }),
    )
    expect(() => assembleIntegerList(['99999999999999999999'])).toThrow(
      expect.objectContaining<Partial<IntegerListValidationError>>({ code: 'notInteger' }),
    )
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
    expect(() => assembleHttpStatusCodeList([500.5])).toThrow(
      expect.objectContaining<Partial<IntegerListValidationError>>({ code: 'notInteger' }),
    )
    expect(() => assembleHttpStatusCodeList([Infinity])).toThrow(
      expect.objectContaining<Partial<IntegerListValidationError>>({ code: 'notInteger' }),
    )

    // assembleIntegerList without bounds accepts any safe integer
    expect(assembleIntegerList([-5, '0', 999999])).toEqual([-5, 0, 999999])
  })
})
