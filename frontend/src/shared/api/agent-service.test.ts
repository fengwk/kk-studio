import { describe, expect, it, vi } from 'vitest'
import { createAgentService } from '@/shared/api/agent-service'
import type { HttpClient } from '@/shared/api/client'

function createClient(): HttpClient {
  return {
    get: vi.fn(async () => ({})),
    post: vi.fn(async () => ({})),
    put: vi.fn(async () => ({})),
    delete: vi.fn(async () => ({})),
  }
}

describe('agentService', () => {
  it('maps global resource lists to catalog paths', async () => {
    const client = createClient()
    const service = createAgentService(client)

    await service.listProviders(2, 20)
    await service.listModels(3, 30)
    await service.listAgents(4, 40)
    await service.listTools()

    expect(client.get).toHaveBeenNthCalledWith(1, '/ai/catalog/providers', { params: { pageNumber: 2, pageSize: 20 } })
    expect(client.get).toHaveBeenNthCalledWith(2, '/ai/catalog/models', { params: { pageNumber: 3, pageSize: 30 } })
    expect(client.get).toHaveBeenNthCalledWith(3, '/ai/catalog/agents', { params: { pageNumber: 4, pageSize: 40 } })
    expect(client.get).toHaveBeenNthCalledWith(4, '/ai/catalog/tools')
  })

  it('uses the standard page defaults for global resource lists', async () => {
    const client = createClient()
    const service = createAgentService(client)

    await service.listProviders()
    await service.listModels()
    await service.listAgents()

    expect(client.get).toHaveBeenNthCalledWith(1, '/ai/catalog/providers', { params: { pageNumber: 1, pageSize: 50 } })
    expect(client.get).toHaveBeenNthCalledWith(2, '/ai/catalog/models', { params: { pageNumber: 1, pageSize: 50 } })
    expect(client.get).toHaveBeenNthCalledWith(3, '/ai/catalog/agents', { params: { pageNumber: 1, pageSize: 50 } })
  })

  it('maps provider, model and agent mutations to global id-based CRUD endpoints', async () => {
    const client = createClient()
    const service = createAgentService(client)
    const providerBody = {
      name: 'minimax',
      providerType: 'openai',
      baseUrl: 'https://api.minimax.chat/v1',
      credential: 'key',
      modelCallTimeoutMillis: 1800000,
      modelCallIdleTimeoutMillis: 120000,
    }
    const modelBody = {
      providerId: 'provider-1',
      name: 'MiniMax-M2.7',
      config: {
        limit: { context: 128000, output: 8192 },
        abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
        pricing: {
          currency: 'USD',
          pricingTier: 'default',
          serviceTier: 'default',
          serviceTierMultiplier: 1,
          version: 'v1',
          inputPerMillionTokens: 0,
          outputPerMillionTokens: 0,
          cacheReadPerMillionTokens: 0,
          cacheWritePerMillionTokens: 0,
          cacheWriteLongPerMillionTokens: 0,
          reasoningPerMillionTokens: 0,
        },
        defaultVariant: 'default',
        variants: [{ id: 'default' }],
      },
    }
    const agentBody = {
      name: 'default-assistant',
      description: null,
      systemPrompt: null,
      modelId: 'model-1',
      variant: 'default',
       config: { tools: [], skills: [] },
    }
    const updateProviderBody = { description: 'updated', expectedVersion: '7' }
    const updateModelBody = {
      expectedVersion: '8',
      config: {
        limit: { context: 128000, output: 8192 },
        abilities: { tools: true, reasoning: false, inputModalities: ['TEXT'] },
        pricing: {
          currency: 'USD',
          pricingTier: 'default',
          serviceTier: 'default',
          serviceTierMultiplier: 1,
          version: 'v1',
          inputPerMillionTokens: 0,
          outputPerMillionTokens: 0,
          cacheReadPerMillionTokens: 0,
          cacheWritePerMillionTokens: 0,
          cacheWriteLongPerMillionTokens: 0,
          reasoningPerMillionTokens: 0,
        },
        defaultVariant: 'fast',
        variants: [{ id: 'fast' }],
      },
    }
    const updateAgentBody = { description: 'updated', expectedVersion: '9' }

    await service.createProvider(providerBody)
    await service.updateProvider(101, updateProviderBody)
    await service.deleteProvider(101, '8')
    await service.createModel(modelBody)
    await service.updateModel(202, updateModelBody)
    await service.deleteModel(202, '10')
    await service.createAgent(agentBody)
    await service.updateAgent(303, updateAgentBody)
    await service.deleteAgent(303, '11')

    expect(client.post).toHaveBeenNthCalledWith(1, '/ai/catalog/providers', providerBody)
    expect(client.put).toHaveBeenNthCalledWith(1, '/ai/catalog/providers/101', updateProviderBody)
    expect(client.delete).toHaveBeenNthCalledWith(1, '/ai/catalog/providers/101', { params: { expectedVersion: '8' } })
    expect(client.post).toHaveBeenNthCalledWith(2, '/ai/catalog/models', modelBody)
    expect(client.put).toHaveBeenNthCalledWith(2, '/ai/catalog/models/202', updateModelBody)
    expect(client.delete).toHaveBeenNthCalledWith(2, '/ai/catalog/models/202', { params: { expectedVersion: '10' } })
    expect(client.post).toHaveBeenNthCalledWith(3, '/ai/catalog/agents', agentBody)
    expect(client.put).toHaveBeenNthCalledWith(3, '/ai/catalog/agents/303', updateAgentBody)
    expect(client.delete).toHaveBeenNthCalledWith(3, '/ai/catalog/agents/303', { params: { expectedVersion: '11' } })
  })

})
