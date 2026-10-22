import { describe, expect, it } from 'vitest'
import type {
  AgentModelPricingConditionDTO,
  AgentModelPricingDTO,
} from '@/shared/api/generated/contracts'
import {
  MAX_CONDITION_CHILDREN,
  MAX_CONDITION_DEPTH,
  clonePricing,
  describeCondition,
  emptyPricingDraft,
  normalizeCondition,
  normalizePricing,
} from '@/features/catalog/ai-model-pricing-codec'

function pricing(): AgentModelPricingDTO {
  return {
    base: {
      input: '1.1',
      output: '2.2',
      cacheRead: '0.3',
      cacheWrite: '0.4',
      cacheWriteByTtl: { '3600': '0.5' },
      reasoning: '0',
    },
    overrides: [
      {
        id: 'batch',
        when: { serviceTier: ['batch'] },
        rates: { output: '0', reasoning: null, cacheRead: '' },
      },
    ],
    multipliers: [
      {
        id: 'priority',
        when: { time: { start: '09:00', end: '18:00', timezone: 'Asia/Shanghai', weekdays: [1, 5] } },
        factors: { input: '1.25' },
      },
    ],
  }
}

describe('ai-model-pricing-codec', () => {
  it('normalizes the complete definition without converting decimals through numbers', () => {
    expect(normalizePricing(pricing())).toEqual({
      base: {
        input: '1.1',
        output: '2.2',
        cacheRead: '0.3',
        cacheWrite: '0.4',
        cacheWriteByTtl: { '3600': '0.5' },
        reasoning: '0',
      },
      overrides: [
        {
          id: 'batch',
          when: { serviceTier: ['batch'] },
          rates: { output: '0', reasoning: null },
        },
      ],
      multipliers: [
        {
          id: 'priority',
          when: { time: { start: '09:00', end: '18:00', timezone: 'Asia/Shanghai', weekdays: [1, 5] } },
          factors: { input: '1.25' },
        },
      ],
    })
  })

  it('round-trips zero-edit pricing while preserving explicit empty TTL and false conditions', () => {
    const source: AgentModelPricingDTO = {
      base: {
        input: '1',
        output: '2',
        cacheRead: '0.1',
        cacheWrite: '0.2',
        cacheWriteByTtl: {},
        reasoning: null,
      },
      overrides: [{
        id: 'all-day',
        when: {
          serviceTier: [],
          any: [],
          time: { start: '12:00', end: '24:00', timezone: 'UTC' },
        },
        rates: { cacheWriteByTtl: {}, reasoning: null },
      }],
      multipliers: [],
    }

    const roundTrip = normalizePricing(
      clonePricing(JSON.parse(JSON.stringify(source)) as AgentModelPricingDTO),
    )
    expect(roundTrip).toEqual(source)
    expect(roundTrip.overrides[0]!.when.serviceTier).toEqual([])
    expect(roundTrip.overrides[0]!.when.any).toEqual([])
    expect(roundTrip.overrides[0]!.rates.cacheWriteByTtl).toEqual({})
  })

  it('accepts end 24:00 after any valid start and rejects equal bounds', () => {
    const time = (start: string, end: string): AgentModelPricingConditionDTO => ({
      time: { start, end, timezone: 'UTC' },
    })

    expect(normalizeCondition(time('00:00', '24:00')).time).toEqual({
      start: '00:00', end: '24:00', timezone: 'UTC',
    })
    expect(normalizeCondition(time('06:30', '24:00')).time).toEqual({
      start: '06:30', end: '24:00', timezone: 'UTC',
    })
    expect(() => normalizeCondition(time('00:00', '00:00'))).toThrow(/must differ/)
    expect(() => normalizeCondition(time('09:00', '09:00'))).toThrow(/must differ/)
    expect(() => normalizeCondition(time('24:00', '24:00'))).toThrow(/start/)
    expect(() => normalizeCondition(time('12:00', '24:01'))).toThrow(/end/)
  })

  it('honors the backend condition depth and list bounds without exponential fixtures', () => {
    const nestedNot = (depth: number): AgentModelPricingConditionDTO => {
      let condition: AgentModelPricingConditionDTO = {}
      for (let index = 0; index < depth; index += 1) {
        condition = { not: condition }
      }
      return condition
    }
    const children = Array.from({ length: MAX_CONDITION_CHILDREN }, () => ({}))
    const tiers = Array.from({ length: MAX_CONDITION_CHILDREN }, (_, index) => `tier-${index}`)

    expect(normalizeCondition(nestedNot(MAX_CONDITION_DEPTH)).not).toBeDefined()
    expect(() => normalizeCondition(nestedNot(MAX_CONDITION_DEPTH + 1))).toThrow(/depth/)
    expect(normalizeCondition({ any: children }).any).toHaveLength(MAX_CONDITION_CHILDREN)
    expect(() => normalizeCondition({ any: [...children, {}] })).toThrow(/child count/)
    expect(normalizeCondition({ serviceTier: tiers }).serviceTier).toHaveLength(MAX_CONDITION_CHILDREN)
    expect(() => normalizeCondition({ serviceTier: [...tiers, 'overflow'] }))
      .toThrow(/serviceTier/)
  })

  it('keeps null reasoning as following output and preserves explicit zero', () => {
    const draft = emptyPricingDraft()
    expect(normalizePricing(draft).base.reasoning).toBeNull()
    expect(normalizePricing({
      ...draft,
      base: { ...draft.base, reasoning: '0' },
    }).base.reasoning).toBe('0')
  })

  it('rejects partial or invalid base rates instead of filling omitted fields with zero', () => {
    const draft = emptyPricingDraft()
    expect(() => normalizePricing({
      ...draft,
      base: { ...draft.base, input: '' },
    })).toThrow(/pricing\.base\.input/)
    expect(() => normalizePricing({
      ...draft,
      base: { ...draft.base, output: '-1' },
    })).toThrow(/pricing\.base\.output/)
    expect(() => normalizePricing({
      ...draft,
      base: { ...draft.base, cacheWriteByTtl: { '0': '1' } },
    })).toThrow(/TTL keys/)
  })

  it('validates structured conditions, including mutual exclusion and IANA zones', () => {
    const draft = emptyPricingDraft()
    expect(() => normalizePricing({
      ...draft,
      overrides: [{ id: 'bad-context', when: { context: { gt: 1, gte: 1 } }, rates: {} }],
    })).toThrow(/cannot set both gt and gte/)
    expect(() => normalizePricing({
      ...draft,
      overrides: [{
        id: 'bad-zone',
        when: { time: { start: '09:00', end: '18:00', timezone: 'Mars/Olympus' } },
        rates: {},
      }],
    })).toThrow(/IANA time zone/)
    expect(() => normalizePricing({
      ...draft,
      multipliers: [{
        id: 'bad-weekday',
        when: { time: { start: '09:00', end: '18:00', timezone: 'UTC', weekdays: [0] } },
        factors: {},
      }],
    })).toThrow(/weekdays/)
  })

  it('applies atomic replacement semantics through the draft shape and deep-clones rules', () => {
    const source = pricing()
    const cloned = clonePricing(source)
    expect(cloned).toEqual(source)
    cloned.base.cacheWriteByTtl['7200'] = '0.6'
    cloned.overrides[0]!.rates.output = '9'
    expect(source.base.cacheWriteByTtl).toEqual({ '3600': '0.5' })
    expect(source.overrides[0]!.rates.output).toBe('0')
  })

  it('describes structured conditions without raw JSON', () => {
    expect(describeCondition({})).toBe('always')
    expect(describeCondition({ serviceTier: [] })).toBe('tier ∈ ∅')
    expect(describeCondition({
      context: { gt: 1000, lte: 2000 },
      serviceTier: ['priority'],
      time: { start: '09:00', end: '18:00', timezone: 'UTC', weekdays: [1, 2] },
    })).toBe('context > 1000 · context ≤ 2000 · tier ∈ priority · time 09:00-18:00 UTC [1,2]')
  })
})
