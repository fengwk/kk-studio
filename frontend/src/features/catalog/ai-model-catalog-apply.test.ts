import { describe, expect, it } from 'vitest'
import type { ModelDraft } from '@/features/catalog/ai-console-types'
import { applyCatalogCandidate } from '@/features/catalog/ai-model-catalog-apply'
import { emptyModelDraft } from '@/features/catalog/ai-model-draft-codec'
import type { ModelDefaultCandidateDTO } from '@/shared/api/generated/contracts'

function draft(): ModelDraft {
  return {
    ...emptyModelDraft(),
    providerName: 'provider',
    name: 'manual-name',
    modelId: 'manual-wire-id',
    description: 'manual description',
    contextWindow: '1000',
    maxOutputTokens: '100',
    tools: true,
    reasoning: true,
    inputModalities: ['TEXT'],
    pricing: {
      base: { input: '9', output: '9', cacheRead: '9', cacheWrite: '9', cacheWriteByTtl: {}, reasoning: null },
      overrides: [],
      multipliers: [],
    },
    defaultVariant: 'manual',
    variants: [{ draftId: 'manual-1', id: 'manual', reasoningEffort: '', protocolOptionsJson: '' }],
  }
}

function candidate(overrides: Partial<ModelDefaultCandidateDTO> = {}): ModelDefaultCandidateDTO {
  return {
    id: 'candidate-1',
    provider: 'catalog-provider',
    modelId: 'catalog-wire-id',
    name: 'catalog-name',
    abilities: {},
    supportIssues: [],
    pricingSupported: false,
    ...overrides,
  }
}

describe('applyCatalogCandidate', () => {
  it('writes only confirmable limit and ability facts, including explicit false', () => {
    const next = applyCatalogCandidate(draft(), candidate({
      limit: { context: 200000 },
      abilities: { tools: false, reasoning: true },
    }))
    expect(next).toMatchObject({
      providerName: 'provider',
      name: 'manual-name',
      modelId: 'manual-wire-id',
      description: 'manual description',
      contextWindow: '200000',
      maxOutputTokens: '100',
      tools: false,
      reasoning: true,
      inputModalities: ['TEXT'],
    })
  })

  it('does not replace pricing unless it is supported and complete', () => {
    const before = draft()
    const unsupported = applyCatalogCandidate(before, candidate({
      pricingSupported: false,
      pricing: {
        base: { input: '1', output: '2', cacheRead: '3', cacheWrite: '4', cacheWriteByTtl: {}, reasoning: null },
        overrides: [],
        multipliers: [],
      },
    }))
    expect(unsupported.pricing).toEqual(before.pricing)

    const supported = applyCatalogCandidate(before, candidate({
      pricingSupported: true,
      pricing: {
        base: { input: '1.1', output: '2.2', cacheRead: '0.3', cacheWrite: '0.4', cacheWriteByTtl: {}, reasoning: '0' },
        overrides: [],
        multipliers: [],
      },
    }))
    expect(supported.pricing.base).toMatchObject({ input: '1.1', output: '2.2', reasoning: '0' })
    expect(supported.pricing).not.toBe(before.pricing)
  })

  it('keeps the existing variants without a complete, mappable default variant', () => {
    const before = draft()
    const noDefault = applyCatalogCandidate(before, candidate({
      variants: [{ id: 'fast' }, { id: 'slow' }],
    }))
    expect(noDefault.variants).toEqual(before.variants)
    expect(noDefault.defaultVariant).toBe('manual')

    const unmappable = applyCatalogCandidate(before, candidate({
      defaultVariant: 'fast',
      variants: [{ id: 'fast' }, { id: ' ' }],
    }))
    expect(unmappable.variants).toEqual(before.variants)
  })

  it('replaces variants only when every id maps and the default exists', () => {
    const next = applyCatalogCandidate(draft(), candidate({
      variants: [{ id: 'fast', reasoningEffort: 'low' }, { id: 'slow' }],
      defaultVariant: 'fast',
    }))
    expect(next.defaultVariant).toBe('fast')
    expect(next.variants.map((variant) => [variant.id, variant.reasoningEffort])).toEqual([
      ['fast', 'low'],
      ['slow', ''],
    ])
  })

  it('does not mutate the input draft or reuse candidate pricing objects', () => {
    const before = draft()
    const source = candidate({
      pricingSupported: true,
      pricing: {
        base: { input: '1', output: '2', cacheRead: '3', cacheWrite: '4', cacheWriteByTtl: { '3600': '5' }, reasoning: null },
        overrides: [],
        multipliers: [],
      },
    })
    const next = applyCatalogCandidate(before, source)
    next.pricing.base.cacheWriteByTtl['7200'] = '6'
    expect(before.pricing.base.input).toBe('9')
    expect(source.pricing?.base.cacheWriteByTtl).toEqual({ '3600': '5' })
  })
})
