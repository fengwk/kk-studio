import { describe, expect, it } from 'vitest'
import type { ModelDraft } from '@/features/catalog/ai-console-types'
import { applyCatalogCandidate } from '@/features/catalog/ai-model-catalog-apply'
import { emptyModelDraft } from '@/features/catalog/ai-model-draft-codec'
import { emptyModelPricingDraft, normalizePricingDraft } from '@/features/catalog/model-pricing-draft'
import type { ModelCatalogCandidateDTO, ModelPricingDTO } from '@/shared/api/generated/contracts'

function pricing(input = '9'): ModelPricingDTO {
  return {
    base: {
      input,
      output: '9',
      cacheRead: '9',
      cacheWrite: '9',
      cacheWriteByTtl: {},
      reasoning: null,
    },
    rules: [],
    changes: [],
  }
}

function draft(): ModelDraft {
  const base = emptyModelDraft({ name: 'provider' })
  return {
    ...base,
    name: 'manual-name',
    modelId: 'manual-wire-id',
    description: 'manual description',
    contextWindow: '1000',
    maxOutputTokens: '100',
    tools: true,
    reasoning: true,
    inputModalities: ['TEXT'],
    pricing: {
      ...emptyModelPricingDraft(),
      definition: {
        ...emptyModelPricingDraft().definition,
        base: { input: '9', output: '9', cacheRead: '9', cacheWrite: '9', cacheWriteByTtl: [], reasoning: null },
      },
    },
    defaultVariant: 'manual',
    variants: [{ ...base.variants[0]!, id: 'manual', promptCacheRetention: 'SHORT' }],
  }
}

function candidate(overrides: Partial<ModelCatalogCandidateDTO> = {}): ModelCatalogCandidateDTO {
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
  it('writes only confirmed limits and abilities, including explicit false', () => {
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

  it('does not infer model abilities from model IDs', () => {
    const before = draft()
    const next = applyCatalogCandidate(before, candidate({ modelId: 'anthropic/claude-sonnet-5.5-20260723' }))
    expect(next.midConversationSystemMessages).toBe(false)
    expect(next.messageLevelEffort).toBe(false)
  })

  it('preserves the existing complete price when directory pricing cannot be imported and retains an explanation', () => {
    const before = draft()
    const directoryPricing = pricing('1')
    const unsupported = applyCatalogCandidate(before, candidate({
      pricingSupported: false,
      supportIssues: ['priority tier has a distinct price table'],
      pricing: directoryPricing,
    }))
    expect(unsupported.pricing).toEqual(before.pricing)
    expect(unsupported.pricingImportNotice).toEqual({
      kind: 'unsupported',
      supportIssues: ['priority tier has a distinct price table'],
      pricing: directoryPricing,
    })
    expect(before.pricing.definition.base.input).toBe('9')
  })

  it('imports a supported pricing plan as a local draft and reports excluded costs separately', () => {
    const directoryPricing = pricing('1.25')
    const before = draft()
    const applied = applyCatalogCandidate(before, candidate({
      pricingSupported: true,
      supportIssues: ['ignored region multiplier', 'non-token cost is not included: web_search'],
      pricing: directoryPricing,
    }))
    expect(normalizePricingDraft(applied.pricing)?.base.input).toBe('1.25')
    expect(applied.pricingImportNotice).toMatchObject({
      kind: 'excluded',
      supportIssues: ['ignored region multiplier', 'non-token cost is not included: web_search'],
    })
    expect(before.pricing.definition.base.input).toBe('9')
  })

  it('keeps existing price if no candidate price is available', () => {
    const before = draft()
    const applied = applyCatalogCandidate(before, candidate({ pricingSupported: true, pricing: null }))
    expect(applied.pricing).toEqual(before.pricing)
    expect(applied.pricingImportNotice).toBeNull()
  })

  it('keeps existing Variants without a complete catalog mapping and preserves cache selections when mapping', () => {
    const before = draft()
    const incomplete = applyCatalogCandidate(before, candidate({
      variants: [{ id: 'fast', promptCacheRetention: 'NONE' }, { id: 'slow', promptCacheRetention: 'NONE' }],
    }))
    expect(incomplete.variants).toEqual(before.variants)

    const mapped = applyCatalogCandidate(before, candidate({
      variants: [
        { id: 'fast', promptCacheRetention: 'NONE', reasoningEffort: 'low' },
        { id: 'slow', promptCacheRetention: 'NONE' },
      ],
      defaultVariant: 'fast',
    }))
    expect(mapped.defaultVariant).toBe('fast')
    expect(mapped.variants.map(({ id, reasoningEffort, promptCacheRetention }) => [id, reasoningEffort, promptCacheRetention]))
      .toEqual([['fast', 'low', 'SHORT'], ['slow', '', 'SHORT']])
  })

  it('does not mutate draft or candidate pricing objects', () => {
    const before = draft()
    const source = candidate({ pricingSupported: true, pricing: pricing('1') })
    const applied = applyCatalogCandidate(before, source)
    applied.pricing.definition.base.cacheWriteByTtl.push({ draftKey: 'ttl-7200', seconds: '7200', rate: '6' })
    expect(before.pricing.definition.base.input).toBe('9')
    expect(source.pricing?.base.cacheWriteByTtl).toEqual({})
  })
})
