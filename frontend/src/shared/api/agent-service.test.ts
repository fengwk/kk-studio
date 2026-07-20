import { describe, expect, it, vi } from 'vitest'
import { createAgentService } from '@/shared/api/agent-service'
import type { HttpClient } from '@/shared/api/client'
import type { ModelUsageSummaryDTO } from '@/shared/api/contracts'

function createClient(): HttpClient {
  return {
    get: vi.fn(async () => ({})),
    post: vi.fn(async () => ({})),
    put: vi.fn(async () => ({})),
    delete: vi.fn(async () => ({})),
  }
}

describe('agentService', () => {
  it('maps global resource lists to top-level paths', async () => {
    const client = createClient()
    const service = createAgentService(client)

    await service.listProviders(2, 20)
    await service.listModels(3, 30)
    await service.listAgents(4, 40)

    expect(client.get).toHaveBeenNthCalledWith(1, '/providers', { params: { pageNumber: 2, pageSize: 20 } })
    expect(client.get).toHaveBeenNthCalledWith(2, '/models', { params: { pageNumber: 3, pageSize: 30 } })
    expect(client.get).toHaveBeenNthCalledWith(3, '/agents', { params: { pageNumber: 4, pageSize: 40 } })
  })

  it('uses the standard page defaults for global resource lists', async () => {
    const client = createClient()
    const service = createAgentService(client)

    await service.listProviders()
    await service.listModels()
    await service.listAgents()

    expect(client.get).toHaveBeenNthCalledWith(1, '/providers', { params: { pageNumber: 1, pageSize: 50 } })
    expect(client.get).toHaveBeenNthCalledWith(2, '/models', { params: { pageNumber: 1, pageSize: 50 } })
    expect(client.get).toHaveBeenNthCalledWith(3, '/agents', { params: { pageNumber: 1, pageSize: 50 } })
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
      provider: 'minimax',
      name: 'MiniMax-M2.7',
      defaultVariant: 'default',
      variantsJson: '[{"name":"default"}]',
    }
    const agentBody = {
      name: 'default-assistant',
      modelId: 'model-1',
      variant: 'default',
      config: { tools: [], skills: [], allowedSubagents: [] },
    }
    const updateProviderBody = { description: 'updated' }
    const updateModelBody = { defaultVariant: 'fast' }
    const updateAgentBody = { description: 'updated' }

    await service.createProvider(providerBody)
    await service.updateProvider(101, updateProviderBody)
    await service.deleteProvider(101)
    await service.createModel(modelBody)
    await service.updateModel(202, updateModelBody)
    await service.deleteModel(202)
    await service.createAgent(agentBody)
    await service.updateAgent(303, updateAgentBody)
    await service.deleteAgent(303)

    expect(client.post).toHaveBeenNthCalledWith(1, '/providers', providerBody)
    expect(client.put).toHaveBeenNthCalledWith(1, '/providers/101', updateProviderBody)
    expect(client.delete).toHaveBeenNthCalledWith(1, '/providers/101')
    expect(client.post).toHaveBeenNthCalledWith(2, '/models', modelBody)
    expect(client.put).toHaveBeenNthCalledWith(2, '/models/202', updateModelBody)
    expect(client.delete).toHaveBeenNthCalledWith(2, '/models/202')
    expect(client.post).toHaveBeenNthCalledWith(3, '/agents', agentBody)
    expect(client.put).toHaveBeenNthCalledWith(3, '/agents/303', updateAgentBody)
    expect(client.delete).toHaveBeenNthCalledWith(3, '/agents/303')
  })

  it('maps usage summaries to encoded thread, session and model endpoints', async () => {
    // Explicit promise annotations keep all three service return contracts tied to the backend summary DTO.
    const client = createClient()
    const service = createAgentService(client)

    const threadUsage: Promise<ModelUsageSummaryDTO> = service.getThreadUsage('thread /1')
    const sessionUsage: Promise<ModelUsageSummaryDTO> = service.getSessionUsage('session /2')
    const modelUsage: Promise<ModelUsageSummaryDTO> = service.getModelUsage('model /3')
    await Promise.all([threadUsage, sessionUsage, modelUsage])

    expect(client.get).toHaveBeenNthCalledWith(1, '/usage/threads/thread%20%2F1')
    expect(client.get).toHaveBeenNthCalledWith(2, '/usage/sessions/session%20%2F2')
    expect(client.get).toHaveBeenNthCalledWith(3, '/usage/models/model%20%2F3')
  })

})
