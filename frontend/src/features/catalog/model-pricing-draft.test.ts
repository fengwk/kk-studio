import { describe, expect, it } from 'vitest'
import type { ModelPricingDTO } from '@/shared/api/generated/contracts'
import {
  clonePricingDefinitionDraft,
  clonePricingDraft,
  emptyModelPricingDraft,
  findPricingConflicts,
  findPricingRangeOverrideIssues,
  findPricingTtlRowIssues,
  hasConfiguredPricing,
  normalizePricingDraft,
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
      input: '', output: '', cacheRead: '', cacheWrite: '', cacheWriteByTtl: [], reasoning: undefined,
    })
    expect(draft.definition.inputTokenRanges).toEqual([])
    expect(draft.changes).toEqual([])
    expect(normalizePricingDraft(draft)).toBeUndefined()
  })

  it('flags overlapping input ranges, duplicate service tiers and same-weekday time overlaps', () => {
    const draft = emptyModelPricingDraft()
    draft.definition.inputTokenRanges = [
      { draftKey: 'range-a', minInputTokens: '100', maxInputTokensExclusive: '201', rates: {}, cacheWriteTtlMode: 'inherit', cacheWriteByTtl: [] },
      { draftKey: 'range-b', minInputTokens: '200', maxInputTokensExclusive: '300', rates: {}, cacheWriteTtlMode: 'inherit', cacheWriteByTtl: [] },
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
      { draftKey: 'a', minInputTokens: '100', maxInputTokensExclusive: '', rates: {}, cacheWriteTtlMode: 'inherit', cacheWriteByTtl: [] },
      { draftKey: 'b', minInputTokens: '200', maxInputTokensExclusive: '300', rates: {}, cacheWriteTtlMode: 'inherit', cacheWriteByTtl: [] },
    ]
    expect(findPricingConflicts(draft.definition)).toEqual([
      { group: 'inputTokenRanges', keys: ['a', 'b'] },
    ])
    draft.definition.inputTokenRanges = [
      { draftKey: 'a', minInputTokens: '0', maxInputTokensExclusive: '100', rates: {}, cacheWriteTtlMode: 'inherit', cacheWriteByTtl: [] },
      { draftKey: 'b', minInputTokens: '100', maxInputTokensExclusive: '200', rates: {}, cacheWriteTtlMode: 'inherit', cacheWriteByTtl: [] },
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
      rates: { input: '2.5' }, cacheWriteTtlMode: 'inherit', cacheWriteByTtl: [],
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
      rates: { reasoning: null }, cacheWriteTtlMode: 'inherit' as const, cacheWriteByTtl: [],
    }
    draft.definition.inputTokenRanges = [range]
    expect(normalizePricingDraft(draft)!.rules).toEqual([{
      type: 'input_token_range', minInputTokens: 0, maxInputTokensExclusive: 100, reasoning: null,
    }])
    draft.definition.inputTokenRanges = [{ ...range, cacheWriteTtlMode: 'unpartitioned' }]
    expect(normalizePricingDraft(draft)!.rules[0]).toMatchObject({ cacheWriteByTtl: {} })
    draft.definition.inputTokenRanges = [{ ...range, cacheWriteTtlMode: 'custom', cacheWriteByTtl: [{ draftKey: 'ttl', seconds: '300', rate: '0.25' }] }]
    expect(normalizePricingDraft(draft)!.rules[0]).toMatchObject({ cacheWriteByTtl: { '300': '0.25' } })
  })

  it('round-trips wire TTL prices through keyed draft rows', () => {
    const pricing: ModelPricingDTO = {
      base: {
        input: '1', output: '2', cacheRead: '0.1', cacheWrite: null,
        cacheWriteByTtl: { '300': '0.25', '3600': '1' }, reasoning: null,
      },
      rules: [{
        type: 'input_token_range', minInputTokens: 0, maxInputTokensExclusive: 100, cacheWriteByTtl: { '600': '0.5' },
      }],
      changes: [],
    }
    const draft = clonePricingDraft(pricing)
    expect(draft.definition.base.cacheWriteByTtl.map(({ seconds, rate }) => [seconds, rate]))
      .toEqual([['300', '0.25'], ['3600', '1']])
    expect(new Set(draft.definition.base.cacheWriteByTtl.map((row) => row.draftKey)).size).toBe(2)
    expect(draft.definition.inputTokenRanges[0]).toMatchObject({
      cacheWriteTtlMode: 'custom', cacheWriteByTtl: [{ seconds: '600', rate: '0.5' }],
    })
    expect(normalizePricingDraft(draft)).toEqual(pricing)
  })

  it('rejects duplicate, blank or non-positive TTL seconds instead of silently merging rows', () => {
    const draft = emptyModelPricingDraft()
    setPricedBase(draft)
    draft.definition.base.cacheWriteByTtl = [
      { draftKey: 'a', seconds: '300', rate: '0.25' },
      { draftKey: 'b', seconds: '300', rate: '0.5' },
    ]
    expect(() => normalizePricingDraft(draft)).toThrow(/must not repeat 300 seconds/)
    draft.definition.base.cacheWriteByTtl = [{ draftKey: 'a', seconds: '300', rate: ' ' }]
    expect(() => normalizePricingDraft(draft)).toThrow(/pricing\.base\.cacheWriteByTtl\.300 rate is required/)
    draft.definition.base.cacheWriteByTtl = [{ draftKey: 'a', seconds: '0', rate: '1' }]
    expect(() => normalizePricingDraft(draft)).toThrow(/cacheWriteByTtl keys must be positive integer seconds/)
    draft.definition.base.cacheWriteByTtl = [{ draftKey: 'a', seconds: '', rate: '1' }]
    expect(() => normalizePricingDraft(draft)).toThrow(/cacheWriteByTtl keys must be positive integer seconds/)
  })

  it('requires at least one TTL row for custom range prices and omits inherited rows', () => {
    const draft = emptyModelPricingDraft()
    setPricedBase(draft)
    draft.definition.inputTokenRanges = [{
      draftKey: 'range', minInputTokens: '0', maxInputTokensExclusive: '100', rates: {},
      cacheWriteTtlMode: 'custom', cacheWriteByTtl: [],
    }]
    expect(() => normalizePricingDraft(draft)).toThrow(/custom TTL prices must have at least one row/)
    draft.definition.inputTokenRanges[0]!.cacheWriteByTtl = [{ draftKey: 'ttl', seconds: '0', rate: '1' }]
    expect(() => normalizePricingDraft(draft)).toThrow(/TTL must be positive seconds/)
    draft.definition.inputTokenRanges[0]!.cacheWriteByTtl = [{ draftKey: 'ttl', seconds: '600', rate: '' }]
    expect(() => normalizePricingDraft(draft)).toThrow(/pricing\.rules\[0\]\.TTL\.600 rate is required/)
    draft.definition.inputTokenRanges[0]!.cacheWriteTtlMode = 'inherit'
    draft.definition.inputTokenRanges[0]!.rates = { input: '2' }
    expect(normalizePricingDraft(draft)!.rules[0]).not.toHaveProperty('cacheWriteByTtl')
  })

  /** 服务端 ModelPricingValidator 拒绝“一个费率都不覆盖”的输入档位，本地必须在保存前以同一口径失败。 */
  it('rejects a token range that overrides no fee rate locally', () => {
    const draft = emptyModelPricingDraft()
    setPricedBase(draft)
    const range = {
      draftKey: 'range', minInputTokens: '0', maxInputTokensExclusive: '100', rates: {},
      cacheWriteTtlMode: 'inherit' as const, cacheWriteByTtl: [],
    }
    draft.definition.inputTokenRanges = [range]
    expect(() => normalizePricingDraft(draft)).toThrow(/pricing\.rules\[0\] must override at least one fee rate/)

    // 显式 0、已声明的 TTL 表（含空表）和显式 reasoning:null 都是合法覆盖，不能被误判。
    draft.definition.inputTokenRanges = [{ ...range, rates: { input: '0' } }]
    expect(normalizePricingDraft(draft)!.rules[0]).toMatchObject({ input: '0' })
    draft.definition.inputTokenRanges = [{ ...range, cacheWriteTtlMode: 'unpartitioned' }]
    expect(normalizePricingDraft(draft)!.rules[0]).toMatchObject({ cacheWriteByTtl: {} })
    draft.definition.inputTokenRanges = [{ ...range, rates: { reasoning: null } }]
    expect(normalizePricingDraft(draft)!.rules[0]).toMatchObject({ reasoning: null })
    draft.definition.inputTokenRanges = [{ ...range, rates: { cacheWrite: '0' } }]
    expect(normalizePricingDraft(draft)!.rules[0]).toMatchObject({ cacheWrite: '0' })
  })

  /** 行级提示只定位边界已成立的行，避免新行刚创建就报错，也避免与先校验边界的顺序冲突。 */
  it('localizes empty-override rows only after their bounds hold', () => {
    const definition = emptyModelPricingDraft().definition
    const range = {
      draftKey: 'range', minInputTokens: '', maxInputTokensExclusive: '', rates: {},
      cacheWriteTtlMode: 'inherit' as const, cacheWriteByTtl: [],
    }
    definition.inputTokenRanges = [range]
    expect(findPricingRangeOverrideIssues(definition)).toEqual([])

    definition.inputTokenRanges = [{ ...range, minInputTokens: '100' }]
    expect(findPricingRangeOverrideIssues(definition)).toEqual(['range'])

    definition.inputTokenRanges = [{ ...range, rates: { output: '0' } }]
    expect(findPricingRangeOverrideIssues(definition)).toEqual([])

    // 边界本身无效（非规范整数、上界不大于下界）时仍按边界报错，不重复给覆盖提示。
    definition.inputTokenRanges = [{ ...range, minInputTokens: '007', maxInputTokensExclusive: '100' }]
    expect(findPricingRangeOverrideIssues(definition)).toEqual([])
    definition.inputTokenRanges = [{ ...range, minInputTokens: '200', maxInputTokensExclusive: '100' }]
    expect(findPricingRangeOverrideIssues(definition)).toEqual([])
  })

  it('reports invalid, duplicate and blank TTL row issues for inline editor messages', () => {
    expect(findPricingTtlRowIssues([
      { draftKey: 'a', seconds: '', rate: '1' },
      { draftKey: 'b', seconds: '0', rate: '1' },
      { draftKey: 'c', seconds: '600', rate: '1' },
      { draftKey: 'd', seconds: '600', rate: '0.5' },
      { draftKey: 'e', seconds: '900', rate: ' ' },
    ])).toEqual({
      a: 'invalidSeconds', b: 'invalidSeconds', c: 'duplicateSeconds', d: 'duplicateSeconds', e: 'rateRequired',
    })
  })

  it('copies TTL rows into fresh keys and counts them as configured pricing', () => {
    const source = emptyModelPricingDraft().definition
    source.base.cacheWriteByTtl = [{ draftKey: 'ttl', seconds: '300', rate: '0.25' }]
    const copied = clonePricingDefinitionDraft(source)
    expect(copied.base.cacheWriteByTtl).toEqual([{ draftKey: expect.any(String), seconds: '300', rate: '0.25' }])
    expect(copied.base.cacheWriteByTtl[0]!.draftKey).not.toBe('ttl')
    copied.base.cacheWriteByTtl[0]!.rate = '9'
    expect(source.base.cacheWriteByTtl[0]!.rate).toBe('0.25')
    expect(hasConfiguredPricing({ definition: source, changes: [] })).toBe(true)
    expect(hasConfiguredPricing(emptyModelPricingDraft())).toBe(false)
  })

  it('orders UTC price changes and rejects duplicate or invalid dates', () => {
    const draft = emptyModelPricingDraft()
    setPricedBase(draft)
    const first = emptyModelPricingDraft().definition
    const second = emptyModelPricingDraft().definition
    first.base.input = '1'; first.base.output = '2'; first.base.cacheRead = '0.1'
    second.base.input = '1'; second.base.output = '2'; second.base.cacheRead = '0.1'
    draft.changes = [
      { draftKey: 'c2', from: '2027-03-01', definition: second },
      { draftKey: 'c1', from: '2027-01-01', definition: first },
    ]
    expect(normalizePricingDraft(draft)?.changes.map((change) => change.from)).toEqual(['2027-01-01', '2027-03-01'])
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

})
