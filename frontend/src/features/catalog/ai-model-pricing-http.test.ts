import axios, { AxiosHeaders } from 'axios'
import type { AxiosAdapter } from 'axios'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import type { HttpClient } from '@/shared/api/client'
import type { AgentModelPricingDTO } from '@/shared/api/generated/contracts'
import { parseJsonPreservingPathEqualsNumbers } from '@/shared/lib/lossless-json-number'
import { clonePricing, normalizePricing } from '@/features/catalog/ai-model-pricing-codec'

const adapter = vi.fn<AxiosAdapter>()
let apiClient: HttpClient

beforeAll(async () => {
  const originalAdapter = axios.defaults.adapter
  axios.defaults.adapter = adapter
  try {
    apiClient = (await import('@/shared/api/client')).apiClient
  } finally {
    axios.defaults.adapter = originalAdapter
  }
})

afterEach(() => {
  adapter.mockReset()
})

describe('request-body pricing HTTP round-trip', () => {
  it('preserves exact numbers from GET draft normalization through PUT serialization', async () => {
    adapter.mockImplementation(async (config) => ({
      config,
      status: 200,
      statusText: 'OK',
      headers: new AxiosHeaders(),
      // 直接构造 JSON 原文：先经 JSON.stringify(number) 就会丢精度。
      data: '{"status":200,"data":{"config":{"pricing":{'
        + '"base":{"input":"1","output":"2","cacheRead":"0","cacheWrite":"0",'
        + '"cacheWriteByTtl":{},"reasoning":null},'
        + '"overrides":[{"id":"r","when":{"requestBody":'
        + '{"path":"/n","equals":9007199254740993}},"rates":{"input":"3"}}],'
        + '"multipliers":[]}}}}',
    }))

    const response = await apiClient.get<{
      config: { pricing: AgentModelPricingDTO }
    }>('/models/example', {
      jsonParser: parseJsonPreservingPathEqualsNumbers,
    })
    const normalized = normalizePricing(clonePricing(response.config.pricing))

    await apiClient.put('/models/example', {
      expectedVersion: '1',
      config: { pricing: normalized },
    })
    const body = adapter.mock.calls[1][0].data
    expect(body).toContain('"equals":9007199254740993')
    expect(body).not.toContain('rawJSON')
  })
})
