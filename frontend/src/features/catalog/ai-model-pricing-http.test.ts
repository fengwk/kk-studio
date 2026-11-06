import { afterEach, describe, expect, it, vi } from 'vitest'
import { request } from '@/shared/api/client'
import type { ModelDTO, ModelUpdateDTO } from '@/shared/api/generated/contracts'
import { parseJsonPreservingPathEqualsNumbers } from '@/shared/lib/lossless-json-number'
import { clonePricing, normalizePricing } from '@/features/catalog/ai-model-pricing-codec'

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('request-body pricing HTTP round-trip', () => {
  it('preserves exact numbers from GET draft normalization through PUT serialization', async () => {
    const transport = vi.fn(async (_url: string, _init: RequestInit) => new Response(
      // 直接构造 JSON 原文：先经 JSON.stringify(number) 就会丢精度。
      '[{"type":"MODEL","name":"example","providerName":"stub","modelId":"example",'
        + '"config":{"limit":{"context":200000,"output":8192},'
        + '"abilities":{"tools":true,"reasoning":false,"inputModalities":["TEXT"]},'
        + '"pricing":{'
        + '"base":{"input":"1","output":"2","cacheRead":"0","cacheWrite":"0",'
        + '"cacheWriteByTtl":{},"reasoning":null},'
        + '"overrides":[{"id":"r","when":{"requestBody":'
        + '{"path":"/n","equals":9007199254740993}},"rates":{"input":"3"}}],'
        + '"multipliers":[]},'
        + '"variants":[]},'
        + '"version":"1","createdAt":1792454400000,"updatedAt":1792454400000}]',
      { headers: { 'Content-Type': 'application/json' } },
    ))
    vi.stubGlobal('fetch', transport)

    const models = await request('GET /api/platform/models', {}, {
      jsonParser: parseJsonPreservingPathEqualsNumbers,
    }) as ModelDTO[]
    const model = models[0]!
    if (model.type !== 'MODEL') {
      throw new Error('fixture is a single model')
    }
    if (model.config.pricing == null) {
      throw new Error('fixture has pricing')
    }
    const normalized = normalizePricing(clonePricing(model.config.pricing))

    const update: ModelUpdateDTO = {
      type: 'MODEL',
      name: model.name,
      description: model.description,
      providerName: model.providerName,
      modelId: model.modelId,
      config: { ...model.config, pricing: normalized },
      expectedVersion: model.version,
    }
    await request('PUT /api/platform/models/{*name}', {
      path: { name: model.name },
      body: update,
    })
    const body = transport.mock.calls[1]![1].body
    expect(body).toContain('"equals":9007199254740993')
    expect(body).not.toContain('rawJSON')
  })
})
