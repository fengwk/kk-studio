import { describe, expect, it } from 'vitest'
import {
  emptyModelPricingDraft,
  findPricingConflicts,
  multiplyPriceFactors,
  normalizePricingDraft,
  pricingDefinitionAt,
  splitCrossMidnightPeriod,
} from '@/features/catalog/model-pricing-draft'

function setPricedBase(draft: ReturnType<typeof emptyModelPricingDraft>) {
  draft.definition.base.input = '1'
  draft.definition.base.output = '2'
  draft.definition.base.cacheRead = '0.1'
}

describe('local model pricing draft', () => {
  it('starts unpriced without creating a wire pricing object or default zeroes', () => {
    const draft = emptyModelPricingDraft()
    expect(draft.definition.base).toEqual({
      input: '', output: '', cacheRead: '', cacheWrite: '', cacheWriteByTtl: {}, reasoning: undefined,
    })
    expect(draft.definition.inputTokenRanges).toEqual([])
    expect(draft.changes).toEqual([])
    expect(normalizePricingDraft(draft)).toBeUndefined()
  })

  it('flags overlapping input ranges, duplicate service tiers and same-weekday time overlaps', () => {
    const draft = emptyModelPricingDraft()
    draft.definition.inputTokenRanges = [
      { draftKey: 'range-a', minInputTokens: '100', maxInputTokensExclusive: '201', rates: {}, cacheWriteTtlMode: 'inherit', cacheWriteByTtl: {} },
      { draftKey: 'range-b', minInputTokens: '200', maxInputTokensExclusive: '300', rates: {}, cacheWriteTtlMode: 'inherit', cacheWriteByTtl: {} },
    ]
    draft.definition.serviceTiers = [
      { draftKey: 'tier-a', serviceTier: 'fast', factors: { factor: '1', inputFactor: '', outputFactor: '', cacheReadFactor: '', cacheWriteFactor: '', reasoningFactor: '' } },
      { draftKey: 'tier-b', serviceTier: 'fast', factors: { factor: '1', inputFactor: '', outputFactor: '', cacheReadFactor: '', cacheWriteFactor: '', reasoningFactor: '' } },
    ]
    const factors = { factor: '1', inputFactor: '', outputFactor: '', cacheReadFactor: '', cacheWriteFactor: '', reasoningFactor: '' }
    draft.definition.timePeriods = [
      { draftKey: 'time-a', weekdays: [1], start: '01:00', end: '03:00', timezone: 'UTC', factors },
      { draftKey: 'time-b', weekdays: [1, 2], start: '02:00', end: '04:00', timezone: 'UTC', factors },
    ]
    expect(findPricingConflicts(draft.definition)).toEqual([
      { group: 'inputTokenRanges', keys: ['range-a', 'range-b'] },
      { group: 'serviceTiers', keys: ['tier-a', 'tier-b'] },
      { group: 'timePeriods', keys: ['time-a', 'time-b'] },
    ])
  })

  it('flags overlapping open-ended input ranges and allows adjacent half-open ranges and disjoint weekdays', () => {
    const draft = emptyModelPricingDraft()
    draft.definition.inputTokenRanges = [
      { draftKey: 'a', minInputTokens: '100', maxInputTokensExclusive: '', rates: {}, cacheWriteTtlMode: 'inherit', cacheWriteByTtl: {} },
      { draftKey: 'b', minInputTokens: '200', maxInputTokensExclusive: '300', rates: {}, cacheWriteTtlMode: 'inherit', cacheWriteByTtl: {} },
    ]
    expect(findPricingConflicts(draft.definition)).toEqual([
      { group: 'inputTokenRanges', keys: ['a', 'b'] },
    ])
    draft.definition.inputTokenRanges = [
      { draftKey: 'a', minInputTokens: '0', maxInputTokensExclusive: '100', rates: {}, cacheWriteTtlMode: 'inherit', cacheWriteByTtl: {} },
      { draftKey: 'b', minInputTokens: '100', maxInputTokensExclusive: '200', rates: {}, cacheWriteTtlMode: 'inherit', cacheWriteByTtl: {} },
    ]
    const factors = { factor: '1', inputFactor: '', outputFactor: '', cacheReadFactor: '', cacheWriteFactor: '', reasoningFactor: '' }
    draft.definition.timePeriods = [
      { draftKey: 'a', weekdays: [1], start: '01:00', end: '02:00', timezone: 'UTC', factors },
      { draftKey: 'b', weekdays: [2], start: '01:30', end: '02:30', timezone: 'UTC', factors },
    ]
    expect(findPricingConflicts(draft.definition)).toEqual([])
  })

  it('splits cross-midnight windows and advances weekday using ISO numbering', () => {
    const pieces = splitCrossMidnightPeriod({
      weekdays: [1, 7], start: '22:30', end: '02:15', timezone: 'UTC',
      factors: { factor: '1', inputFactor: '', outputFactor: '', cacheReadFactor: '', cacheWriteFactor: '', reasoningFactor: '' },
    }, (() => { let n = 0; return () => `key-${++n}` })())
    expect(pieces.map(({ draftKey, weekdays, start, end }) => ({ draftKey, weekdays, start, end }))).toEqual([
      { draftKey: 'key-1', weekdays: [1, 7], start: '22:30', end: '24:00' },
      { draftKey: 'key-2', weekdays: [1, 2], start: '00:00', end: '02:15' },
    ])
  })

  it('normalizes safe local pricing while omitting draft keys and preserving unknown prices and explicit zero', () => {
    const draft = emptyModelPricingDraft()
    draft.definition.base.input = '0'
    draft.definition.base.output = '1.2500'
    draft.definition.base.cacheRead = '0.3'
    draft.definition.inputTokenRanges = [{
      draftKey: 'client-only', minInputTokens: '200000', maxInputTokensExclusive: '300001',
      rates: { input: '2.5' }, cacheWriteTtlMode: 'inherit', cacheWriteByTtl: {},
    }]
    const plan = normalizePricingDraft(draft)!
    expect(plan.base).toMatchObject({ input: '0', output: '1.2500', cacheRead: '0.3', cacheWrite: null })
    expect(plan.rules).toEqual([{
      type: 'input_token_range', minInputTokens: 200000, maxInputTokensExclusive: 300001, input: '2.5',
    }])
    expect(JSON.stringify(plan)).not.toContain('draftKey')
  })

  it('preserves the three reasoning states and the distinction between inherited and empty TTL prices', () => {
    const draft = emptyModelPricingDraft()
    setPricedBase(draft)
    expect(normalizePricingDraft(draft)!.base).not.toHaveProperty('reasoning')
    draft.definition.base.reasoning = null
    expect(normalizePricingDraft(draft)!.base.reasoning).toBeNull()
    draft.definition.base.reasoning = '0'
    expect(normalizePricingDraft(draft)!.base.reasoning).toBe('0')

    const range = {
      draftKey: 'range', minInputTokens: '0', maxInputTokensExclusive: '100',
      rates: { reasoning: null }, cacheWriteTtlMode: 'inherit' as const, cacheWriteByTtl: {},
    }
    draft.definition.inputTokenRanges = [range]
    expect(normalizePricingDraft(draft)!.rules).toEqual([{
      type: 'input_token_range', minInputTokens: 0, maxInputTokensExclusive: 100, reasoning: null,
    }])
    draft.definition.inputTokenRanges = [{ ...range, cacheWriteTtlMode: 'unpartitioned' }]
    expect(normalizePricingDraft(draft)!.rules[0]).toMatchObject({ cacheWriteByTtl: {} })
    draft.definition.inputTokenRanges = [{ ...range, cacheWriteTtlMode: 'custom', cacheWriteByTtl: { '300': '0.25' } }]
    expect(normalizePricingDraft(draft)!.rules[0]).toMatchObject({ cacheWriteByTtl: { '300': '0.25' } })
  })

  it('validates date changes, detects duplicate effective dates and treats missing date as unknown', () => {
    const draft = emptyModelPricingDraft()
    setPricedBase(draft)
    const first = emptyModelPricingDraft().definition
    const second = emptyModelPricingDraft().definition
    first.base.input = '1'; first.base.output = '2'; first.base.cacheRead = '0.1'
    second.base.input = '1'; second.base.output = '2'; second.base.cacheRead = '0.1'
    draft.changes = [
      { draftKey: 'c1', from: '2027-01-01', definition: first },
      { draftKey: 'c2', from: '2027-03-01', definition: second },
    ]
    const plan = normalizePricingDraft(draft)!
    expect(pricingDefinitionAt(plan)).toBeNull()
    expect(pricingDefinitionAt(plan, '2027-02-01T00:00:00Z')).toEqual({ base: plan.base, rules: plan.rules })
    expect(pricingDefinitionAt(plan, '2027-03-01T00:00:00Z')).toEqual({ base: plan.changes[1]!.base, rules: plan.changes[1]!.rules })
    expect(() => normalizePricingDraft({
      ...draft, changes: [...draft.changes, { ...draft.changes[0]!, draftKey: 'duplicate' }],
    })).toThrow(/duplicate pricing change date/)
    expect(() => normalizePricingDraft({
      ...draft, changes: [{ ...draft.changes[0]!, from: '2027-02-30' }],
    })).toThrow(/UTC date/)
  })

  it('rejects inconsistent timezone groups and bad rule factors', () => {
    const draft = emptyModelPricingDraft()
    setPricedBase(draft)
    const factors = { factor: '1', inputFactor: '', outputFactor: '', cacheReadFactor: '', cacheWriteFactor: '', reasoningFactor: '' }
    draft.definition.timePeriods = [
      { draftKey: 'a', weekdays: [], start: '00:00', end: '12:00', timezone: 'UTC', factors },
      { draftKey: 'b', weekdays: [], start: '12:00', end: '24:00', timezone: 'Europe/Paris', factors },
    ]
    expect(() => normalizePricingDraft(draft)).toThrow(/share one timezone/)
    draft.definition.timePeriods[0]!.timezone = 'UTC'
    draft.definition.serviceTiers = [{ draftKey: 'tier', serviceTier: 'fast', factors: { ...factors, outputFactor: '-1' } }]
    expect(() => normalizePricingDraft(draft)).toThrow(/non-negative decimal/)
  })

  it('multiplies monetary values exactly as decimal strings', () => {
    expect(multiplyPriceFactors('0.15', ['26', '1.2'])).toBe('4.68')
    expect(multiplyPriceFactors('9007199254740993.01', ['1'])).toBe('9007199254740993.01')
    expect(() => multiplyPriceFactors('1.2', ['-1'])).toThrow(/non-negative decimals/)
  })
})
