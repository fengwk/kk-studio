import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from '@/shared/api/client'
import type { ModelPricingDTO } from '@/shared/api/generated/contracts'
import { parseJsonPreservingPathEqualsNumbers } from '@/shared/lib/lossless-json-number'
import { clonePricing, normalizePricing } from '@/features/catalog/ai-model-pricing-codec'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('request-body pricing HTTP round-trip', () => {
  it('preserves exact numbers from GET draft normalization through PUT serialization', async () => {
    const transport = vi.fn(async (_url: string, _init: RequestInit) => new Response(
      // 直接构造 JSON 原文：先经 JSON.stringify(number) 就会丢精度。
      '{"config":{"pricing":{'
        + '"base":{"input":"1","output":"2","cacheRead":"0","cacheWrite":"0",'
        + '"cacheWriteByTtl":{},"reasoning":null},'
        + '"overrides":[{"id":"r","when":{"requestBody":'
        + '{"path":"/n","equals":9007199254740993}},"rates":{"input":"3"}}],'
        + '"multipliers":[]}}}',
      { headers: { 'Content-Type': 'application/json' } },
    ))
    vi.stubGlobal('fetch', transport)

    const response = await apiClient.get<{
      config: { pricing: ModelPricingDTO }
    }>('/models/example', {
      jsonParser: parseJsonPreservingPathEqualsNumbers,
    })
    const normalized = normalizePricing(clonePricing(response.config.pricing))

    await apiClient.put('/models/example', {
      expectedVersion: '1',
      config: { pricing: normalized },
    })
    const body = transport.mock.calls[1]![1].body
    expect(body).toContain('"equals":9007199254740993')
    expect(body).not.toContain('rawJSON')
  })
})
