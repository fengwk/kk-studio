import { describe, expect, it } from 'vitest'
import type {
  AgentModelPricingDefinitionDTO,
} from '@/shared/api/contracts/ai-catalog'
import {
  clonePricing,
  describeCondition,
  emptyPricingDraft,
  normalizePricing,
} from '@/features/ai/catalog/ai-model-pricing-codec'

function pricing(): AgentModelPricingDefinitionDTO {
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
    expect(describeCondition({
      context: { gt: 1000, lte: 2000 },
      serviceTier: ['priority'],
      time: { start: '09:00', end: '18:00', timezone: 'UTC', weekdays: [1, 2] },
    })).toBe('context > 1000 · context ≤ 2000 · tier ∈ priority · time 09:00-18:00 UTC [1,2]')
  })
})
