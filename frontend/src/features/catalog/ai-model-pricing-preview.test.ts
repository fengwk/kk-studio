import { afterEach, describe, expect, it, vi } from 'vitest'
import { previewModelPricing } from '@/features/catalog/ai-model-pricing-preview'

const rates = {
  input: '3.20',
  output: '9.60',
  cacheRead: '0.10',
  cacheWrite: '0.20',
  cacheWriteByTtl: {},
  reasoning: null,
}

afterEach(() => vi.unstubAllGlobals())

describe('model pricing preview API', () => {
  it('posts pricing and request facts to the authoritative runtime preview endpoint', async () => {
    const fetchMock = vi.fn(async (_url: string, _init: RequestInit) => new Response(JSON.stringify({
      rates,
      activeFrom: '2027-01-01',
      matchedRules: [{
        type: 'input_token_range', minInputTokens: 200001, maxInputTokensExclusive: 300001, input: '2',
      }, {
        type: 'service_tier_multiplier', serviceTier: 'priority', factor: '2',
      }],
    }), { headers: { 'content-type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    const response = await previewModelPricing({
      pricing: {
        base: rates,
        rules: [{ type: 'service_tier_multiplier', serviceTier: 'priority', factor: '2' }],
        changes: [],
      },
      inputTokens: 250000,
      requestedAt: 1798768800000,
      serviceTier: 'priority',
    })

    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, init] = fetchMock.mock.calls[0]!
    expect(url).toBe('/api/platform/models/pricing-preview')
    expect(init?.method).toBe('POST')
    expect(JSON.parse(String(init?.body))).toMatchObject({
      inputTokens: 250000,
      requestedAt: 1798768800000,
      serviceTier: 'priority',
      pricing: { rules: [{ type: 'service_tier_multiplier', serviceTier: 'priority', factor: '2' }] },
    })
    expect(response.rates?.input).toBe('3.20')
    expect(response.matchedRules.map((rule) => rule.type)).toEqual([
      'input_token_range', 'service_tier_multiplier',
    ])
  })
})
